package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.HoleGeometry
import com.realmeasure.app.model.HoleSpacing
import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Extracts irregular object contours and their nested holes from the
 * flattened 1 px = 1/s mm canvas.
 *
 * Object isolation is polarity-independent: adaptive thresholds are run in
 * both directions so a light object on a dark mat and a dark object on a
 * light mat both become a white foreground blob. The reference rectangle is
 * erased first. If adaptive thresholding finds nothing, a Canny outline pass
 * is used as a low-contrast fallback.
 */
object IrregularShapeAnalyzer {

    class AnalysisException(
        val code: String,
        message: String,
    ) : Exception(message)

    /**
     * @return every valid object on the canvas, largest first. The first item
     *   is the auto-selected object.
     */
    fun analyze(warped: Bitmap, calibration: CalibrationResult): List<ObjectGeometry> =
        withMatPool {
            val rgba = Mat().tracked()
            Utils.bitmapToMat(warped, rgba)
            val gray = Mat().tracked()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val s = calibration.pixelsPerMm
            if (!s.isFinite() || s <= 0.0) {
                throw AnalysisException("BAD_SCALE", "The calibrated scale was invalid.")
            }
            val referenceRect = inflateReferenceRect(
                calibration.referenceRectPx,
                (AppConfig.Calibration.REFERENCE_MASK_MARGIN_MM * s).roundToInt(),
                gray.cols(),
                gray.rows(),
            )

            val blurred = Mat().tracked()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(
                    AppConfig.Analysis.CLOSE_KERNEL.toDouble(),
                    AppConfig.Analysis.CLOSE_KERNEL.toDouble(),
                ),
            ).tracked()

            val candidates = ArrayList<ObjectGeometry>()

            for (inverse in listOf(true, false)) {
                val mask = Mat()
                try {
                    Imgproc.adaptiveThreshold(
                        blurred,
                        mask,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        if (inverse) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY,
                        AppConfig.Analysis.BINARY_BLOCK,
                        AppConfig.Analysis.BINARY_C,
                    )
                    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                    eraseReference(mask, referenceRect)
                    candidates += extractCandidates(
                        mask = mask,
                        calibration = calibration,
                        referenceRectPx = referenceRect,
                        withHoles = true,
                    )
                } finally {
                    mask.release()
                }
            }

            if (candidates.isEmpty()) {
                val edges = Mat()
                try {
                    Imgproc.Canny(
                        blurred,
                        edges,
                        AppConfig.Analysis.CANNY_LOW,
                        AppConfig.Analysis.CANNY_HIGH,
                    )
                    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
                    eraseReference(edges, referenceRect)
                    candidates += extractCandidates(
                        mask = edges,
                        calibration = calibration,
                        referenceRectPx = referenceRect,
                        withHoles = false,
                    )
                } finally {
                    edges.release()
                }
            }

            val deduped = deduplicate(candidates)
            if (deduped.isEmpty()) {
                throw AnalysisException(
                    "NO_OBJECT",
                    "No object was found beside the reference. Use a contrasting matte surface, " +
                        "keep the object fully visible and avoid glare.",
                )
            }

            deduped
                .sortedByDescending { it.areaNetMm2 }
                .mapIndexed { index, objectGeometry -> objectGeometry.copy(index = index) }
        }

    // ------------------------------------------------------------------
    // Contour evaluation
    // ------------------------------------------------------------------

    private fun MatPool.extractCandidates(
        mask: Mat,
        calibration: CalibrationResult,
        referenceRectPx: PixelRect,
        withHoles: Boolean,
    ): List<ObjectGeometry> {
        val cfg = AppConfig.Analysis
        val s = calibration.pixelsPerMm
        val frameArea = mask.cols().toDouble() * mask.rows()
        val minAreaPx = cfg.MIN_OBJECT_AREA_MM2 * s * s
        val maxAreaPx = cfg.MAX_OBJECT_AREA_MM2 * s * s
        val minHolePx = cfg.MIN_HOLE_AREA_MM2 * s * s
        val svgEpsilonPx = cfg.SVG_EPSILON_MM * s

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().tracked()
        val results = ArrayList<ObjectGeometry>()

        try {
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                if (withHoles) Imgproc.RETR_TREE else Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            if (contours.isEmpty()) return emptyList()

            val hierarchyData = IntArray(contours.size * 4)
            hierarchy.get(0, 0, hierarchyData)

            for (index in contours.indices) {
                val parent = hierarchyData[index * 4 + 3]
                if (parent != -1) continue

                val contour = contours[index]
                val areaPx = Imgproc.contourArea(contour)
                if (areaPx < minAreaPx || areaPx > maxAreaPx) continue
                if (areaPx / frameArea > 0.90) continue

                val bounding = Imgproc.boundingRect(contour)
                if (touchesBorder(bounding, mask.cols(), mask.rows())) continue
                if (overlapsReference(bounding, referenceRectPx)) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                try {
                    val perimeterPx = Imgproc.arcLength(contour2f, true)
                    if (perimeterPx < 12.0) continue

                    val rotated = Imgproc.minAreaRect(contour2f)
                    val longPx = max(rotated.size.width, rotated.size.height)
                    val shortPx = min(rotated.size.width, rotated.size.height)
                    if (longPx < 4.0 || shortPx < 2.0) continue

                    val holes = if (withHoles) {
                        collectHoles(
                            contours = contours,
                            hierarchyData = hierarchyData,
                            parentIndex = index,
                            outerAreaPx = areaPx,
                            minHolePx = minHolePx,
                            s = s,
                            epsilonPx = svgEpsilonPx,
                            calibration = calibration,
                        )
                    } else {
                        emptyList()
                    }

                    val holeAreaPx = holes.sumOf { it.areaMm2 * s * s }
                    val netAreaPx = areaPx - holeAreaPx
                    if (netAreaPx <= 0.0) continue

                    val lengthMm = longPx / s
                    val widthMm = shortPx / s
                    val perimeterMm = perimeterPx / s
                    val netAreaMm2 = netAreaPx / (s * s)
                    if (netAreaMm2 < cfg.MIN_OBJECT_AREA_MM2) continue
                    val netAreaCm2 = netAreaMm2 / 100.0

                    val deltaL = ErrorEstimator.linearError(
                        lengthMm,
                        calibration.nativePixelsPerMm,
                        calibration.mode.widthMm,
                        calibration.epsilonCal,
                    )
                    val deltaW = ErrorEstimator.linearError(
                        widthMm,
                        calibration.nativePixelsPerMm,
                        calibration.mode.widthMm,
                        calibration.epsilonCal,
                    )
                    val deltaP = ErrorEstimator.linearError(
                        perimeterMm,
                        calibration.nativePixelsPerMm,
                        calibration.mode.widthMm,
                        calibration.epsilonCal,
                    )
                    val deltaAvg = (deltaL + deltaW) / 2.0
                    val deltaA = ErrorEstimator.areaErrorCm2(
                        perimeterMm,
                        deltaAvg,
                        netAreaMm2,
                        calibration.epsilonCal,
                    )
                    val quality = ErrorEstimator.quality(deltaL, calibration.mode)
                    val recommendation = ErrorEstimator.recommendation(
                        quality,
                        calibration.mode,
                        lengthMm,
                    )

                    val outerPtsPx = contour.toArray().map { Pt(it.x, it.y) }
                    val simplifiedPx = simplify(contour2f, svgEpsilonPx)
                    val outerMm = simplifiedPx.map { Pt(it.x / s, it.y / s) }

                    val (minPt, maxPt) = Geometry.bounds(outerPtsPx.map { Pt(it.x / s, it.y / s) })
                    val moments = Imgproc.moments(contour)
                    val centroid = if (moments.m00 > 1e-6) {
                        Pt(moments.m10 / (moments.m00 * s), moments.m01 / (moments.m00 * s))
                    } else {
                        Geometry.polygonCentroid(outerMm)
                    }

                    results += ObjectGeometry(
                        index = 0,
                        lengthMm = lengthMm,
                        widthMm = widthMm,
                        lengthErrorMm = deltaL,
                        widthErrorMm = deltaW,
                        perimeterMm = perimeterMm,
                        perimeterErrorMm = deltaP,
                        areaNetMm2 = netAreaMm2,
                        areaNetCm2 = netAreaCm2,
                        areaErrorCm2 = deltaA,
                        holes = holes,
                        holeSpacings = holeSpacings(
                            holes = holes,
                            calibration = calibration,
                        ),
                        centroid = centroid,
                        boundsMin = minPt,
                        boundsMax = maxPt,
                        outerContour = outerMm,
                        quality = quality,
                        qualityRecommendation = recommendation,
                    )
                } finally {
                    contour2f.release()
                }
            }
        } finally {
            contours.forEach { runCatching { it.release() } }
        }
        return results
    }

    private fun collectHoles(
        contours: List<MatOfPoint>,
        hierarchyData: IntArray,
        parentIndex: Int,
        outerAreaPx: Double,
        minHolePx: Double,
        s: Double,
        epsilonPx: Double,
        calibration: CalibrationResult,
    ): List<HoleGeometry> {
        val holes = ArrayList<HoleGeometry>()
        var childIndex = hierarchyData[parentIndex * 4 + 2]
        var holeNumber = 0

        while (childIndex != -1) {
            val child = contours[childIndex]
            val areaPx = Imgproc.contourArea(child)
            if (areaPx >= minHolePx && areaPx < outerAreaPx * 0.90) {
                val moments = Imgproc.moments(child)
                if (moments.m00 > 1e-6) {
                    val cx = moments.m10 / (moments.m00 * s)
                    val cy = moments.m01 / (moments.m00 * s)
                    val child2f = MatOfPoint2f(*child.toArray())
                    try {
                        val simplified = simplify(child2f, epsilonPx)
                            .map { Pt(it.x / s, it.y / s) }
                        if (simplified.size >= 3) {
                            val areaMm2 = areaPx / (s * s)
                            val diameter = 2.0 * sqrt(areaMm2 / Math.PI)
                            holes += HoleGeometry(
                                index = holeNumber++,
                                centroid = Pt(cx, cy),
                                diameterMm = diameter,
                                areaMm2 = areaMm2,
                                diameterErrorMm = ErrorEstimator.diameterError(
                                    diameter,
                                    calibration.nativePixelsPerMm,
                                    calibration.mode.widthMm,
                                    calibration.epsilonCal,
                                ),
                                contour = simplified,
                            )
                        }
                    } finally {
                        child2f.release()
                    }
                }
            }
            childIndex = hierarchyData[childIndex * 4]
        }
        return holes
    }

    private fun holeSpacings(
        holes: List<HoleGeometry>,
        calibration: CalibrationResult,
    ): List<HoleSpacing> {
        val spacings = ArrayList<HoleSpacing>()
        for (first in holes.indices) {
            for (second in first + 1 until holes.size) {
                val distance = Geometry.distance(
                    holes[first].centroid,
                    holes[second].centroid,
                )
                spacings += HoleSpacing(
                    firstIndex = holes[first].index,
                    secondIndex = holes[second].index,
                    distanceMm = distance,
                    errorMm = ErrorEstimator.distanceError(
                        distance,
                        calibration.nativePixelsPerMm,
                        calibration.mode.widthMm,
                        calibration.epsilonCal,
                    ),
                )
            }
        }
        return spacings
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun simplify(contour2f: MatOfPoint2f, epsilonPx: Double): List<Pt> {
        val approx = MatOfPoint2f()
        return try {
            Imgproc.approxPolyDP(contour2f, approx, epsilonPx, true)
            val points = approx.toArray().map { Pt(it.x, it.y) }
            if (points.size >= 3) points else contour2f.toArray().map { Pt(it.x, it.y) }
        } finally {
            approx.release()
        }
    }

    private fun inflateReferenceRect(
        rect: PixelRect,
        marginPx: Int,
        width: Int,
        height: Int,
    ): PixelRect = PixelRect(
        left = (rect.left - marginPx).coerceIn(0, width - 1),
        top = (rect.top - marginPx).coerceIn(0, height - 1),
        right = (rect.right + marginPx).coerceIn(1, width),
        bottom = (rect.bottom + marginPx).coerceIn(1, height),
    )

    private fun eraseReference(mask: Mat, rect: PixelRect) {
        if (rect.isEmpty) return
        Imgproc.rectangle(
            mask,
            Point(rect.left.toDouble(), rect.top.toDouble()),
            Point(rect.right.toDouble(), rect.bottom.toDouble()),
            Scalar(0.0),
            -1,
        )
    }

    private fun touchesBorder(rect: Rect, width: Int, height: Int): Boolean {
        val margin = AppConfig.Analysis.BORDER_MARGIN_PX
        return rect.x <= margin ||
            rect.y <= margin ||
            rect.x + rect.width >= width - margin ||
            rect.y + rect.height >= height - margin
    }

    private fun overlapsReference(rect: Rect, reference: PixelRect): Boolean {
        val centerX = rect.x + rect.width / 2.0
        val centerY = rect.y + rect.height / 2.0
        return centerX in reference.left.toDouble()..reference.right.toDouble() &&
            centerY in reference.top.toDouble()..reference.bottom.toDouble()
    }

    private fun deduplicate(candidates: List<ObjectGeometry>): List<ObjectGeometry> {
        val kept = ArrayList<ObjectGeometry>()
        for (candidate in candidates.sortedByDescending { it.areaNetMm2 }) {
            val duplicate = kept.any { existing ->
                val centroidGap = Geometry.distance(existing.centroid, candidate.centroid)
                val areaRatio = abs(existing.areaNetMm2 - candidate.areaNetMm2) /
                    max(existing.areaNetMm2, candidate.areaNetMm2)
                centroidGap < 2.0 && areaRatio < 0.25
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }
}
