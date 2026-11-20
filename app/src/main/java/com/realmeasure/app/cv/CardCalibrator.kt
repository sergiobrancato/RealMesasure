package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.CalibrationResult
import com.realmeasure.app.model.CardQuad
import com.realmeasure.app.model.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Card calibration.
 *
 * 1. Crop the amber guide's search window from the captured bitmap.
 * 2. Find the largest four-corner contour whose aspect ratio matches an
 *    ISO/IEC 7810 ID-1 card (85.60 x 53.98 mm).
 * 3. Build a metric homography (source pixels -> millimetres on the card
 *    plane) with Imgproc.getPerspectiveTransform.
 * 4. Warp a rectified card thumbnail with Imgproc.warpPerspective for the
 *    calibration audit panel, and report the exact px/mm scale.
 *
 * FastenerAnalyzer consumes the homography to remove camera tilt when it
 * rectifies the cyan scan zone.
 */
object CardCalibrator {

    /** Typed failure carrying a user-facing hint. */
    class CalibrationException(val code: String, message: String, val hint: String) :
        Exception(message)

    /** Calibrate + produce the small rectified card preview shown on the result screen. */
    data class CalibrationOutcome(
        val result: CalibrationResult,
        val cardPreview: Bitmap?,
    )

    /** Detect and validate the reference card in the left viewfinder guide. */
    fun calibrate(bitmap: Bitmap, includePreview: Boolean = true): CalibrationOutcome =
        withMatPool {
            val source = Mat().tracked()
            Utils.bitmapToMat(bitmap, source)

            val gray = Mat().tracked()
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)

            // ---- Search crop (card guide + margin) ------------------------
            val guides = AppConfig.Guides
            val padX = guides.CARD_W * guides.CARD_SEARCH_PADDING
            val padY = guides.CARD_H * guides.CARD_SEARCH_PADDING
            val left = floor(max(0.0, (guides.CARD_X - padX) * gray.cols())).toInt()
            val top = floor(max(0.0, (guides.CARD_Y - padY) * gray.rows())).toInt()
            val right = ceil(min(1.0, guides.CARD_X + guides.CARD_W + padX) * gray.cols()).toInt()
            val bottom = ceil(min(1.0, guides.CARD_Y + guides.CARD_H + padY) * gray.rows()).toInt()
            val searchRect = Rect(left, top, max(8, right - left), max(8, bottom - top))

            val search = crop(gray, searchRect)
            if (search.cols() < 60 || search.rows() < 40) {
                throw CalibrationException(
                    "CARD_TOO_SMALL",
                    "The card guide is too small in the captured photo.",
                    "Move the phone closer, or use a photo with more resolution.",
                )
            }

            // ---- Binarisation ----------------------------------------------
            val cv = AppConfig.CardCv
            val blurred = Mat().tracked()
            Imgproc.GaussianBlur(
                search,
                blurred,
                Size(cv.GAUSSIAN_KERNEL.toDouble(), cv.GAUSSIAN_KERNEL.toDouble()),
                0.0,
            )
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(3.0, 3.0),
            ).tracked()

            val adaptiveMask = Mat().tracked()
            Imgproc.adaptiveThreshold(
                blurred, adaptiveMask, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                cv.ADAPTIVE_BLOCK, cv.ADAPTIVE_C,
            )
            val adaptiveClosed = Mat().tracked()
            Imgproc.morphologyEx(adaptiveMask, adaptiveClosed, Imgproc.MORPH_CLOSE, kernel)

            val edges = Mat().tracked()
            Imgproc.Canny(blurred, edges, cv.CANNY_LOW, cv.CANNY_HIGH)
            val edgesClosed = Mat().tracked()
            Imgproc.morphologyEx(edges, edgesClosed, Imgproc.MORPH_CLOSE, kernel)

            // ---- Best four-corner candidate --------------------------------
            val candidates = listOfNotNull(
                evaluateMask(adaptiveClosed, searchRect.width, searchRect.height),
                evaluateMask(edgesClosed, searchRect.width, searchRect.height),
            )
            val best = candidates.maxByOrNull { it.score }
                ?: throw CalibrationException(
                    "CARD_NOT_FOUND",
                    "The bank/ID card was not found inside the amber guide.",
                    "Lay the card flat, keep all four corners visible, and avoid glare on the card.",
                )

            // Reject quads whose corners touch the search window (card clipped).
            val guardX = searchRect.width * cv.EDGE_GUARD_RATIO
            val guardY = searchRect.height * cv.EDGE_GUARD_RATIO
            val clipped = best.quad.any { point ->
                point.x <= guardX || point.y <= guardY ||
                    point.x >= searchRect.width - guardX ||
                    point.y >= searchRect.height - guardY
            }
            if (clipped) {
                throw CalibrationException(
                    "CARD_CLIPPED",
                    "Part of the reference card is outside the guide.",
                    "Pull the phone back until the entire card sits inside the amber frame.",
                )
            }

            // ---- Full-image quad and metric scale --------------------------
            val fullPoints = best.quad.map { Pt(it.x + searchRect.x, it.y + searchRect.y) }
            val quad = Geometry.orderQuad(fullPoints)
            val ordered = Geometry.quadPoints(quad)
            val (widthPx, heightPx) = Geometry.quadSides(quad)

            val cardW = AppConfig.CARD_WIDTH_MM
            val cardH = AppConfig.CARD_HEIGHT_MM
            val pixelsPerMmX = widthPx / cardW
            val pixelsPerMmY = heightPx / cardH
            val pixelsPerMm = (pixelsPerMmX + pixelsPerMmY) / 2.0
            val scaleDiscrepancy = abs(pixelsPerMmX - pixelsPerMmY) / max(pixelsPerMm, 1e-6)

            if (scaleDiscrepancy > cv.MAX_SCALE_DISCREPANCY) {
                throw CalibrationException(
                    "SCALE_MISMATCH",
                    "The card appears skewed or partly folded.",
                    "Check that the card is flat and not bent, then align its edges with the amber frame.",
                )
            }

            // ---- Metric homography (source px -> card millimetres) ---------
            val metricPoints = listOf(
                Pt(0.0, 0.0),
                Pt(cardW, 0.0),
                Pt(cardW, cardH),
                Pt(0.0, cardH),
            )
            val src2f = MatOfPoint2f(*ordered.map { it.toPoint() }.toTypedArray()).tracked()
            val dst2f = MatOfPoint2f(*metricPoints.map { it.toPoint() }.toTypedArray()).tracked()
            val transformMat = Imgproc.getPerspectiveTransform(src2f, dst2f)

            val homography = DoubleArray(9)
            transformMat.get(0, 0, homography)
            transformMat.release()

            val errorPx = Geometry.reprojectionError(homography, ordered, metricPoints)
            val scaleScore = 1.0 - (scaleDiscrepancy / cv.MAX_SCALE_DISCREPANCY).coerceIn(0.0, 1.0)
            val errorScore = 1.0 - (errorPx / 2.5).coerceIn(0.0, 1.0)
            val confidence = (0.55 * best.score + 0.30 * scaleScore + 0.15 * errorScore)
                .coerceIn(0.0, 1.0)

            // ---- Rectified card thumbnail ----------------------------------
            val preview = if (includePreview) {
                buildRectifiedPreview(source, ordered, cardW, cardH)
            } else {
                null
            }

            CalibrationOutcome(
                result = CalibrationResult(
                    pixelsPerMm = pixelsPerMm,
                    pixelsPerMmX = pixelsPerMmX,
                    pixelsPerMmY = pixelsPerMmY,
                    cardWidthMm = cardW,
                    cardHeightMm = cardH,
                    cardQuad = quad,
                    homography = homography,
                    confidence = confidence,
                    scaleDiscrepancy = scaleDiscrepancy,
                    reprojectionErrorPx = errorPx,
                    sourceWidthPx = bitmap.width,
                    sourceHeightPx = bitmap.height,
                ),
                cardPreview = preview,
            )
        }

    // ------------------------------------------------------------------
    // Quad scoring
    // ------------------------------------------------------------------

    private data class QuadCandidate(val quad: List<Pt>, val score: Double)

    /** Score every four-corner contour inside one binarised search crop. */
    private fun MatPool.evaluateMask(mask: Mat, width: Int, height: Int): QuadCandidate? {
        val cv = AppConfig.CardCv
        val roiArea = width.toDouble() * height
        val cardAspect = AppConfig.CARD_ASPECT
        val center = Pt(width / 2.0, height / 2.0)
        val halfDiagonal = hypot(width.toDouble(), height.toDouble()) / 2.0

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().tracked()
        var best: QuadCandidate? = null
        try {
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val coverage = area / roiArea
                if (coverage < cv.MIN_AREA_RATIO || coverage > cv.MAX_AREA_RATIO) continue

                val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                if (perimeter < 80.0) continue

                val approx = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(
                        MatOfPoint2f(*contour.toArray()),
                        approx,
                        perimeter * cv.APPROX_EPSILON_RATIO,
                        true,
                    )
                    val points = approx.toArray().map { it.toPt() }
                    if (points.size != 4) continue

                    val quad = Geometry.orderQuad(points)
                    val (quadWidth, quadHeight) = Geometry.quadSides(quad)
                    if (quadWidth < 20.0 || quadHeight < 20.0) continue

                    val aspect = max(quadWidth, quadHeight) / min(quadWidth, quadHeight)
                    val aspectError = abs(aspect - cardAspect) / cardAspect
                    if (aspectError > cv.MAX_ASPECT_ERROR) continue

                    val quadCenter = Pt(
                        points.sumOf { it.x } / 4.0,
                        points.sumOf { it.y } / 4.0,
                    )
                    val centerDistance = Geometry.distance(quadCenter, center) / halfDiagonal
                    val aspectScore = 1.0 - (aspectError / cv.MAX_ASPECT_ERROR).coerceIn(0.0, 1.0)
                    val areaScore = (coverage / 0.55).coerceIn(0.0, 1.0)
                    val centerScore = 1.0 - centerDistance.coerceIn(0.0, 1.0)
                    val score = 0.50 * aspectScore + 0.35 * areaScore + 0.15 * centerScore

                    if (best == null || score > best!!.score) {
                        best = QuadCandidate(points, score)
                    }
                } finally {
                    approx.release()
                }
            }
        } finally {
            contours.forEach { mat -> runCatching { mat.release() } }
        }
        return best
    }

    /** Warp the card into a metric thumbnail proving the perspective correction. */
    private fun buildRectifiedPreview(
        source: Mat,
        cardPoints: List<Pt>,
        cardW: Double,
        cardH: Double,
    ): Bitmap? {
        val pxPerMm = AppConfig.CardCv.RECTIFIED_PX_PER_MM
        val width = max(32.0, cardW * pxPerMm).toInt()
        val height = max(24.0, cardH * pxPerMm).toInt()
        val destination = listOf(
            Pt(0.0, 0.0),
            Pt(width.toDouble(), 0.0),
            Pt(width.toDouble(), height.toDouble()),
            Pt(0.0, height.toDouble()),
        )

        var src2f: MatOfPoint2f? = null
        var dst2f: MatOfPoint2f? = null
        var transform: Mat? = null
        var warped: Mat? = null
        return try {
            src2f = MatOfPoint2f(*cardPoints.map { it.toPoint() }.toTypedArray())
            dst2f = MatOfPoint2f(*destination.map { it.toPoint() }.toTypedArray())
            transform = Imgproc.getPerspectiveTransform(src2f, dst2f)
            warped = Mat()
            Imgproc.warpPerspective(
                source, warped, transform,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT,
                Scalar(16.0, 13.0, 11.0, 255.0),
            )
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, bitmap)
            bitmap
        } catch (exception: Exception) {
            null
        } finally {
            runCatching { src2f?.release() }
            runCatching { dst2f?.release() }
            runCatching { transform?.release() }
            runCatching { warped?.release() }
        }
    }
}
