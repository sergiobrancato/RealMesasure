package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceDetection
import com.realmeasure.app.model.ReferenceMode
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the selected ISO reference (ID-1 card or A4 sheet) as a convex
 * 4-vertex polygon anywhere in the frame.
 *
 * The live scanner uses a small working copy for speed; the still calibrator
 * reuses the same detector at a higher resolution and then performs sub-pixel
 * corner refinement on the full-resolution grayscale image.
 */
object ReferenceDetector {

    /**
     * @param bitmap upright preview/still bitmap.
     * @param mode which physical reference to look for.
     * @param maxWidth working width for the search; smaller is faster.
     */
    fun detect(
        bitmap: Bitmap,
        mode: ReferenceMode,
        maxWidth: Int = AppConfig.Detection.WORKING_WIDTH_PX,
    ): ReferenceDetection? = withMatPool {
        val rgba = Mat().tracked()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat().tracked()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        detectGray(gray, mode, maxWidth)
    }

    /**
     * Grayscale overload used by the full-resolution calibration pass.
     * Returned points are in the coordinate system of [gray].
     */
    fun detectGray(
        gray: Mat,
        mode: ReferenceMode,
        maxWidth: Int = AppConfig.Calibration.DETECT_MAX_WIDTH_PX,
    ): ReferenceDetection? = withMatPool {
        if (gray.cols() < 40 || gray.rows() < 40) return null

        val scale = if (gray.cols() > maxWidth) maxWidth.toDouble() / gray.cols() else 1.0
        val working = if (scale < 1.0) {
            val resized = Mat()
            Imgproc.resize(
                gray,
                resized,
                Size((gray.cols() * scale).toInt().toDouble(), (gray.rows() * scale).toInt().toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            resized.tracked()
        } else {
            gray
        }

        val blurred = Mat().tracked()
        Imgproc.GaussianBlur(working, blurred, Size(5.0, 5.0), 0.0)

        val enhanced = Mat().tracked()
        val clahe = Imgproc.createCLAHE(
            AppConfig.Detection.CLAHE_CLIP_LIMIT,
            Size(AppConfig.Detection.CLAHE_TILE_GRID, AppConfig.Detection.CLAHE_TILE_GRID),
        )
        clahe.apply(blurred, enhanced)
        clahe.collectGarbage()

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(
                AppConfig.Detection.CLOSE_KERNEL.toDouble(),
                AppConfig.Detection.CLOSE_KERNEL.toDouble(),
            ),
        ).tracked()

        val adaptive = Mat().tracked()
        Imgproc.adaptiveThreshold(
            enhanced,
            adaptive,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            AppConfig.Analysis.BINARY_BLOCK,
            AppConfig.Analysis.BINARY_C,
        )
        val adaptiveClosed = Mat().tracked()
        Imgproc.morphologyEx(adaptive, adaptiveClosed, Imgproc.MORPH_CLOSE, kernel)

        val edges = Mat().tracked()
        Imgproc.Canny(
            enhanced,
            edges,
            AppConfig.Detection.CANNY_LOW,
            AppConfig.Detection.CANNY_HIGH,
        )
        val edgesClosed = Mat().tracked()
        Imgproc.morphologyEx(edges, edgesClosed, Imgproc.MORPH_CLOSE, kernel)

        val best = listOfNotNull(
            evaluateMask(adaptiveClosed, mode, working.cols(), working.rows()),
            evaluateMask(edgesClosed, mode, working.cols(), working.rows()),
        ).maxByOrNull { it.score } ?: return null

        if (scale >= 1.0) return best.detection

        // Scale corners back to the caller's full-resolution coordinates.
        val inverse = 1.0 / scale
        val scaledQuad = best.detection.quad.map { Pt(it.x * inverse, it.y * inverse) }
        ReferenceDetection(
            quad = scaledQuad,
            score = best.detection.score,
            aspectError = best.detection.aspectError,
        )
    }

    private data class ScoredDetection(
        val detection: ReferenceDetection,
        val score: Double,
    )

    private fun MatPool.evaluateMask(
        mask: Mat,
        mode: ReferenceMode,
        frameWidth: Int,
        frameHeight: Int,
    ): ScoredDetection? {
        val cfg = AppConfig.Detection
        val frameArea = frameWidth.toDouble() * frameHeight
        val borderMarginX = frameWidth * cfg.EDGE_MARGIN_RATIO
        val borderMarginY = frameHeight * cfg.EDGE_MARGIN_RATIO

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().tracked()
        var best: ScoredDetection? = null

        try {
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val areaRatio = area / frameArea
                if (areaRatio < cfg.MIN_AREA_RATIO || areaRatio > cfg.MAX_AREA_RATIO) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                try {
                    val perimeter = Imgproc.arcLength(contour2f, true)
                    if (perimeter < cfg.MIN_QUAD_SIDE_PX * 4.0) continue

                    val approx = MatOfPoint2f()
                    try {
                        var points: List<Pt> = emptyList()
                        for (epsilonRatio in listOf(0.02, 0.035, 0.05)) {
                            Imgproc.approxPolyDP(contour2f, approx, perimeter * epsilonRatio, true)
                            points = approx.toArray().map { Pt(it.x, it.y) }
                            if (points.size == 4) break
                        }
                        if (points.size != 4) continue

                        val ordered = Geometry.orderQuad(points)
                        if (!Geometry.contains(ordered, Geometry.polygonCentroid(ordered))) {
                            // The ordered polygon is self-intersecting; angular
                            // ordering often fixes extreme perspective cases.
                            continue
                        }

                        val (sideA, sideB) = Geometry.quadSides(ordered)
                        if (min(sideA, sideB) < cfg.MIN_QUAD_SIDE_PX) continue

                        val aspect = max(sideA, sideB) / min(sideA, sideB)
                        val aspectError = abs(aspect - mode.trueAspect) / mode.trueAspect
                        if (aspectError > cfg.ASPECT_TOLERANCE) continue

                        if (ordered.any {
                                it.x <= borderMarginX || it.y <= borderMarginY ||
                                    it.x >= frameWidth - borderMarginX ||
                                    it.y >= frameHeight - borderMarginY
                            }
                        ) {
                            continue
                        }

                        val rotated = Imgproc.minAreaRect(contour2f)
                        val rectArea = rotated.size.width * rotated.size.height
                        val solidity = if (rectArea > 1e-6) {
                            (area / rectArea).coerceIn(0.0, 1.0)
                        } else {
                            0.0
                        }
                        if (solidity < cfg.MIN_SOLIDITY) continue

                        val aspectScore = 1.0 - (aspectError / cfg.ASPECT_TOLERANCE)
                        val areaScore = (areaRatio / 0.25).coerceIn(0.0, 1.0)
                        val score = 0.45 * aspectScore + 0.30 * solidity + 0.25 * areaScore

                        val detection = ReferenceDetection(
                            quad = ordered,
                            score = score.coerceIn(0.0, 1.0),
                            aspectError = aspectError,
                        )
                        if (best == null || score > best!!.score) {
                            best = ScoredDetection(detection, score)
                        }
                    } finally {
                        approx.release()
                    }
                } finally {
                    contour2f.release()
                }
            }
        } finally {
            contours.forEach { runCatching { it.release() } }
        }
        return best
    }
}
