package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceMode
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-resolution planar calibration.
 *
 * Pipeline:
 *  1. find the selected reference in a downscaled copy of the still,
 *  2. refine its four corners with cornerSubPix on the full-resolution gray,
 *  3. reject excessive perspective tilt using the interior-angle gate,
 *  4. build H0 that maps the reference to an exact W_ref x H_ref metric plane,
 *  5. project the full image corners with Core.perspectiveTransform,
 *  6. translate H0 so the full scene (not just the reference) is preserved,
 *  7. warp the whole image at s px/mm, clamping the canvas to 2400 px.
 *
 * The incoming still is downscaled so its longest side is at most
 * [AppConfig.Calibration.MAX_SOURCE_SIDE_PX] *before* it becomes a Mat. All
 * corner detection, sub-pixel refinement and homography coordinates therefore
 * live in that single scaled frame, so repeated scans cannot exhaust the native
 * or Java heap.
 *
 * Every pixel of the returned bitmap is exactly 1 / pixelsPerMm millimetres.
 */
object PrecisionCalibrator {

    fun calibrate(still: Bitmap, mode: ReferenceMode): CalibrationResult {
        val maxSide = AppConfig.Calibration.MAX_SOURCE_SIDE_PX
        val longest = max(still.width, still.height)
        val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        val sourceBitmap = if (scale < 1.0) {
            val width = (still.width * scale).roundToInt().coerceAtLeast(1)
            val height = (still.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(still, width, height, true)
        } else {
            still
        }

        try {
            return withMatPool {
                val source = Mat().tracked()
                Utils.bitmapToMat(sourceBitmap, source)
                val gray = Mat().tracked()
                Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)

                val detection = ReferenceDetector.detectGray(
                    gray = gray,
                    mode = mode,
                    maxWidth = AppConfig.Calibration.DETECT_MAX_WIDTH_PX,
                ) ?: throw CalibrationException(
                    "REFERENCE_NOT_FOUND",
                    "The ${mode.label} was not found in the captured photo. " +
                        "Keep the whole reference flat, visible and well lit.",
                )

                val refined = refineCorners(gray, detection.quad)
                val ordered = Geometry.orientLongEdgeFirst(Geometry.orderQuad(refined))
                if (ordered.any { !it.x.isFinite() || !it.y.isFinite() }) {
                    throw CalibrationException(
                        "REFERENCE_DISTORTED",
                        "The reference corners look distorted. Flatten it and try again.",
                    )
                }

                val imageWidth = gray.cols()
                val imageHeight = gray.rows()
                val border = min(imageWidth, imageHeight) * 0.005
                if (ordered.any {
                        it.x <= border || it.y <= border ||
                            it.x >= imageWidth - border || it.y >= imageHeight - border
                    }
                ) {
                    throw CalibrationException(
                        "REFERENCE_CLIPPED",
                        "Part of the reference is outside the photo. Pull the phone back so the whole " +
                            "${mode.label} is visible.",
                    )
                }

                val tilt = Geometry.maxTiltDeviationDeg(ordered)
                if (tilt > AppConfig.Calibration.MAX_TILT_DEVIATION_DEG) {
                    throw CalibrationException(
                        "TILT_TOO_HIGH",
                        "Camera tilt is too high (%.0f° corner deviation). Hold the phone parallel " +
                            "to the surface and try again.".format(tilt),
                    )
                }

                val (topWidthPx, sideHeightPx) = Geometry.quadSides(ordered)
                if (topWidthPx < 24.0 || sideHeightPx < 16.0) {
                    throw CalibrationException(
                        "REFERENCE_TOO_SMALL",
                        "The reference appears too small. Move closer or switch to A4 Paper mode.",
                    )
                }

                // s_native = (|P1-P0| + |P2-P3|) / (2 * W_ref)
                val nativePxPerMm = topWidthPx / mode.widthMm
                if (!nativePxPerMm.isFinite() || nativePxPerMm <= 0.0) {
                    throw CalibrationException(
                        "REFERENCE_DISTORTED",
                        "Could not derive a metric scale from the reference. Try again.",
                    )
                }

                val sWarp = mode.warpPxPerMm
                val target = listOf(
                    Pt(0.0, 0.0),
                    Pt(sWarp * mode.widthMm, 0.0),
                    Pt(sWarp * mode.widthMm, sWarp * mode.heightMm),
                    Pt(0.0, sWarp * mode.heightMm),
                )

                val src2f = MatOfPoint2f(*ordered.map { Point(it.x, it.y) }.toTypedArray()).tracked()
                val dst2f = MatOfPoint2f(*target.map { Point(it.x, it.y) }.toTypedArray()).tracked()
                val h0 = Imgproc.getPerspectiveTransform(src2f, dst2f).tracked()
                val h0Array = DoubleArray(9)
                h0.get(0, 0, h0Array)

                val fullCorners = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(imageWidth.toDouble(), 0.0),
                    Point(imageWidth.toDouble(), imageHeight.toDouble()),
                    Point(0.0, imageHeight.toDouble()),
                ).tracked()
                val projected = MatOfPoint2f().tracked()
                Core.perspectiveTransform(fullCorners, projected, h0)
                val projectedPoints = projected.toArray().map { Pt(it.x, it.y) }
                if (projectedPoints.any { !it.x.isFinite() || !it.y.isFinite() }) {
                    throw CalibrationException(
                        "WARP_FAILED",
                        "Could not flatten the scene. Hold the phone flatter and try again.",
                    )
                }

                val xMin = projectedPoints.minOf { it.x }
                val xMax = projectedPoints.maxOf { it.x }
                val yMin = projectedPoints.minOf { it.y }
                val yMax = projectedPoints.maxOf { it.y }
                val rawWidth = xMax - xMin
                val rawHeight = yMax - yMin
                if (rawWidth <= 1.0 || rawHeight <= 1.0 || rawWidth > 40_000 || rawHeight > 40_000) {
                    throw CalibrationException(
                        "WARP_FAILED",
                        "The flattened canvas was invalid. Re-aim at the scene and try again.",
                    )
                }

                val maxCanvas = AppConfig.Calibration.MAX_CANVAS_PX.toDouble()
                val factor = min(1.0, maxCanvas / max(rawWidth, rawHeight))
                val canvasWidth = ceil(rawWidth * factor).toInt().coerceAtLeast(1)
                val canvasHeight = ceil(rawHeight * factor).toInt().coerceAtLeast(1)

                val h = Geometry.multiply3x3(
                    Geometry.scaling(factor),
                    Geometry.multiply3x3(
                        Geometry.translation(-xMin, -yMin),
                        h0Array,
                    ),
                )
                val hMat = Mat(3, 3, CvType.CV_64F).tracked()
                hMat.put(0, 0, *h)

                val warped = Mat().tracked()
                Imgproc.warpPerspective(
                    source,
                    warped,
                    hMat,
                    Size(canvasWidth.toDouble(), canvasHeight.toDouble()),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT,
                    Scalar(17.0, 21.0, 26.0, 255.0),
                )

                val warpedBitmap = Bitmap.createBitmap(
                    canvasWidth,
                    canvasHeight,
                    Bitmap.Config.ARGB_8888,
                )
                Utils.matToBitmap(warped, warpedBitmap)

                val pixelsPerMm = sWarp * factor
                val referenceRect = referenceRect(
                    xMin = xMin,
                    yMin = yMin,
                    factor = factor,
                    sWarp = sWarp,
                    mode = mode,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                )

                val warpedGray = Mat().tracked()
                Imgproc.cvtColor(warped, warpedGray, Imgproc.COLOR_RGBA2GRAY)
                val sourceAspect = topWidthPx / sideHeightPx
                val measuredWarpedAspect = measureWarpedReferenceAspect(
                    warpedGray,
                    referenceRect,
                ) ?: sourceAspect

                // ε_cal is the relative drift of the reference aspect after the
                // warp. If the warped edges cannot be re-measured, fall back to
                // the detected source aspect as a conservative estimate.
                val epsilon = ErrorEstimator.epsilonCal(
                    measuredWarpedAspect,
                    mode.trueAspect,
                )

                CalibrationResult(
                    mode = mode,
                    warpedBitmap = warpedBitmap,
                    warpedWidthMm = canvasWidth / pixelsPerMm,
                    warpedHeightMm = canvasHeight / pixelsPerMm,
                    pixelsPerMm = pixelsPerMm,
                    nativePixelsPerMm = nativePxPerMm,
                    epsilonCal = epsilon,
                    warpedReferenceAspect = measuredWarpedAspect,
                    tiltDeviationDeg = tilt,
                    referenceQuad = ordered,
                    referenceRectPx = referenceRect,
                    homography = h,
                )
            }
        } finally {
            if (sourceBitmap !== still && !sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // Sub-pixel corner refinement
    // ------------------------------------------------------------------

    private fun refineCorners(gray: Mat, quad: List<Pt>): List<Pt> {
        val corners = MatOfPoint2f(*quad.map { Point(it.x, it.y) }.toTypedArray())
        return try {
            val criteria = TermCriteria(
                TermCriteria.COUNT + TermCriteria.EPS,
                AppConfig.Calibration.SUBPIX_ITERATIONS,
                AppConfig.Calibration.SUBPIX_EPSILON,
            )
            Imgproc.cornerSubPix(
                gray,
                corners,
                Size(AppConfig.Calibration.SUBPIX_WIN, AppConfig.Calibration.SUBPIX_WIN),
                Size(-1.0, -1.0),
                criteria,
            )
            corners.toArray().map { Pt(it.x, it.y) }
        } catch (exception: Exception) {
            quad
        } finally {
            corners.release()
        }
    }

    // ------------------------------------------------------------------
    // Warped reference inspection
    // ------------------------------------------------------------------

    private fun referenceRect(
        xMin: Double,
        yMin: Double,
        factor: Double,
        sWarp: Double,
        mode: ReferenceMode,
        canvasWidth: Int,
        canvasHeight: Int,
    ): PixelRect {
        val left = (-xMin * factor).roundToInt().coerceIn(0, canvasWidth - 1)
        val top = (-yMin * factor).roundToInt().coerceIn(0, canvasHeight - 1)
        val right = (( -xMin + sWarp * mode.widthMm) * factor)
            .roundToInt()
            .coerceIn(left + 1, canvasWidth)
        val bottom = ((-yMin + sWarp * mode.heightMm) * factor)
            .roundToInt()
            .coerceIn(top + 1, canvasHeight)
        return PixelRect(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * Re-measure the reference's aspect ratio on the flattened canvas.
     *
     * The homography maps the reference to a rectangle by construction, so
     * the expected answer is close to [ReferenceMode.trueAspect]. Any residual
     * comes from interpolation and lens distortion; it feeds ε_cal together
     * with the pre-warp detected aspect shown below.
     */
    private fun measureWarpedReferenceAspect(warpedGray: Mat, rect: PixelRect): Double? {
        if (rect.isEmpty || warpedGray.cols() < 40 || warpedGray.rows() < 40) return null
        val edges = Mat()
        return try {
            Imgproc.Canny(warpedGray, edges, 30.0, 80.0)
            val width = edges.cols()
            val height = edges.rows()
            val pixels = ByteArray(width * height)
            edges.get(0, 0, pixels)

            val bandX = max(3, (rect.width * 0.02).toInt())
            val bandY = max(3, (rect.height * 0.02).toInt())
            val midTop = rect.top + (rect.height * 0.15).toInt()
            val midBottom = rect.bottom - (rect.height * 0.15).toInt()
            val midLeft = rect.left + (rect.width * 0.15).toInt()
            val midRight = rect.right - (rect.width * 0.15).toInt()

            val left = columnPeak(
                pixels, width, height,
                x0 = rect.left - bandX,
                x1 = rect.left + bandX,
                y0 = midTop,
                y1 = midBottom,
            )
            val right = columnPeak(
                pixels, width, height,
                x0 = rect.right - bandX,
                x1 = rect.right + bandX,
                y0 = midTop,
                y1 = midBottom,
            )
            val top = rowPeak(
                pixels, width, height,
                x0 = midLeft,
                x1 = midRight,
                y0 = rect.top - bandY,
                y1 = rect.top + bandY,
            )
            val bottom = rowPeak(
                pixels, width, height,
                x0 = midLeft,
                x1 = midRight,
                y0 = rect.bottom - bandY,
                y1 = rect.bottom + bandY,
            )
            val measuredWidth = right - left
            val measuredHeight = bottom - top
            if (measuredWidth < 8 || measuredHeight < 8) return null
            measuredWidth.toDouble() / measuredHeight
        } catch (exception: Exception) {
            null
        } finally {
            edges.release()
        }
    }

    private fun rowPeak(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): Int {
        val left = x0.coerceIn(0, width - 1)
        val right = x1.coerceIn(left + 1, width)
        val top = y0.coerceIn(0, height - 1)
        val bottom = y1.coerceIn(top + 1, height)
        var bestRow = top
        var bestCount = -1
        for (y in top until bottom) {
            val offset = y * width
            var count = 0
            for (x in left until right) {
                if (pixels[offset + x].toInt() != 0) count++
            }
            if (count > bestCount) {
                bestCount = count
                bestRow = y
            }
        }
        return bestRow
    }

    private fun columnPeak(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): Int {
        val left = x0.coerceIn(0, width - 1)
        val right = x1.coerceIn(left + 1, width)
        val top = y0.coerceIn(0, height - 1)
        val bottom = y1.coerceIn(top + 1, height)
        var bestColumn = left
        var bestCount = -1
        for (x in left until right) {
            var count = 0
            for (y in top until bottom) {
                if (pixels[y * width + x].toInt() != 0) count++
            }
            if (count > bestCount) {
                bestCount = count
                bestColumn = x
            }
        }
        return bestColumn
    }
}
