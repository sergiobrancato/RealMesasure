package com.realmeasure.app.config

import androidx.compose.ui.graphics.Color

/**
 * Central tuning file for the planar CAD scanner.
 *
 * Every threshold that affects detection, calibration, contour extraction or
 * the uncertainty model lives here so the behaviour can be tuned without
 * touching the algorithms.
 */
object AppConfig {
    const val APP_NAME = "RealMeasure"
    const val APP_VERSION = "2.0.0"

    // ------------------------------------------------------------------
    // Hands-free reference detection (live preview)
    // ------------------------------------------------------------------
    object Detection {
        /** Working width for the ~6-8 fps preview detector. */
        const val WORKING_WIDTH_PX = 720
        /** Minimum time between analysed preview frames (~6 fps). */
        const val FRAME_INTERVAL_MS = 160L
        /** Consecutive valid frames required before the still capture fires. */
        const val REQUIRED_CONSECUTIVE_FRAMES = 5
        /** Debounce after returning from Success/Error before a new auto-capture. */
        const val SCAN_COOLDOWN_MS = 500L
        /** Reject non-90-degree corners beyond this in the live tracker. */
        const val MAX_LIVE_TILT_DEG = 12.0
        /** Allowed relative aspect deviation from the reference's true ratio. */
        const val ASPECT_TOLERANCE = 0.12
        /** A reference must occupy at least this fraction of the preview frame. */
        const val MIN_AREA_RATIO = 0.0012
        const val MAX_AREA_RATIO = 0.92
        /** approxPolyDP epsilon as a fraction of the contour perimeter. */
        const val APPROX_EPSILON_RATIO = 0.02
        /** Contour area / convex-hull area must be at least this. */
        const val MIN_SOLIDITY = 0.78
        /** Shortest accepted quad side in working pixels. */
        const val MIN_QUAD_SIDE_PX = 32.0
        /** Keep the whole reference at least this far from the frame border. */
        const val EDGE_MARGIN_RATIO = 0.008
        /** Mean grey value below which the scene is considered dark. */
        const val LOW_LIGHT_MEAN = 45.0

        const val CLAHE_CLIP_LIMIT = 2.0
        const val CLAHE_TILE_GRID = 8.0
        const val CANNY_LOW = 32.0
        const val CANNY_HIGH = 96.0
        const val CLOSE_KERNEL = 5
    }

    // ------------------------------------------------------------------
    // Full-resolution calibration
    // ------------------------------------------------------------------
    object Calibration {
        /** Downscale used only to find the reference in the still. */
        const val DETECT_MAX_WIDTH_PX = 1800
        /** Hard cap for the decoded still and the Mat conversion (longest side). */
        const val MAX_SOURCE_SIDE_PX = 2048
        /** Largest flattened canvas; the px/mm scale is reduced if exceeded. */
        const val MAX_CANVAS_PX = 2400
        /** cornerSubPix window and stop criteria from the specification. */
        const val SUBPIX_WIN = 11.0
        const val SUBPIX_ITERATIONS = 30
        const val SUBPIX_EPSILON = 0.01
        /** Interior-angle deviation that invalidates a still. */
        const val MAX_TILT_DEVIATION_DEG = 22.0
        /** Margin around the reference when masking it out of the analysis. */
        const val REFERENCE_MASK_MARGIN_MM = 3.0
    }

    // ------------------------------------------------------------------
    // Irregular-shape contour analysis
    // ------------------------------------------------------------------
    object Analysis {
        const val BINARY_BLOCK = 41
        const val BINARY_C = 6.0
        const val CANNY_LOW = 35.0
        const val CANNY_HIGH = 110.0
        const val CLOSE_KERNEL = 5
        /** Background Lab color difference (0-255) used to seed the object mask. */
        const val BG_COLOR_TOLERANCE = 18.0
        /** Background luminance difference (0-255) used for hole pass-through. */
        const val BG_LUMA_TOLERANCE = 14.0
        /** Minimum mean color contrast for reliable background-colored hole detection. */
        const val MIN_OBJECT_CONTRAST = 14.0
        /** Smallest object worth measuring. */
        const val MIN_OBJECT_AREA_MM2 = 20.0
        /** Guard against a hand/table blob being treated as an object. */
        const val MAX_OBJECT_AREA_MM2 = 4_000_000.0
        /** Real pass-through holes only; rejects printed text and specular spots. */
        const val HOLE_MIN_AREA_MM2 = 2.5
        const val HOLE_MIN_CIRCULARITY = 0.45
        const val HOLE_MAX_OBJECT_FRACTION = 0.95
        /** GrabCut refinement iterations. */
        const val GRABCUT_ITERATIONS = 3
        /** SVG polygon simplification tolerance. */
        const val SVG_EPSILON_MM = 0.25
        /** Objects touching the warped canvas border are incomplete. */
        const val BORDER_MARGIN_PX = 3
    }

    // ------------------------------------------------------------------
    // Uncertainty model
    // ------------------------------------------------------------------
    object ErrorModel {
        /** 1-sigma edge localisation uncertainty in sensor pixels. */
        const val SENSOR_EDGE_SIGMA_PX = 1.25

        /** Precision badge thresholds for the small-object reference. */
        const val CARD_HIGH_PRECISION_MM = 0.6
        const val CARD_MODERATE_PRECISION_MM = 1.5
        /** Precision badge thresholds for the large-object reference. */
        const val A4_HIGH_PRECISION_MM = 1.8
        const val A4_MODERATE_PRECISION_MM = 3.5

        /** Above this length the ID-card mode is no longer recommended. */
        const val CARD_MAX_RECOMMENDED_OBJECT_MM = 250.0

        /** Assumed handheld camera-to-plane distance for 3D parallax correction. */
        const val CAMERA_DISTANCE_MM = 400.0
        /** Maximum object height supported by the parallax slider. */
        const val MAX_PARALLAX_HEIGHT_MM = 150.0
    }
}

/**
 * Precision dark-workshop color tokens referenced by the Compose theme and
 * every canvas drawing in the app.
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
    val Emerald = Color(0xFF22C55E)
    val Danger = Color(0xFFFB7185)
}
