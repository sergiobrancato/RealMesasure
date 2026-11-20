package com.realmeasure.app.model

/**
 * Pure domain models shared by the vision pipeline, the matcher and the UI.
 *
 * Nothing in this file imports Android or OpenCV so the catalogue matching
 * logic stays unit-testable on the JVM (see StandardMatcherTest).
 */

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

/** Capture mode selected on the scanner screen. */
enum class ScanMode(
    val label: String,
    val dimensionLabel: String,
    val hint: String,
) {
    SCREW_BOLT(
        label = "Screw / Bolt",
        dimensionLabel = "Shaft \u00D8",
        hint = "Lay the fastener flat beside the card. Keep the whole part inside the cyan zone.",
    ),
    HEX_KEY(
        label = "Hex Key",
        dimensionLabel = "Across flats",
        hint = "Align the long arm vertically in the cyan zone. The short leg identifies the size.",
    ),
}

/** Display unit. All stored measurements are millimetres. */
enum class UnitSystem(val suffix: String) {
    MM("mm"),
    INCH("in"),
}

/** Catalogue family. */
enum class HardwareFamily { METRIC, IMPERIAL }

/** Catalogue entry kind. */
enum class HardwareKind { FASTENER, HEX_KEY }

/** Thread/pitch option shown under a matched fastener. */
enum class ThreadKind { COARSE, FINE, UNC, UNF }

/** Confidence band for a catalogue match. */
enum class MatchConfidence { HIGH, MEDIUM, LOW }

// ---------------------------------------------------------------------------
// Geometry primitives
// ---------------------------------------------------------------------------

/** A point in image pixels or metric millimetres (context-dependent). */
data class Pt(val x: Double, val y: Double)

/** A point normalised to the processed bitmap (0..1 on both axes). */
data class NormPt(val x: Double, val y: Double)

/** A quadrilateral ordered TL -> TR -> BR -> BL. */
data class CardQuad(
    val topLeft: Pt,
    val topRight: Pt,
    val bottomRight: Pt,
    val bottomLeft: Pt,
)

/** The four draggable caliper points, normalised to the processed bitmap. */
data class CaliperHandles(
    val widthA: NormPt,
    val widthB: NormPt,
    val lengthA: NormPt,
    val lengthB: NormPt,
)

// ---------------------------------------------------------------------------
// Calibration
// ---------------------------------------------------------------------------

/**
 * Result of calibrating against the ID-1 card.
 *
 * [homography] is a row-major 3x3 matrix mapping source-image pixels to a
 * metric plane whose origin is the top-left corner of the card. It is valid
 * for any object resting on the same flat surface - exactly how the fastener
 * scan zone is rectified before measurement.
 */
data class CalibrationResult(
    /** Isotropic convenience scale (mean of X/Y scale). */
    val pixelsPerMm: Double,
    val pixelsPerMmX: Double,
    val pixelsPerMmY: Double,
    val cardWidthMm: Double,
    val cardHeightMm: Double,
    val cardQuad: CardQuad,
    val homography: DoubleArray,
    /** 0..1 confidence from quad fit, scale agreement and reprojection error. */
    val confidence: Double,
    /** |scaleX - scaleY| / meanScale. Lower is better. */
    val scaleDiscrepancy: Double,
    /** Mean corner reprojection error, in source pixels. */
    val reprojectionErrorPx: Double,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
)

// ---------------------------------------------------------------------------
// Fastener measurement
// ---------------------------------------------------------------------------

/** Which binarisation produced the winning contour. */
enum class BinarisationStage { ADAPTIVE, CANNY }

/** Raw pixel-domain output from FastenerAnalyzer. */
data class RawPixelMeasurement(
    val shaftWidthPx: Double,
    val shaftLengthPx: Double,
    val overallLengthPx: Double,
    val headWidthPx: Double,
    val headLengthPx: Double,
    /** Orientation of the major (length) axis, degrees. */
    val angleDeg: Double,
    val pixelsPerMm: Double,
    val contourAreaPx: Double,
    val stage: BinarisationStage,
)

/** Fully processed fastener measurement. */
data class FastenerMeasurement(
    val mode: ScanMode,
    val detected: Boolean,
    /** Shaft diameter / hex-key across-flats value (mm). */
    val shaftDiameterMm: Double,
    /** Under-head length for screws, overall long arm for hex keys (mm). */
    val lengthMm: Double,
    val headWidthMm: Double,
    val overallLengthMm: Double,
    val confidence: Double,
    val raw: RawPixelMeasurement,
    val handles: CaliperHandles,
)

// ---------------------------------------------------------------------------
// Catalogue matching
// ---------------------------------------------------------------------------

/** Thread/pitch option for a matched fastener. */
data class ThreadOption(
    val kind: ThreadKind,
    val label: String,
    val pitchMm: Double,
)

/** A single catalogue match with deltas and standard-length guidance. */
data class StandardMatch(
    val specId: String,
    val designation: String,
    val family: HardwareFamily,
    val kind: HardwareKind,
    val nominalMm: Double,
    val nominalIn: Double,
    val measuredMm: Double,
    val deltaMm: Double,
    val inTolerance: Boolean,
    val confidence: MatchConfidence,
    val threadOptions: List<ThreadOption>,
    val recommendedLengthMm: Double? = null,
    val recommendedLengthIn: Double? = null,
    /** "short" or "long" for hex-key arm identification. */
    val armType: String? = null,
    val standard: String? = null,
    val headType: String? = null,
    val socketSizeMm: Double? = null,
    val socketSizeIn: Double? = null,
    /** 0..1, 1 = exact nominal match. */
    val score: Double,
)

/** Metric + imperial identification result. */
data class StandardMatchResult(
    val metric: StandardMatch?,
    val imperial: StandardMatch?,
    val alternatives: List<StandardMatch>,
    /** True when at least one family is inside the configured tolerance. */
    val withinTolerance: Boolean,
)
