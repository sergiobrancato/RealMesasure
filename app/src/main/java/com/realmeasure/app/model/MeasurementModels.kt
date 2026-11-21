package com.realmeasure.app.model

/**
 * The two physical reference anchors the scanner can lock onto.
 *
 * Both are planar rectangles with a known ISO size, which is what turns a
 * single camera image into a metric coordinate system.
 */
enum class ReferenceMode(
    /** Full label used in the result and history cards. */
    val label: String,
    /** Compact label for the segmented control. */
    val shortLabel: String,
    /** Long reference edge in millimetres. */
    val widthMm: Double,
    /** Short reference edge in millimetres. */
    val heightMm: Double,
    /** Pixels per millimetre used for the flattened canvas. */
    val warpPxPerMm: Double,
    /** Guidance copy about the object size this mode is designed for. */
    val sizeHint: String,
) {
    CARD(
        label = "ID / Credit Card",
        shortLabel = "SMALL · ID CARD",
        widthMm = 85.60,
        heightMm = 53.98,
        warpPxPerMm = 10.0,
        sizeHint = "Best for parts, brackets and gaskets under 250 mm",
    ),
    A4(
        label = "A4 Paper Sheet",
        shortLabel = "LARGE · A4 SHEET",
        widthMm = 297.00,
        heightMm = 210.00,
        warpPxPerMm = 5.0,
        sizeHint = "Best for boards, panels and templates from 250 mm to 2000 mm",
    );

    /** The true width / height ratio printed on the physical reference. */
    val trueAspect: Double get() = widthMm / heightMm
}

/** Quality band derived from the propagated linear error margin. */
enum class MeasurementQuality(val label: String) {
    HIGH("High precision"),
    MODERATE("Moderate precision"),
    LOW("Low precision"),
}

/** A point in image pixels or millimetres (context-dependent). */
data class Pt(val x: Double, val y: Double)

/**
 * One detected reference quadrilateral, ordered TL -> TR -> BR -> BL in the
 * source image. [score] is a 0..1 detector confidence used only for ranking.
 */
data class ReferenceDetection(
    val quad: List<Pt>,
    val score: Double,
    val aspectError: Double,
)
