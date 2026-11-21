package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceMode

/** Integer rectangle in the flattened canvas. */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Result of the metric planar calibration.
 *
 * [pixelsPerMm] is the final scale of [warpedBitmap] after any
 * `MAX_CANVAS_PX` clamping, so every pixel measures exactly `1 / pixelsPerMm`
 * millimetres. [referenceRectPx] is where the reference landed on the
 * flattened canvas and is masked out before object analysis.
 */
data class CalibrationResult(
    val mode: ReferenceMode,
    val warpedBitmap: Bitmap,
    val warpedWidthMm: Double,
    val warpedHeightMm: Double,
    val pixelsPerMm: Double,
    val nativePixelsPerMm: Double,
    val epsilonCal: Double,
    val warpedReferenceAspect: Double,
    val tiltDeviationDeg: Double,
    val referenceQuad: List<Pt>,
    val referenceRectPx: PixelRect,
    val homography: DoubleArray,
)

/** Typed calibration failure with a user-facing explanation. */
class CalibrationException(
    val code: String,
    message: String,
) : Exception(message)
