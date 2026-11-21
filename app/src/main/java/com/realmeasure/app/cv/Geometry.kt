package com.realmeasure.app.cv

import com.realmeasure.app.model.Pt
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure planar geometry used by the calibrator, analyzer, SVG exporter and the
 * JVM unit tests. No Android or OpenCV imports live here.
 */
object Geometry {

    // ------------------------------------------------------------------
    // Quad ordering + tilt gate
    // ------------------------------------------------------------------

    /**
     * Order four arbitrary points clockwise starting at the top-left, using
     * the extremes of (x + y) and (y - x).
     */
    fun orderQuad(points: List<Pt>): List<Pt> {
        require(points.size == 4) { "orderQuad expected 4 points, received ${points.size}" }

        val topLeft = points.minByOrNull { it.x + it.y }!!
        val bottomRight = points.maxByOrNull { it.x + it.y }!!
        val topRight = points.minByOrNull { it.y - it.x }!!
        val bottomLeft = points.maxByOrNull { it.y - it.x }!!

        val unique = setOf(topLeft, topRight, bottomRight, bottomLeft)
        if (unique.size == 4) {
            return listOf(topLeft, topRight, bottomRight, bottomLeft)
        }

        // Degenerate sum/diff ordering (extreme perspective): sort angularly
        // around the centroid and start at the top-left-most point.
        val center = Pt(points.sumOf { it.x } / 4.0, points.sumOf { it.y } / 4.0)
        val angular = points.sortedBy { atan2(it.y - center.y, it.x - center.x) }
        val start = angular.indices.minByOrNull { angular[it].x + angular[it].y } ?: 0
        val rotated = angular.drop(start) + angular.take(start)
        return rotated
    }

    /**
     * Rotate a clockwise quad so the long physical edge is P0 -> P1. This
     * lets portrait and landscape ID cards / A4 sheets both map their long
     * side to the reference's width in millimetres.
     */
    fun orientLongEdgeFirst(quad: List<Pt>): List<Pt> {
        require(quad.size == 4)
        val horizontal = distance(quad[0], quad[1])
        val vertical = distance(quad[1], quad[2])
        return if (horizontal >= vertical) quad else listOf(quad[1], quad[2], quad[3], quad[0])
    }

    /**
     * Interior angle at each corner in degrees, following the exact
     * specification:
     *   u = P[(i+1) % 4] - P[i]
     *   v = P[(i+3) % 4] - P[i]
     *   theta = acos((u.v) / (|u||v|)) * 180/pi
     */
    fun interiorAnglesDeg(quad: List<Pt>): List<Double> {
        require(quad.size == 4)
        return quad.indices.map { index ->
            val current = quad[index]
            val next = quad[(index + 1) % 4]
            val previous = quad[(index + 3) % 4]
            val ux = next.x - current.x
            val uy = next.y - current.y
            val vx = previous.x - current.x
            val vy = previous.y - current.y
            val uLength = hypot(ux, uy)
            val vLength = hypot(vx, vy)
            if (uLength < 1e-9 || vLength < 1e-9) {
                0.0
            } else {
                val cosine = ((ux * vx + uy * vy) / (uLength * vLength)).coerceIn(-1.0, 1.0)
                Math.toDegrees(acos(cosine))
            }
        }
    }

    /** Max |theta - 90| across the four corners, in degrees. */
    fun maxTiltDeviationDeg(quad: List<Pt>): Double =
        interiorAnglesDeg(quad).maxOfOrNull { abs(it - 90.0) } ?: 180.0

    /** Average width (P0-P1 / P3-P2) and height (P0-P3 / P1-P2). */
    fun quadSides(quad: List<Pt>): Pair<Double, Double> {
        require(quad.size == 4)
        val width = (distance(quad[0], quad[1]) + distance(quad[3], quad[2])) / 2.0
        val height = (distance(quad[0], quad[3]) + distance(quad[1], quad[2])) / 2.0
        return width to height
    }

    // ------------------------------------------------------------------
    // Polygon helpers
    // ------------------------------------------------------------------

    fun distance(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

    /** Absolute shoelace area. */
    fun polygonArea(points: List<Pt>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x * next.y - next.x * current.y
        }
        return abs(sum) / 2.0
    }

    /** Polygon centroid (area-weighted); falls back to the mean for degenerates. */
    fun polygonCentroid(points: List<Pt>): Pt {
        if (points.isEmpty()) return Pt(0.0, 0.0)
        var twiceArea = 0.0
        var cx = 0.0
        var cy = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val cross = current.x * next.y - next.x * current.y
            twiceArea += cross
            cx += (current.x + next.x) * cross
            cy += (current.y + next.y) * cross
        }
        if (abs(twiceArea) < 1e-12) {
            return Pt(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
        }
        val factor = 1.0 / (3.0 * twiceArea)
        return Pt(cx * factor, cy * factor)
    }

    fun bounds(points: List<Pt>): Pair<Pt, Pt> {
        if (points.isEmpty()) return Pt(0.0, 0.0) to Pt(0.0, 0.0)
        return Pt(
            points.minOf { it.x },
            points.minOf { it.y },
        ) to Pt(
            points.maxOf { it.x },
            points.maxOf { it.y },
        )
    }

    /** Ray-crossing point-in-polygon test. */
    fun contains(polygon: List<Pt>, point: Pt): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val intersects = (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                ((previous.y - current.y).takeIf { abs(it) > 1e-12 } ?: 1e-12) + current.x
            if (intersects) inside = !inside
            previous = current
        }
        return inside
    }

    // ------------------------------------------------------------------
    // Homography helpers (row-major 3x3)
    // ------------------------------------------------------------------

    fun translation(dx: Double, dy: Double): DoubleArray = doubleArrayOf(
        1.0, 0.0, dx,
        0.0, 1.0, dy,
        0.0, 0.0, 1.0,
    )

    fun scaling(factor: Double): DoubleArray = doubleArrayOf(
        factor, 0.0, 0.0,
        0.0, factor, 0.0,
        0.0, 0.0, 1.0,
    )

    /** Row-major 3x3 multiplication: returns a * b. */
    fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == 9 && b.size == 9)
        val result = DoubleArray(9)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) {
                    sum += a[row * 3 + k] * b[k * 3 + column]
                }
                result[row * 3 + column] = sum
            }
        }
        return result
    }

    fun applyH(h: DoubleArray, p: Pt): Pt {
        val denominator = h[6] * p.x + h[7] * p.y + h[8]
        if (abs(denominator) < 1e-12) return Pt(Double.NaN, Double.NaN)
        return Pt(
            x = (h[0] * p.x + h[1] * p.y + h[2]) / denominator,
            y = (h[3] * p.x + h[4] * p.y + h[5]) / denominator,
        )
    }

    fun isFinite(value: Double): Boolean = value.isFinite()

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun clamp(value: Double, minimum: Double, maximum: Double): Double =
        max(minimum, min(maximum, value))
}
