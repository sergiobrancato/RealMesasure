package com.realmeasure.app.data

import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceMode

/**
 * One saved planar scan.
 *
 * [svgOuter] and [svgHoles] are the already-simplified polygons in
 * millimetres, so "re-export 1:1 SVG" works even if the original photo is
 * deleted and requires no computer vision at export time.
 */
data class ScanRecord(
    val id: String,
    val timestamp: Long,
    val mode: ReferenceMode,
    val imagePath: String,
    val lengthMm: Double,
    val widthMm: Double,
    val lengthErrorMm: Double,
    val widthErrorMm: Double,
    val areaCm2: Double,
    val areaErrorCm2: Double,
    val perimeterMm: Double,
    val holeCount: Int,
    val quality: MeasurementQuality,
    val svgOuter: List<Pt>,
    val svgHoles: List<List<Pt>>,
)
