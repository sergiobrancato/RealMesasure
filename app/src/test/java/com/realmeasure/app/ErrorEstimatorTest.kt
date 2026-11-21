package com.realmeasure.app

import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.cv.ErrorEstimator
import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.model.ReferenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the physics-based uncertainty model. These are pure Kotlin and
 * run with `./gradlew testDebugUnitTest` without a device or OpenCV.
 */
class ErrorEstimatorTest {

    @Test
    fun `delta px follows the magnification formula`() {
        val delta = ErrorEstimator.deltaPx(
            lengthMm = 85.6,
            nativePxPerMm = 10.0,
            referenceWidthMm = 85.6,
        )
        assertEquals(0.25, delta, 1e-9)
    }

    @Test
    fun `linear error combines sensor and calibration terms`() {
        val sensor = ErrorEstimator.deltaPx(100.0, 10.0, 100.0)
        val expected = sensor + 100.0 * 0.01
        val actual = ErrorEstimator.linearError(100.0, 10.0, 100.0, 0.01)
        assertEquals(expected, actual, 1e-9)
    }

    @Test
    fun `area error follows the propagated perimeter formula`() {
        val error = ErrorEstimator.areaErrorCm2(
            perimeterMm = 200.0,
            deltaLAvgMm = 0.3,
            areaNetMm2 = 1000.0,
            epsilonCal = 0.01,
        )
        assertEquals(0.8, error, 1e-9)
    }

    @Test
    fun `epsilon cal is relative aspect drift`() {
        val epsilon = ErrorEstimator.epsilonCal(1.60, 1.5858)
        assertEquals(0.008957, epsilon, 1e-5)
    }

    @Test
    fun `card quality thresholds are applied`() {
        assertEquals(MeasurementQuality.HIGH, ErrorEstimator.quality(0.5, ReferenceMode.CARD))
        assertEquals(MeasurementQuality.MODERATE, ErrorEstimator.quality(1.0, ReferenceMode.CARD))
        assertEquals(MeasurementQuality.LOW, ErrorEstimator.quality(2.0, ReferenceMode.CARD))
    }

    @Test
    fun `a4 quality thresholds are more permissive`() {
        assertEquals(MeasurementQuality.HIGH, ErrorEstimator.quality(1.0, ReferenceMode.A4))
        assertEquals(MeasurementQuality.MODERATE, ErrorEstimator.quality(2.5, ReferenceMode.A4))
        assertEquals(MeasurementQuality.LOW, ErrorEstimator.quality(4.0, ReferenceMode.A4))
    }

    @Test
    fun `low precision card recommends A4 for large objects`() {
        val recommendation = ErrorEstimator.recommendation(
            quality = MeasurementQuality.LOW,
            mode = ReferenceMode.CARD,
            objectLengthMm = 300.0,
        )
        assertNotNull(recommendation)
        assertTrue(recommendation!!.contains("A4"))
    }

    @Test
    fun `high precision has no recommendation`() {
        assertNull(
            ErrorEstimator.recommendation(
                quality = MeasurementQuality.HIGH,
                mode = ReferenceMode.CARD,
                objectLengthMm = 50.0,
            ),
        )
    }

    @Test
    fun `a4 cuts large-board uncertainty by more than sixty percent`() {
        // Same 200 mm board, but the ID card forces a much lower native px/mm.
        val cardError = ErrorEstimator.linearError(
            lengthMm = 200.0,
            nativePxPerMm = 2.5,
            referenceWidthMm = ReferenceMode.CARD.widthMm,
            epsilonCal = 0.002,
        )
        val a4Error = ErrorEstimator.linearError(
            lengthMm = 200.0,
            nativePxPerMm = 8.0,
            referenceWidthMm = ReferenceMode.A4.widthMm,
            epsilonCal = 0.002,
        )
        assertTrue(a4Error < cardError * 0.40)
    }

    // ------------------------------------------------------------------
    // 3D height parallax correction
    // ------------------------------------------------------------------

    @Test
    fun `zero height leaves measurements unchanged`() {
        assertEquals(1.0, ErrorEstimator.parallaxFactor(0.0), 1e-12)
        assertEquals(100.0, ErrorEstimator.correctLength(100.0, 0.0), 1e-12)
        assertEquals(2500.0, ErrorEstimator.correctArea(2500.0, 0.0), 1e-12)
    }

    @Test
    fun `forty millimetres height at four hundred millimetres produces k of zero point nine`() {
        val k = ErrorEstimator.parallaxFactor(40.0)
        assertEquals(0.9, k, 1e-12)
        assertEquals(90.0, ErrorEstimator.correctLength(100.0, 40.0), 1e-9)
        assertEquals(2025.0, ErrorEstimator.correctArea(2500.0, 40.0), 1e-9)
    }

    @Test
    fun `parallax correction scales error margins by k and k squared`() {
        assertEquals(
            0.9,
            ErrorEstimator.correctLinearError(1.0, 40.0),
            1e-12,
        )
        assertEquals(
            0.81,
            ErrorEstimator.correctAreaError(1.0, 40.0),
            1e-12,
        )
    }

    @Test
    fun `maximum supported height stays physically meaningful`() {
        val k = ErrorEstimator.parallaxFactor(
            AppConfig.ErrorModel.MAX_PARALLAX_HEIGHT_MM,
        )
        assertEquals(0.625, k, 1e-12)
        assertTrue(k > 0.0)
    }
}
