package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.BinarisationStage
import com.realmeasure.app.model.CaliperHandles
import com.realmeasure.app.model.CalibrationResult
import com.realmeasure.app.model.FastenerMeasurement
import com.realmeasure.app.model.NormPt
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.RawPixelMeasurement
import com.realmeasure.app.model.ScanMode
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.lang.Math.toDegrees
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fastener measurement.
 *
 * The cyan scan zone is projected through the card homography into a metric
 * plane, then warped with OpenCV so one pixel equals a known fraction of a
 * millimetre. Contours are found in three binarisations (adaptive dark,
 * adaptive light, Canny), and the best object is reduced to a min-area
 * rectangle. A longitudinal width profile separates the head/short leg from
 * the shaft, yielding diameter and length in millimetres.
 */
object FastenerAnalyzer {

    /** Typed failure carrying a user-facing hint. */
    class MeasurementException(val code: String, message: String, val hint: String) :
        Exception(message)

    /** Measure a fastener in the right-hand scan zone. */
    fun analyze(
        bitmap: Bitmap,
        calibration: CalibrationResult,
        mode: ScanMode,
    ): FastenerMeasurement = withMatPool {
        val source = Mat().tracked()
        Utils.bitmapToMat(bitmap, source)

        // ---- Crop the target zone + margin ------------------------------
        val guides = AppConfig.Guides
        val padX = guides.TARGET_W * guides.TARGET_SEARCH_PADDING
        val padY = guides.TARGET_H * guides.TARGET_SEARCH_PADDING
        val left = floor(max(0.0, (guides.TARGET_X - padX) * source.cols())).toInt()
        val top = floor(max(0.0, (guides.TARGET_Y - padY) * source.rows())).toInt()
        val right = ceil(min(1.0, guides.TARGET_X + guides.TARGET_W + padX) * source.cols()).toInt()
        val bottom = ceil(min(1.0, guides.TARGET_Y + guides.TARGET_H + padY) * source.rows()).toInt()
        val cropRect = Rect(left, top, max(8, right - left), max(8, bottom - top))
        val crop = crop(source, cropRect)

        // ---- Project the crop onto the metric card plane ----------------
        val localCorners = listOf(
            Pt(0.0, 0.0),
            Pt(cropRect.width.toDouble(), 0.0),
            Pt(cropRect.width.toDouble(), cropRect.height.toDouble()),
            Pt(0.0, cropRect.height.toDouble()),
        )
        val metricCorners = localCorners.map {
            Geometry.applyH(calibration.homography, Pt(it.x + cropRect.x, it.y + cropRect.y))
        }
        if (metricCorners.any { it.x.isNaN() || it.y.isNaN() }) {
            throw MeasurementException(
                "BAD_SCALE",
                "The scan zone could not be projected onto the card plane.",
                "Keep the fastener and card flat, then capture again.",
            )
        }

        val cv = AppConfig.FastenerCv
        val minX = metricCorners.minOf { it.x }
        val maxX = metricCorners.maxOf { it.x }
        val minY = metricCorners.minOf { it.y }
        val maxY = metricCorners.maxOf { it.y }
        val marginMm = 2.5
        val spanX = max(8.0, maxX - minX + marginMm * 2.0)
        val spanY = max(8.0, maxY - minY + marginMm * 2.0)
        val pxPerMm = max(
            4.0,
            minOf(cv.RECTIFIED_PX_PER_MM, cv.MAX_RECTIFIED_PX / spanX, cv.MAX_RECTIFIED_PX / spanY),
        )
        val destinationWidth = max(32, (spanX * pxPerMm).roundToInt())
        val destinationHeight = max(32, (spanY * pxPerMm).roundToInt())
        val destinationCorners = metricCorners.map {
            Pt(
                (it.x - minX + marginMm) * pxPerMm,
                (it.y - minY + marginMm) * pxPerMm,
            )
        }

        // ---- Warp into the metric crop ----------------------------------
        val src2f = MatOfPoint2f(*localCorners.map { it.toPoint() }.toTypedArray()).tracked()
        val dst2f = MatOfPoint2f(*destinationCorners.map { it.toPoint() }.toTypedArray()).tracked()
        val transformMat = Imgproc.getPerspectiveTransform(src2f, dst2f)

        val rectified = Mat().tracked()
        Imgproc.warpPerspective(
            crop, rectified, transformMat,
            Size(destinationWidth.toDouble(), destinationHeight.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT,
            Scalar(16.0, 13.0, 11.0, 255.0),
        )

        val localHomography = DoubleArray(9)
        transformMat.get(0, 0, localHomography)
        transformMat.release()

        // ---- Binarise ----------------------------------------------------
        val gray = Mat().tracked()
        Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat().tracked()
        Imgproc.GaussianBlur(
            gray, blurred,
            Size(cv.GAUSSIAN_KERNEL.toDouble(), cv.GAUSSIAN_KERNEL.toDouble()),
            0.0,
        )
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(cv.MORPH_KERNEL.toDouble(), cv.MORPH_KERNEL.toDouble()),
        ).tracked()

        val masks = ArrayList<MaskSource>(3)
        for (thresholdType in listOf(Imgproc.THRESH_BINARY_INV, Imgproc.THRESH_BINARY)) {
            val rawMask = Mat().tracked()
            Imgproc.adaptiveThreshold(
                blurred, rawMask, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, thresholdType,
                cv.ADAPTIVE_BLOCK, cv.ADAPTIVE_C,
            )
            val closed = Mat().tracked()
            Imgproc.morphologyEx(rawMask, closed, Imgproc.MORPH_CLOSE, kernel)
            masks.add(MaskSource(closed, BinarisationStage.ADAPTIVE))
        }
        val edges = Mat().tracked()
        Imgproc.Canny(blurred, edges, cv.CANNY_LOW, cv.CANNY_HIGH)
        val closedEdges = Mat().tracked()
        Imgproc.morphologyEx(edges, closedEdges, Imgproc.MORPH_CLOSE, kernel)
        masks.add(MaskSource(closedEdges, BinarisationStage.CANNY))

        // ---- Best contour --------------------------------------------------
        val best = chooseContour(masks, destinationWidth, destinationHeight, pxPerMm, mode)
            ?: throw MeasurementException(
                "NO_CONTOUR",
                "No fastener contour was found in the cyan scan zone.",
                "Use a plain background, keep the whole part inside the zone, and avoid hard shadows.",
            )

        val points = extractContourPoints(masks[best.maskIndex].mat, best.areaPx)
        if (points.size < 8) {
            throw MeasurementException(
                "NO_CONTOUR",
                "The detected contour is too small to measure.",
                "Move the phone closer and fill more of the cyan zone with the fastener.",
            )
        }

        // ---- Longitudinal width profile -----------------------------------
        val profile = analyzeProfile(points, mode)

        val shaftDiameterMm = profile.shaftWidthPx / pxPerMm
        val overallLengthMm = profile.overallLengthPx / pxPerMm
        val lengthMm = if (mode == ScanMode.HEX_KEY) {
            overallLengthMm
        } else {
            profile.shaftLengthPx / pxPerMm
        }
        val headWidthMm = profile.headWidthPx / pxPerMm

        val minLengthMm = if (mode == ScanMode.HEX_KEY) {
            max(10.0, cv.MIN_LENGTH_MM)
        } else {
            cv.MIN_LENGTH_MM
        }
        if (shaftDiameterMm.isNaN() || lengthMm.isNaN() ||
            shaftDiameterMm < cv.MIN_DIAMETER_MM || shaftDiameterMm > cv.MAX_DIAMETER_MM ||
            lengthMm < minLengthMm || lengthMm > cv.MAX_LENGTH_MM
        ) {
            throw MeasurementException(
                "BAD_PROFILE",
                "The measured size is outside the supported fastener range.",
                "Supported range: M1.6-M16 screws and 1.5-10 mm hex keys. Check the calibration card and try again.",
            )
        }

        // ---- Map caliper handles back to the source bitmap ----------------
        val inverseLocal = try {
            Geometry.invert3x3(localHomography)
        } catch (exception: IllegalArgumentException) {
            throw MeasurementException(
                "BAD_SCALE",
                "The perspective transform could not be inverted for fine-tuning.",
                "Capture again with less tilt between the phone and the table.",
            )
        }

        fun toNorm(p: Pt): NormPt {
            val local = Geometry.applyH(inverseLocal, p)
            return NormPt(
                x = ((local.x + cropRect.x) / max(source.cols(), 1)).coerceIn(0.0, 1.0),
                y = ((local.y + cropRect.y) / max(source.rows(), 1)).coerceIn(0.0, 1.0),
            )
        }

        val handles = CaliperHandles(
            widthA = toNorm(profile.widthLineA),
            widthB = toNorm(profile.widthLineB),
            lengthA = toNorm(profile.lengthLineA),
            lengthB = toNorm(profile.lengthLineB),
        )

        val headBonus = if (profile.headSide == HeadSide.NONE) 0.7 else 1.0
        val confidence = (
            0.55 * best.score + 0.30 * profile.confidence + 0.15 * headBonus
            ).coerceIn(0.0, 1.0)

        FastenerMeasurement(
            mode = mode,
            detected = true,
            shaftDiameterMm = shaftDiameterMm,
            lengthMm = lengthMm,
            headWidthMm = headWidthMm,
            overallLengthMm = overallLengthMm,
            confidence = confidence,
            raw = RawPixelMeasurement(
                shaftWidthPx = profile.shaftWidthPx,
                shaftLengthPx = profile.shaftLengthPx,
                overallLengthPx = profile.overallLengthPx,
                headWidthPx = profile.headWidthPx,
                headLengthPx = profile.headLengthPx,
                angleDeg = toDegrees(profile.angleRad),
                pixelsPerMm = pxPerMm,
                contourAreaPx = best.areaPx,
                stage = best.stage,
            ),
            handles = handles,
        )
    }

    // ------------------------------------------------------------------
    // Contour selection
    // ------------------------------------------------------------------

    private data class MaskSource(val mat: Mat, val stage: BinarisationStage)

    private data class ContourCandidate(
        val maskIndex: Int,
        val stage: BinarisationStage,
        val score: Double,
        val areaPx: Double,
    )

    /** Score every external contour in each binarisation. */
    private fun chooseContour(
        masks: List<MaskSource>,
        width: Int,
        height: Int,
        pxPerMm: Double,
        mode: ScanMode,
    ): ContourCandidate? {
        val cv = AppConfig.FastenerCv
        val minLengthMm = if (mode == ScanMode.HEX_KEY) 10.0 else cv.MIN_LENGTH_MM
        val frameCenter = Pt(width / 2.0, height / 2.0)
        val halfDiagonal = hypot(width.toDouble(), height.toDouble()) / 2.0

        var best: ContourCandidate? = null

        masks.forEachIndexed { maskIndex, maskSource ->
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(
                    maskSource.mat, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
                )

                for (contour in contours) {
                    val areaPx = Imgproc.contourArea(contour)
                    if (areaPx / (pxPerMm * pxPerMm) < cv.MIN_AREA_MM2) continue

                    val rect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
                    val rectWidthPx = max(rect.size.width, rect.size.height)
                    val rectHeightPx = min(rect.size.width, rect.size.height)
                    if (rectWidthPx < 4.0 || rectHeightPx < 2.0) continue

                    val diameterMm = rectHeightPx / pxPerMm
                    val lengthMm = rectWidthPx / pxPerMm
                    if (diameterMm < cv.MIN_DIAMETER_MM || diameterMm > cv.MAX_DIAMETER_MM ||
                        lengthMm < minLengthMm || lengthMm > cv.MAX_LENGTH_MM
                    ) {
                        continue
                    }

                    val fill = (areaPx / (rectWidthPx * rectHeightPx)).coerceIn(0.0, 1.0)
                    if (fill < 0.16) continue

                    val center = Pt(rect.center.x, rect.center.y)
                    val areaScore = (areaPx / (width * height * 0.06)).coerceIn(0.0, 1.0)
                    val centerScore =
                        1.0 - (Geometry.distance(center, frameCenter) / halfDiagonal).coerceIn(0.0, 1.0)
                    val fillScore = fill
                    val elongationScore = (rectWidthPx / max(rectHeightPx, 1.0) / 4.0).coerceIn(0.0, 1.0)
                    val score = cv.WEIGHT_AREA * areaScore +
                        cv.WEIGHT_ELONGATION * elongationScore +
                        cv.WEIGHT_CENTER * centerScore +
                        cv.WEIGHT_EDGE_SHARPNESS * fillScore

                    if (best == null || score > best!!.score) {
                        best = ContourCandidate(maskIndex, maskSource.stage, score, areaPx)
                    }
                }
            } finally {
                contours.forEach { mat -> runCatching { mat.release() } }
                hierarchy.release()
            }
        }
        return best
    }

    /**
     * Re-run findContours with dense points on the winning mask and pick the
     * contour whose polygon area best matches the scored candidate. This
     * avoids depending on OpenCV vector ordering across platforms.
     */
    private fun extractContourPoints(mask: Mat, expectedAreaPx: Double): List<Pt> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE,
            )

            var bestPoints = emptyList<Pt>()
            var bestError = Double.POSITIVE_INFINITY
            for (contour in contours) {
                val points = contour.toArray().map { it.toPt() }
                if (points.size < 4) continue
                val area = Geometry.polygonArea(points)
                val error = abs(area - expectedAreaPx)
                if (error < bestError) {
                    bestError = error
                    bestPoints = points
                }
            }
            return bestPoints
        } finally {
            contours.forEach { mat -> runCatching { mat.release() } }
            hierarchy.release()
        }
    }

    // ------------------------------------------------------------------
    // Longitudinal width profile
    // ------------------------------------------------------------------

    private enum class HeadSide { START, END, NONE }

    private data class ProfileAnalysis(
        val angleRad: Double,
        val shaftWidthPx: Double,
        val shaftLengthPx: Double,
        val overallLengthPx: Double,
        val headWidthPx: Double,
        val headLengthPx: Double,
        val headSide: HeadSide,
        val widthLineA: Pt,
        val widthLineB: Pt,
        val lengthLineA: Pt,
        val lengthLineB: Pt,
        val confidence: Double,
    )

    private data class Projections(val t: List<Double>, val s: List<Double>)

    private data class Run(val start: Int, val end: Int, val maxWidth: Double)

    /** Build a PCA-aligned longitudinal width profile and split head from shaft. */
    private fun analyzeProfile(points: List<Pt>, mode: ScanMode): ProfileAnalysis {
        val cv = AppConfig.FastenerCv
        val origin = Pt(
            points.map { it.x }.average(),
            points.map { it.y }.average(),
        )

        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (p in points) {
            val dx = p.x - origin.x
            val dy = p.y - origin.y
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }

        var angle = 0.5 * atan2(2.0 * xy, xx - yy)
        var (major, minor) = Geometry.axisVectors(angle)
        var projections = projectPoints(points, origin, major, minor)
        if (range(projections.s) > range(projections.t)) {
            angle += PI / 2.0
            val vectors = Geometry.axisVectors(angle)
            major = vectors.first
            minor = vectors.second
            projections = projectPoints(points, origin, major, minor)
        }

        val tMin = projections.t.min()
        val tMax = projections.t.max()
        val sMin = projections.s.min()
        val sMax = projections.s.max()
        val overallLengthPx = tMax - tMin
        if (overallLengthPx < 6.0 || sMax - sMin < 3.0) {
            throw MeasurementException(
                "BAD_PROFILE",
                "The fastener outline is too thin to profile.",
                "Move closer and make sure the fastener is not blurred.",
            )
        }

        val binCount = cv.PROFILE_BINS.coerceIn(24, 120)
        val binWidthPx = overallLengthPx / binCount
        val minSByBin = DoubleArray(binCount) { Double.POSITIVE_INFINITY }
        val maxSByBin = DoubleArray(binCount) { Double.NEGATIVE_INFINITY }
        for (index in points.indices) {
            val t = projections.t[index]
            val s = projections.s[index]
            val binIndex = (((t - tMin) / overallLengthPx) * (binCount - 1))
                .toInt()
                .coerceIn(0, binCount - 1)
            if (s < minSByBin[binIndex]) minSByBin[binIndex] = s
            if (s > maxSByBin[binIndex]) maxSByBin[binIndex] = s
        }

        val rawWidths = DoubleArray(binCount) { index ->
            val lo = minSByBin[index]
            val hi = maxSByBin[index]
            if (lo.isFinite() && hi.isFinite()) hi - lo else Double.NaN
        }
        val filled = fillGaps(rawWidths)
        val smoothed = movingAverage(filled, 3)
        val validWidths = smoothed.filter { it.isFinite() && it > 0.0 }
        if (validWidths.size < 4) {
            throw MeasurementException(
                "BAD_PROFILE",
                "The fastener width profile is incomplete.",
                "Keep the whole fastener inside the cyan zone and avoid cropping the head.",
            )
        }

        val baseWidth = Geometry.percentile(validWidths, cv.SHAFT_WIDTH_PERCENTILE)
        val headThreshold = baseWidth * cv.HEAD_WIDTH_RATIO
        val runs = findRuns(smoothed, headThreshold)
        val headRun = chooseHeadRun(runs, binCount, cv.MAX_HEAD_LENGTH_FRACTION)

        var headSide = HeadSide.NONE
        var headWidthPx = validWidths.max()
        var headLengthPx = 0.0
        var shaftStartBin = 0
        var shaftEndBin = binCount - 1

        if (headRun != null) {
            val runFraction = (headRun.end - headRun.start + 1.0) / binCount
            val touchesStart = headRun.start <= 2
            val touchesEnd = headRun.end >= binCount - 3
            if (runFraction <= cv.MAX_HEAD_LENGTH_FRACTION && runFraction >= 0.015 &&
                touchesStart != touchesEnd
            ) {
                headSide = if (touchesStart) HeadSide.START else HeadSide.END
                headWidthPx = smoothed
                    .slice(headRun.start..headRun.end)
                    .filter { it.isFinite() }
                    .max()
                headLengthPx = (headRun.end - headRun.start + 1) * binWidthPx
                shaftStartBin = if (touchesStart) headRun.end + 1 else 0
                shaftEndBin = if (touchesEnd) headRun.start - 1 else binCount - 1
            }
        }

        var shaftWidths = smoothed
            .slice(shaftStartBin..shaftEndBin)
            .filter { it.isFinite() && it > 0.0 }
        if (shaftWidths.size < 3) {
            headSide = HeadSide.NONE
            shaftStartBin = 0
            shaftEndBin = binCount - 1
            shaftWidths = validWidths
        }

        val shaftWidthPx = Geometry.median(shaftWidths)
        if (shaftWidthPx.isNaN() || shaftWidthPx <= 0.0) {
            throw MeasurementException(
                "BAD_PROFILE",
                "The shaft width could not be isolated.",
                "Place the fastener so the threaded shaft is fully visible.",
            )
        }

        val shaftLengthPx = if (mode == ScanMode.SCREW_BOLT && headSide != HeadSide.NONE) {
            (shaftEndBin - shaftStartBin + 1) * binWidthPx
        } else {
            overallLengthPx
        }

        val widthBin = ((shaftStartBin + shaftEndBin) / 2.0).toInt()
            .coerceIn(shaftStartBin, shaftEndBin)
        val tAtWidth = tMin + (widthBin + 0.5) * binWidthPx
        val widthMinS = if (minSByBin[widthBin].isFinite()) minSByBin[widthBin] else sMin
        val widthMaxS = if (maxSByBin[widthBin].isFinite()) maxSByBin[widthBin] else sMax

        val centers = ArrayList<Double>()
        for (bin in shaftStartBin..shaftEndBin) {
            val lo = minSByBin[bin]
            val hi = maxSByBin[bin]
            if (lo.isFinite() && hi.isFinite()) centers.add((lo + hi) / 2.0)
        }
        val shaftCenterS = if (centers.isNotEmpty()) Geometry.median(centers) else 0.0

        val tStart = if (mode == ScanMode.SCREW_BOLT && headSide != HeadSide.NONE) {
            tMin + shaftStartBin * binWidthPx
        } else {
            tMin
        }
        val tEnd = if (mode == ScanMode.SCREW_BOLT && headSide != HeadSide.NONE) {
            tMin + (shaftEndBin + 1) * binWidthPx
        } else {
            tMax
        }

        fun pointAt(t: Double, s: Double): Pt = Pt(
            x = origin.x + major.x * t + minor.x * s,
            y = origin.y + major.y * t + minor.y * s,
        )

        val deviations = shaftWidths.map { abs(it - shaftWidthPx) }
        val mad = Geometry.median(deviations)
        val profileConfidence = 1.0 - (mad / max(shaftWidthPx * 0.18, 0.5)).coerceIn(0.0, 1.0)

        return ProfileAnalysis(
            angleRad = angle,
            shaftWidthPx = shaftWidthPx,
            shaftLengthPx = shaftLengthPx,
            overallLengthPx = overallLengthPx,
            headWidthPx = headWidthPx,
            headLengthPx = headLengthPx,
            headSide = headSide,
            widthLineA = pointAt(tAtWidth, widthMinS),
            widthLineB = pointAt(tAtWidth, widthMaxS),
            lengthLineA = pointAt(tStart, shaftCenterS),
            lengthLineB = pointAt(tEnd, shaftCenterS),
            confidence = profileConfidence,
        )
    }

    private fun projectPoints(
        points: List<Pt>,
        origin: Pt,
        major: Pt,
        minor: Pt,
    ): Projections {
        val t = ArrayList<Double>(points.size)
        val s = ArrayList<Double>(points.size)
        for (p in points) {
            val dx = p.x - origin.x
            val dy = p.y - origin.y
            t.add(dx * major.x + dy * major.y)
            s.add(dx * minor.x + dy * minor.y)
        }
        return Projections(t, s)
    }

    private fun range(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.max() - values.min()
    }

    /** Fill empty bins with the nearest valid neighbour, then the global median. */
    private fun fillGaps(values: DoubleArray): DoubleArray {
        val result = values.copyOf()
        val finite = result.filter { it.isFinite() }
        val fallback = if (finite.isNotEmpty()) Geometry.median(finite) else 0.0

        var last = Double.NaN
        for (index in result.indices) {
            val value = result[index]
            if (value.isFinite()) {
                last = value
            } else if (last.isFinite()) {
                result[index] = last
            }
        }
        var next = Double.NaN
        for (index in result.indices.reversed()) {
            val value = result[index]
            if (value.isFinite()) {
                next = value
            } else if (next.isFinite()) {
                result[index] = next
            }
        }
        for (index in result.indices) {
            if (!result[index].isFinite()) result[index] = fallback
        }
        return result
    }

    private fun movingAverage(values: DoubleArray, window: Int): DoubleArray {
        val radius = max(0, window / 2)
        return DoubleArray(values.size) { index ->
            var sum = 0.0
            var count = 0
            for (offset in -radius..radius) {
                val position = index + offset
                if (position in values.indices && values[position].isFinite()) {
                    sum += values[position]
                    count += 1
                }
            }
            if (count > 0) sum / count else 0.0
        }
    }

    private fun findRuns(values: DoubleArray, threshold: Double): List<Run> {
        val runs = ArrayList<Run>()
        var start = -1
        var maxWidth = 0.0
        values.forEachIndexed { index, value ->
            if (value > threshold) {
                if (start < 0) {
                    start = index
                    maxWidth = value
                } else if (value > maxWidth) {
                    maxWidth = value
                }
            } else if (start >= 0) {
                runs.add(Run(start, index - 1, maxWidth))
                start = -1
                maxWidth = 0.0
            }
        }
        if (start >= 0) runs.add(Run(start, values.size - 1, maxWidth))
        return runs
    }

    private fun chooseHeadRun(runs: List<Run>, binCount: Int, maxFraction: Double): Run? {
        val endRuns = runs.filter { run ->
            (run.start <= 2 || run.end >= binCount - 3) &&
                (run.end - run.start + 1.0) <= binCount * maxFraction
        }
        return endRuns.maxByOrNull { it.maxWidth }
    }
}
