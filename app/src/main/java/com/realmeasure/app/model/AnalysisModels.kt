package com.realmeasure.app.model

/**
 * One internal hole, expressed in millimetres on the flattened canvas.
 *
 * [centroid] follows the image-coordinate convention: origin at the top-left
 * of the warped canvas, x right, y down. The SVG exporter translates it.
 */
data class HoleGeometry(
    val index: Int,
    val centroid: Pt,
    val diameterMm: Double,
    val areaMm2: Double,
    val diameterErrorMm: Double,
    /** approxPolyDP-simplified polygon in mm, used for drawing + SVG. */
    val contour: List<Pt>,
)

/** Centre-to-centre distance between two holes. */
data class HoleSpacing(
    val firstIndex: Int,
    val secondIndex: Int,
    val distanceMm: Double,
    val errorMm: Double,
)

/**
 * Complete measured geometry for one irregular object on the flattened
 * canvas. All distances are millimetres, areas are reported both in mm² and
 * cm², and every displayed length carries a propagated error margin.
 */
data class ObjectGeometry(
    val index: Int,
    val lengthMm: Double,
    val widthMm: Double,
    val lengthErrorMm: Double,
    val widthErrorMm: Double,
    val perimeterMm: Double,
    val perimeterErrorMm: Double,
    val areaNetMm2: Double,
    val areaNetCm2: Double,
    val areaErrorCm2: Double,
    val holes: List<HoleGeometry>,
    val holeSpacings: List<HoleSpacing>,
    /** Centroid of the outer contour, canvas millimetres. */
    val centroid: Pt,
    val boundsMin: Pt,
    val boundsMax: Pt,
    /** approxPolyDP-simplified outer polygon in mm, used for drawing + SVG. */
    val outerContour: List<Pt>,
    val quality: MeasurementQuality,
    val qualityRecommendation: String?,
)
