package com.realmeasure.app.cv

import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.model.ReferenceMode
import kotlin.math.abs

/**
 * Physics-based uncertainty propagation for the planar scanner.
 *
 * The model combines the sensor's sub-pixel edge localisation noise with the
 * residual reference calibration drift. The first term grows with the
 * object-to-reference magnification; the second term grows with the measured
 * length itself.
 *
 * All inputs/outputs are millimetres unless a name says otherwise.
 */
object ErrorEstimator {

    /**
     * Δpx(L) = σ_edge / s_native × (1 + L / W_ref)
     *
     * @param lengthMm the linear dimension being measured.
     * @param nativePxPerMm s_native, measured on the full-resolution source.
     * @param referenceWidthMm the reference's long physical edge.
     */
    fun deltaPx(
        lengthMm: Double,
        nativePxPerMm: Double,
        referenceWidthMm: Double,
    ): Double {
        if (nativePxPerMm <= 0.0 || referenceWidthMm <= 0.0) return Double.POSITIVE_INFINITY
        return (AppConfig.ErrorModel.SENSOR_EDGE_SIGMA_PX / nativePxPerMm) *
            (1.0 + lengthMm / referenceWidthMm)
    }

    /**
     * ε_cal = |R_warped - R_true| / R_true
     *
     * `R_warped` is the reference aspect ratio re-measured on the flattened
     * canvas; `R_true` is the physical reference ratio.
     */
    fun epsilonCal(warpedAspect: Double, trueAspect: Double): Double {
        if (trueAspect <= 0.0 || !warpedAspect.isFinite()) return 0.0
        return abs(warpedAspect - trueAspect) / trueAspect
    }

    /**
     * δL = Δpx(L) + L × ε_cal
     */
    fun linearError(
        lengthMm: Double,
        nativePxPerMm: Double,
        referenceWidthMm: Double,
        epsilonCal: Double,
    ): Double {
        val sensor = deltaPx(lengthMm, nativePxPerMm, referenceWidthMm)
        return sensor + lengthMm * epsilonCal
    }

    /**
     * δA(cm²) = (P_mm × δL_avg + 2 × A_net_mm² × ε_cal) / 100
     *
     * @param deltaLAvgMm the average of the bounding-box length and width
     *   error margins; this is the representative perimeter error.
     */
    fun areaErrorCm2(
        perimeterMm: Double,
        deltaLAvgMm: Double,
        areaNetMm2: Double,
        epsilonCal: Double,
    ): Double {
        return ((perimeterMm * deltaLAvgMm) + (2.0 * areaNetMm2 * epsilonCal)) / 100.0
    }

    /** δD for a hole-to-hole or custom pin distance. */
    fun distanceError(
        distanceMm: Double,
        nativePxPerMm: Double,
        referenceWidthMm: Double,
        epsilonCal: Double,
    ): Double = linearError(distanceMm, nativePxPerMm, referenceWidthMm, epsilonCal)

    /** δd for an equivalent hole diameter. */
    fun diameterError(
        diameterMm: Double,
        nativePxPerMm: Double,
        referenceWidthMm: Double,
        epsilonCal: Double,
    ): Double = linearError(diameterMm, nativePxPerMm, referenceWidthMm, epsilonCal)

    // ------------------------------------------------------------------
    // Optional 3D height parallax correction
    // ------------------------------------------------------------------

    /**
     * k = 1 - h / H_cam
     *
     * A tall object's top face is closer to the camera than the reference
     * plane, so its apparent size is magnified relative to the flat footprint.
     * Multiplying the apparent linear measurements by k (and areas by k²)
     * removes that first-order parallax bias for the standard handheld
     * distance [AppConfig.ErrorModel.CAMERA_DISTANCE_MM].
     */
    fun parallaxFactor(
        heightMm: Double,
        cameraDistanceMm: Double = AppConfig.ErrorModel.CAMERA_DISTANCE_MM,
    ): Double {
        if (cameraDistanceMm <= 0.0) return 1.0
        return (1.0 - heightMm / cameraDistanceMm).coerceIn(0.05, 1.0)
    }

    /** D_true = D_apparent × k */
    fun correctLength(apparentMm: Double, heightMm: Double): Double =
        apparentMm * parallaxFactor(heightMm)

    /** A_true = A_apparent × k² */
    fun correctArea(apparentArea: Double, heightMm: Double): Double {
        val k = parallaxFactor(heightMm)
        return apparentArea * k * k
    }

    /** Linear error margins scale with the same k as the dimension. */
    fun correctLinearError(apparentErrorMm: Double, heightMm: Double): Double =
        apparentErrorMm * parallaxFactor(heightMm)

    /** Area error margins scale with k², like the area itself. */
    fun correctAreaError(apparentError: Double, heightMm: Double): Double {
        val k = parallaxFactor(heightMm)
        return apparentError * k * k
    }

    /** Precision badge from the longer bounding-box dimension's δL. */
    fun quality(lengthErrorMm: Double, mode: ReferenceMode): MeasurementQuality {
        val model = AppConfig.ErrorModel
        return when (mode) {
            ReferenceMode.CARD -> when {
                lengthErrorMm <= model.CARD_HIGH_PRECISION_MM -> MeasurementQuality.HIGH
                lengthErrorMm <= model.CARD_MODERATE_PRECISION_MM -> MeasurementQuality.MODERATE
                else -> MeasurementQuality.LOW
            }
            ReferenceMode.A4 -> when {
                lengthErrorMm <= model.A4_HIGH_PRECISION_MM -> MeasurementQuality.HIGH
                lengthErrorMm <= model.A4_MODERATE_PRECISION_MM -> MeasurementQuality.MODERATE
                else -> MeasurementQuality.LOW
            }
        }
    }

    /**
     * Only low-precision scans get a corrective recommendation. The ID-card
     * mode is explicitly unsuitable for objects larger than 250 mm.
     */
    fun recommendation(
        quality: MeasurementQuality,
        mode: ReferenceMode,
        objectLengthMm: Double,
    ): String? {
        if (quality != MeasurementQuality.LOW) return null
        return if (mode == ReferenceMode.CARD &&
            objectLengthMm > AppConfig.ErrorModel.CARD_MAX_RECOMMENDED_OBJECT_MM
        ) {
            "Large object for an ID card — switch to A4 Paper mode for a lower error margin."
        } else {
            "Move the camera closer, flatten the scene and add light to improve precision."
        }
    }
}
