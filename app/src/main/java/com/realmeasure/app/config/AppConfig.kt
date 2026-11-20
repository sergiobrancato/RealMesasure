package com.realmeasure.app.config

import androidx.compose.ui.graphics.Color

/**
 * Central tuning file for RealMeasure.
 *
 * Everything a workshop might want to adjust lives here: ISO ID-1 reference
 * dimensions, viewfinder guide geometry, OpenCV thresholds, matching snap
 * tolerances, caliper fine-step and the design color tokens referenced by the
 * Compose theme. Editing a value here changes behaviour everywhere - no other
 * file needs to be touched for calibration or measurement tuning.
 */
object AppConfig {
    const val APP_NAME = "RealMeasure"
    const val APP_VERSION = "1.0.0"

    // ------------------------------------------------------------------
    // ISO/IEC 7810 ID-1 reference card
    // ------------------------------------------------------------------
    /** Every bank card, ID card and most transit/loyalty cards. */
    const val CARD_WIDTH_MM = 85.60
    const val CARD_HEIGHT_MM = 53.98
    const val CARD_ASPECT = CARD_WIDTH_MM / CARD_HEIGHT_MM // ~1.5858

    /** Millimetres per inch. */
    const val MM_PER_INCH = 25.4

    // ------------------------------------------------------------------
    // Capture geometry
    // ------------------------------------------------------------------
    object Capture {
        /** Stage aspect (width / height). The captured bitmap is centre-cropped
         *  to this ratio so normalised guide coordinates map 1:1. */
        const val STAGE_ASPECT = 3.0 / 4.0
        /** Analysis bitmap width cap (px). */
        const val PROCESSING_MAX_WIDTH_PX = 1200
    }

    // ------------------------------------------------------------------
    // Viewfinder guides (normalised to the 3:4 stage)
    // ------------------------------------------------------------------
    object Guides {
        const val CARD_X = 0.035
        const val CARD_Y = 0.16
        const val CARD_W = 0.60
        /** CARD_W * STAGE_ASPECT / CARD_ASPECT ~ 0.284 (drawn at true ID-1 ratio). */
        const val CARD_H = 0.284
        const val TARGET_X = 0.665
        const val TARGET_Y = 0.10
        const val TARGET_W = 0.30
        const val TARGET_H = 0.72
        /** Extra search margin around each guide when cropping the still photo. */
        const val CARD_SEARCH_PADDING = 0.12
        const val TARGET_SEARCH_PADDING = 0.08
    }

    // ------------------------------------------------------------------
    // OpenCV tuning - card calibration
    // ------------------------------------------------------------------
    object CardCv {
        const val GAUSSIAN_KERNEL = 5
        const val CANNY_LOW = 45.0
        const val CANNY_HIGH = 140.0
        const val ADAPTIVE_BLOCK = 31
        const val ADAPTIVE_C = 7.0
        /** approxPolyDP epsilon as a fraction of contour perimeter. */
        const val APPROX_EPSILON_RATIO = 0.022
        /** Card area must occupy at least / at most this fraction of the search crop. */
        const val MIN_AREA_RATIO = 0.18
        const val MAX_AREA_RATIO = 0.98
        /** Maximum relative deviation from the 1.5858 ID-1 aspect ratio. */
        const val MAX_ASPECT_ERROR = 0.22
        /** Reject quads whose corners touch the search crop (card is clipped). */
        const val EDGE_GUARD_RATIO = 0.015
        /** Reject if horizontal/vertical scales disagree by more than this. */
        const val MAX_SCALE_DISCREPANCY = 0.28
        /** px/mm for the persisted rectified card thumbnail. */
        const val RECTIFIED_PX_PER_MM = 10.0
    }

    // ------------------------------------------------------------------
    // OpenCV tuning - fastener analysis
    // ------------------------------------------------------------------
    object FastenerCv {
        const val GAUSSIAN_KERNEL = 5
        const val CANNY_LOW = 35.0
        const val CANNY_HIGH = 110.0
        const val ADAPTIVE_BLOCK = 31
        const val ADAPTIVE_C = 5.0
        const val MORPH_KERNEL = 3
        /** Contour must exceed this area (mm^2) after metric rectification. */
        const val MIN_AREA_MM2 = 1.6
        /** A head is a run at one end wider than shaftWidth * this ratio. */
        const val HEAD_WIDTH_RATIO = 1.3
        /** Heads longer than this fraction of the part are treated as noise. */
        const val MAX_HEAD_LENGTH_FRACTION = 0.48
        /** Number of bins used for the longitudinal width profile. */
        const val PROFILE_BINS = 72
        /** Robust shaft width percentile (lower ignores the head more strongly). */
        const val SHAFT_WIDTH_PERCENTILE = 0.35
        /** Physical extents a candidate contour must fit inside (mm). */
        const val MIN_DIAMETER_MM = 0.8
        const val MAX_DIAMETER_MM = 26.0
        const val MIN_LENGTH_MM = 2.0
        const val MAX_LENGTH_MM = 220.0
        /** Pixel-per-mm of the perspective-rectified measurement crop. */
        const val RECTIFIED_PX_PER_MM = 12.0
        const val MAX_RECTIFIED_PX = 2600
        // Contour score weights (sum to 1.0).
        const val WEIGHT_AREA = 0.30
        const val WEIGHT_ELONGATION = 0.25
        const val WEIGHT_CENTER = 0.20
        const val WEIGHT_EDGE_SHARPNESS = 0.25
    }

    // ------------------------------------------------------------------
    // Hardware identification snap tolerances
    // ------------------------------------------------------------------
    object Matching {
        const val ABSOLUTE_TOLERANCE_MM = 0.35
        const val RELATIVE_TOLERANCE = 0.045
        const val HEX_ABSOLUTE_TOLERANCE_MM = 0.30
        const val HEX_RELATIVE_TOLERANCE = 0.06
        const val MAX_ALTERNATIVES = 2
    }

    // ------------------------------------------------------------------
    // Interactive caliper
    // ------------------------------------------------------------------
    object Caliper {
        /** Fine-adjust increment (mm). */
        const val STEP_MM = 0.1
        const val MIN_WIDTH_MM = 0.2
        const val MIN_LENGTH_MM = 0.2
        /** Drag hit radius for caliper handles (dp). */
        const val TOUCH_RADIUS_DP = 28
    }
}

/**
 * Precision dark-workshop color tokens (referenced by the Compose theme and
 * every overlay/canvas drawing in the app).
 */
object WorkshopPalette {
    val Background = Color(0xFF0B0D10)
    val Sunken = Color(0xFF0F1319)
    val Surface = Color(0xFF151921)
    val SurfaceRaised = Color(0xFF1B222D)
    val Amber = Color(0xFFF59E0B)
    val AmberDark = Color(0xFFB45309)
    val Cyan = Color(0xFF38BDF8)
    val CyanLight = Color(0xFF7DD3FC)
    val TextPrimary = Color(0xFFF3F4F6)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF94A3B8)
    val TextFaint = Color(0xFF64748B)
    val Border = Color(0xFF263041)
    val Success = Color(0xFF34D399)
    val Danger = Color(0xFFFB7185)
}
