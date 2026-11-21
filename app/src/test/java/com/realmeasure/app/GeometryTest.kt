package com.realmeasure.app

import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure planar geometry tests: ordering, tilt gate, homography composition. */
class GeometryTest {

    private val rectangle = listOf(
        Pt(10.0, 20.0),
        Pt(110.0, 20.0),
        Pt(110.0, 70.0),
        Pt(10.0, 70.0),
    )

    @Test
    fun `order quad starts at top left and runs clockwise`() {
        val shuffled = listOf(rectangle[2], rectangle[0], rectangle[3], rectangle[1])
        val ordered = Geometry.orderQuad(shuffled)
        assertEquals(rectangle.map { it.x to it.y }, ordered.map { it.x to it.y })
    }

    @Test
    fun `interior angles of a rectangle are ninety degrees`() {
        val angles = Geometry.interiorAnglesDeg(rectangle)
        angles.forEach { angle -> assertEquals(90.0, angle, 1e-6) }
        assertEquals(0.0, Geometry.maxTiltDeviationDeg(rectangle), 1e-6)
    }

    @Test
    fun `perspective skew is detected by the tilt gate`() {
        val skewed = listOf(
            Pt(10.0, 20.0),
            Pt(110.0, 5.0),
            Pt(110.0, 70.0),
            Pt(10.0, 70.0),
        )
        assertTrue(Geometry.maxTiltDeviationDeg(skewed) > 4.0)
    }

    @Test
    fun `portrait quad is rotated so the long edge is first`() {
        val portrait = listOf(
            Pt(10.0, 20.0),
            Pt(40.0, 20.0),
            Pt(40.0, 120.0),
            Pt(10.0, 120.0),
        )
        val oriented = Geometry.orientLongEdgeFirst(portrait)
        assertTrue(Geometry.distance(oriented[0], oriented[1]) > 90.0)
        assertTrue(Geometry.distance(oriented[0], oriented[1]) >
            Geometry.distance(oriented[1], oriented[2]))
    }

    @Test
    fun `point in polygon works for concave-free contours`() {
        assertTrue(Geometry.contains(rectangle, Pt(50.0, 40.0)))
        assertFalse(Geometry.contains(rectangle, Pt(5.0, 40.0)))
    }

    @Test
    fun `homography composition applies translation and scale`() {
        val h = Geometry.multiply3x3(
            Geometry.scaling(2.0),
            Geometry.translation(10.0, -5.0),
        )
        val mapped = Geometry.applyH(h, Pt(3.0, 4.0))
        assertEquals(26.0, mapped.x, 1e-9)
        assertEquals(-2.0, mapped.y, 1e-9)
    }

    @Test
    fun `shoelace area and centroid match a rectangle`() {
        assertEquals(5000.0, Geometry.polygonArea(rectangle), 1e-9)
        val centroid = Geometry.polygonCentroid(rectangle)
        assertEquals(60.0, centroid.x, 1e-9)
        assertEquals(45.0, centroid.y, 1e-9)
    }
}
