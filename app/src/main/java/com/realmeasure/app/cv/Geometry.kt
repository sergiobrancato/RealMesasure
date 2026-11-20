package com.realmeasure.app.cv

import com.realmeasure.app.model.CaliperHandles
import com.realmeasure.app.model.CardQuad
import com.realmeasure.app.model.NormPt
import com.realmeasure.app.model.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure geometry and homography math shared by the calibrator, the analyzer
 * and the interactive caliper. No Android/ OpenCV imports - everything here
 * is JVM-unit-testable.
 */
object Geometry {

    // ------------------------------------------------------------------
    // Quad helpers
    // ------------------------------------------------------------------

    /** Order four arbitrary points as TL -> TR -> BR -> BL. */
    fun orderQuad(points: List<Pt>): CardQuad {
        require(points.size == 4) { "orderQuad expected 4 points, received ${points.size}" }

        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.x - it.y }

        fun indexOfExtreme(values: List<Double>, max: Boolean): Int {
            var best = 0
            for (i in 1 until values.size) {
                val current = values[i]
                val previous = values[best]
                val better = if (max) current > previous else current < previous
                if (better) best = i
            }
            return best
        }

        val topLeft = points[indexOfExtreme(sums, max = false)]
        val bottomRight = points[indexOfExtreme(sums, max = true)]
        val topRight = points[indexOfExtreme(diffs, max = true)]
        val bottomLeft = points[indexOfExtreme(diffs, max = false)]

        val unique = setOf(
            "${topLeft.x},${topLeft.y}",
            "${topRight.x},${topRight.y}",
            "${bottomRight.x},${bottomRight.y}",
            "${bottomLeft.x},${bottomLeft.y}",
        )
        if (unique.size == 4) {
            return CardQuad(topLeft, topRight, bottomRight, bottomLeft)
        }

        // Degenerate sum/diff ordering (extreme perspective): fall back to an
        // angular sort around the centroid.
        val center = points.reduce { acc, p -> Pt(acc.x + p.x / 4.0, acc.y + p.y / 4.0) }
        val angular = points.sortedBy { atan2(it.y - center.y, it.x - center.x) }
        var startIndex = 0
        var bestSum = Double.POSITIVE_INFINITY
        angular.forEachIndexed { index, p ->
            val sum = p.x + p.y
            if (sum < bestSum) {
                bestSum = sum
                startIndex = index
            }
        }
        val rotated = angular.drop(startIndex) + angular.take(startIndex)
        return CardQuad(rotated[0], rotated[1], rotated[2], rotated[3])
    }

    fun quadPoints(quad: CardQuad): List<Pt> =
        listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)

    /** Average width/height of the quad in pixels. */
    fun quadSides(quad: CardQuad): Pair<Double, Double> {
        val width = (distance(quad.topLeft, quad.topRight) + distance(quad.bottomLeft, quad.bottomRight)) / 2.0
        val height = (distance(quad.topLeft, quad.bottomLeft) + distance(quad.topRight, quad.bottomRight)) / 2.0
        return width to height
    }

    fun polygonArea(points: List<Pt>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val current = points[i]
            val next = points[(i + 1) % points.size]
            sum += current.x * next.y - next.x * current.y
        }
        return abs(sum) / 2.0
    }

    fun distance(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

    // ------------------------------------------------------------------
    // Homography math (row-major 3x3)
    // ------------------------------------------------------------------

    /** Apply a 3x3 homography to a point. */
    fun applyH(h: DoubleArray, p: Pt): Pt {
        val denominator = h[6] * p.x + h[7] * p.y + h[8]
        if (abs(denominator) < 1e-12) return Pt(Double.NaN, Double.NaN)
        return Pt(
            x = (h[0] * p.x + h[1] * p.y + h[2]) / denominator,
            y = (h[3] * p.x + h[4] * p.y + h[5]) / denominator,
        )
    }

    /** Invert a 3x3 homography. */
    fun invert3x3(h: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(h[0], h[1], h[2])
        val (d, e, f) = Triple(h[3], h[4], h[5])
        val (g, i, j) = Triple(h[6], h[7], h[8])
        val determinant = a * (e * j - f * i) - b * (d * j - f * g) + c * (d * i - e * g)
        require(abs(determinant) > 1e-12) { "Homography is not invertible" }
        val inverse = 1.0 / determinant
        return doubleArrayOf(
            (e * j - f * i) * inverse,
            (c * i - b * j) * inverse,
            (b * f - c * e) * inverse,
            (f * g - d * j) * inverse,
            (a * j - c * g) * inverse,
            (c * d - a * f) * inverse,
            (d * i - e * g) * inverse,
            (b * g - a * i) * inverse,
            (a * e - b * d) * inverse,
        )
    }

    /** Mean corner reprojection error for [src] -> [dst] under [h]. */
    fun reprojectionError(h: DoubleArray, src: List<Pt>, dst: List<Pt>): Double {
        if (src.isEmpty()) return Double.POSITIVE_INFINITY
        var total = 0.0
        for (index in src.indices) {
            val mapped = applyH(h, src[index])
            total += distance(mapped, dst[index])
        }
        return total / src.size
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /** Linear-interpolated percentile where p is in [0, 1]. */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = kotlin.math.floor(position).toInt()
        val upper = kotlin.math.ceil(position).toInt()
        val lo = sorted[lower]
        val hi = sorted[upper]
        return lo + (hi - lo) * (position - lower)
    }

    // ------------------------------------------------------------------
    // Caliper handles
    // ------------------------------------------------------------------

    private fun normToPx(p: NormPt, imageW: Int, imageH: Int): Pt = Pt(p.x * imageW, p.y * imageH)

    private fun pxToNorm(p: Pt, imageW: Int, imageH: Int): NormPt =
        NormPt(p.x / imageW, p.y / imageH)

    private fun clampNorm(p: NormPt): NormPt =
        NormPt(p.x.coerceIn(0.0, 1.0), p.y.coerceIn(0.0, 1.0))

    /**
     * Millimetre distances represented by the four caliper handles. Handles
     * are transformed through the card homography first, so perspective tilt
     * is removed before the Euclidean distance is measured.
     *
     * @return (widthMm, lengthMm)
     */
    fun caliperValues(
        handles: CaliperHandles,
        homography: DoubleArray,
        imageW: Int,
        imageH: Int,
    ): Pair<Double, Double> {
        val widthA = applyH(homography, normToPx(handles.widthA, imageW, imageH))
        val widthB = applyH(homography, normToPx(handles.widthB, imageW, imageH))
        val lengthA = applyH(homography, normToPx(handles.lengthA, imageW, imageH))
        val lengthB = applyH(homography, normToPx(handles.lengthB, imageW, imageH))
        return distance(widthA, widthB) to distance(lengthA, lengthB)
    }

    /**
     * Move the two width handles symmetrically by [deltaMm] on the metric card
     * plane. Keeping ±0.1 mm nudges physically meaningful even with tilt.
     */
    fun nudgeWidth(
        handles: CaliperHandles,
        deltaMm: Double,
        homography: DoubleArray,
        inverseHomography: DoubleArray,
        imageW: Int,
        imageH: Int,
        minMm: Double,
    ): CaliperHandles = nudgePair(
        handles, handles.widthA, handles.widthB, deltaMm,
        homography, inverseHomography, imageW, imageH, minMm,
    ) { current, a, b -> current.copy(widthA = a, widthB = b) }

    /** Move the two length handles symmetrically by [deltaMm]. */
    fun nudgeLength(
        handles: CaliperHandles,
        deltaMm: Double,
        homography: DoubleArray,
        inverseHomography: DoubleArray,
        imageW: Int,
        imageH: Int,
        minMm: Double,
    ): CaliperHandles = nudgePair(
        handles, handles.lengthA, handles.lengthB, deltaMm,
        homography, inverseHomography, imageW, imageH, minMm,
    ) { current, a, b -> current.copy(lengthA = a, lengthB = b) }

    private inline fun nudgePair(
        handles: CaliperHandles,
        first: NormPt,
        second: NormPt,
        deltaMm: Double,
        homography: DoubleArray,
        inverseHomography: DoubleArray,
        imageW: Int,
        imageH: Int,
        minMm: Double,
        rebuild: (CaliperHandles, NormPt, NormPt) -> CaliperHandles,
    ): CaliperHandles {
        val a = applyH(homography, normToPx(first, imageW, imageH))
        val b = applyH(homography, normToPx(second, imageW, imageH))
        val lengthMm = distance(a, b)
        if (lengthMm < 1e-6) return handles

        val targetLength = maxOf(minMm, lengthMm + deltaMm)
        val center = Pt((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
        val direction = Pt((b.x - a.x) / lengthMm, (b.y - a.y) / lengthMm)

        val nextA = Pt(
            center.x - direction.x * targetLength / 2.0,
            center.y - direction.y * targetLength / 2.0,
        )
        val nextB = Pt(
            center.x + direction.x * targetLength / 2.0,
            center.y + direction.y * targetLength / 2.0,
        )

        val normA = clampNorm(pxToNorm(applyH(inverseHomography, nextA), imageW, imageH))
        val normB = clampNorm(pxToNorm(applyH(inverseHomography, nextB), imageW, imageH))
        return rebuild(handles, normA, normB)
    }

    /** Unit major/minor vectors for an axis angle (radians). */
    fun axisVectors(angleRad: Double): Pair<Pt, Pt> {
        val major = Pt(cos(angleRad), sin(angleRad))
        val minor = Pt(-major.y, major.x)
        return major to minor
    }
}
