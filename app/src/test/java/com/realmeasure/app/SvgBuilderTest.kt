package com.realmeasure.app

import com.realmeasure.app.export.SvgBuilder
import com.realmeasure.app.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** True-scale SVG serialisation tests. */
class SvgBuilderTest {

    private val square = listOf(
        Pt(2.0, 2.0),
        Pt(12.0, 2.0),
        Pt(12.0, 7.0),
        Pt(2.0, 7.0),
    )

    @Test
    fun `svg declares millimetre units and a matching viewBox`() {
        val document = SvgBuilder.build(square, emptyList(), "bracket")
        assertTrue(document.text.contains("width=\"10mm\""))
        assertTrue(document.text.contains("height=\"5mm\""))
        assertTrue(document.text.contains("viewBox=\"0 0 10 5\""))
        assertEquals(10.0, document.widthMm, 1e-9)
        assertEquals(5.0, document.heightMm, 1e-9)
    }

    @Test
    fun `outer contour is translated to the origin`() {
        val document = SvgBuilder.build(square, emptyList(), "bracket")
        assertTrue(document.text.contains("d=\"M 0 0 L 10 0 L 10 5 L 0 5 Z"))
    }

    @Test
    fun `holes are combined into one even-odd path`() {
        val hole = circle(4.0, 4.5, 1.0)
        val document = SvgBuilder.build(square, listOf(hole), "plate")
        assertTrue(document.text.contains("fill-rule=\"evenodd\""))
        val moveCount = Regex("M ").findAll(document.text).count()
        // One M for the outer contour + one for the hole.
        assertEquals(2, moveCount)
        assertTrue(document.text.contains("<title>plate</title>"))
    }

    @Test
    fun `xml special characters are escaped in the title`() {
        val document = SvgBuilder.build(square, emptyList(), "A & B <test>")
        assertTrue(document.text.contains("A &amp; B &lt;test&gt;"))
    }

    private fun circle(cx: Double, cy: Double, radius: Double): List<Pt> =
        (0 until 24).map { index ->
            val angle = index * Math.PI * 2.0 / 24.0
            Pt(cx + radius * cos(angle), cy + radius * sin(angle))
        }
}
