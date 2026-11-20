package com.realmeasure.app

import com.realmeasure.app.cv.StandardMatcher
import com.realmeasure.app.model.HardwareKind
import com.realmeasure.app.model.MatchConfidence
import com.realmeasure.app.model.ThreadKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Metric/Imperial catalogue snapping logic.
 *
 * These run with ./gradlew testDebugUnitTest - no emulator or device needed -
 * proving the matcher is deterministic and the snap tolerances behave.
 */
class StandardMatcherTest {

    // ------------------------------------------------------------------
    // Metric fasteners
    // ------------------------------------------------------------------

    @Test
    fun `exact m6 measurement snaps to M6`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        assertNotNull(result.metric)
        assertEquals("M6", result.metric!!.designation)
    }

    @Test
    fun `m6 measurement recommends the 80mm standard length`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 78.7)
        assertEquals(80.0, result.metric!!.recommendedLengthMm!!, 1e-9)
    }

    @Test
    fun `between sizes prefers the closer standard`() {
        // 2.35 mm sits between M2 (2.0) and M2.5 (2.5) - closer to M2.5.
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 2.35, lengthMm = 10.0)
        assertEquals("M2.5", result.metric!!.designation)
    }

    @Test
    fun `metric match carries coarse and fine thread options`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        val options = result.metric!!.threadOptions
        assertEquals(2, options.size)
        assertTrue(options.any { it.kind == ThreadKind.COARSE && it.pitchMm == 1.0 })
        assertTrue(options.any { it.kind == ThreadKind.FINE && it.pitchMm == 0.75 })
    }

    @Test
    fun `high confidence is awarded for exact matches`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        assertEquals(MatchConfidence.HIGH, result.metric!!.confidence)
        assertTrue(result.metric!!.inTolerance)
    }

    // ------------------------------------------------------------------
    // Imperial equivalents
    // ------------------------------------------------------------------

    @Test
    fun `m6 measurement also matches quarter inch unc`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        val imperial = result.imperial!!
        assertEquals("1/4\"-20", imperial.designation)
        assertTrue(imperial.inTolerance)
    }

    @Test
    fun `imperial lengths are recommended in inches`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        val imperial = result.imperial!!
        assertEquals(3.125, imperial.recommendedLengthIn!!, 1e-9)
        assertEquals(3.125 * 25.4, imperial.recommendedLengthMm!!, 1e-9)
    }

    @Test
    fun `imperial thread options include unc and unf tpi`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        val options = result.imperial!!.threadOptions
        assertEquals(2, options.size)
        assertTrue(options.any { it.kind == ThreadKind.UNC && it.label.contains("20 TPI") })
        assertTrue(options.any { it.kind == ThreadKind.UNF && it.label.contains("28 TPI") })
    }

    // ------------------------------------------------------------------
    // Hex keys
    // ------------------------------------------------------------------

    @Test
    fun `metric hex key snaps exactly`() {
        val result = StandardMatcher.match(HardwareKind.HEX_KEY, diameterMm = 6.0, lengthMm = 160.0)
        assertEquals("6 mm", result.metric!!.designation)
        assertEquals("long", result.metric!!.armType)
        assertEquals(160.0, result.metric!!.recommendedLengthMm!!, 1e-9)
    }

    @Test
    fun `sae quarter inch hex key is the imperial counterpart`() {
        val result = StandardMatcher.match(HardwareKind.HEX_KEY, diameterMm = 6.0, lengthMm = 160.0)
        assertEquals("1/4\"", result.imperial!!.designation)
        assertTrue(result.withinTolerance)
    }

    @Test
    fun `oversized hex key is reported outside tolerance`() {
        val result = StandardMatcher.match(HardwareKind.HEX_KEY, diameterMm = 20.0, lengthMm = 180.0)
        assertNotNull(result.metric)
        assertFalse(result.withinTolerance)
    }

    // ------------------------------------------------------------------
    // Alternatives + guard rails
    // ------------------------------------------------------------------

    @Test
    fun `alternatives exclude the primary metric and imperial matches`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 6.0, lengthMm = 80.0)
        val primaryIds = setOfNotNull(result.metric?.specId, result.imperial?.specId)
        assertTrue(primaryIds.isNotEmpty())
        result.alternatives.forEach { alternative ->
            assertFalse(alternative.specId in primaryIds)
        }
    }

    @Test
    fun `invalid diameter returns an empty result`() {
        val result = StandardMatcher.match(HardwareKind.FASTENER, diameterMm = 0.0, lengthMm = 80.0)
        assertFalse(result.withinTolerance)
        assertEquals(0, result.alternatives.size)
    }
}
