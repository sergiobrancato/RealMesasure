package com.realmeasure.app.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.CalibrationResult
import com.realmeasure.app.model.CaliperHandles
import com.realmeasure.app.model.FastenerMeasurement
import com.realmeasure.app.model.Pt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Creates the two images returned in [ScanUiState.Success]:
 *
 * - **annotated** - the frozen frame with the detected card quad and caliper
 *   lines drawn over it, shown in the result modal.
 * - **raw crop** - a tight, pad-around-the-fastener JPEG-friendly crop stored
 *   as the history thumbnail.
 */
object AnalysisRenderer {

    /** Draw the calibration card quad and the measured caliper lines. */
    fun annotate(
        source: Bitmap,
        calibration: CalibrationResult,
        measurement: FastenerMeasurement,
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val stroke = max(2f, source.width / 280f)

        val emerald = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WorkshopPalette.Emerald.copy(alpha = 0.95f).toArgbInt()
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WorkshopPalette.Cyan.copy(alpha = 0.95f).toArgbInt()
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

        // Card quad.
        val quadPoints = Geometry.quadPoints(calibration.cardQuad)
        if (quadPoints.size == 4) {
            val path = Path().apply {
                moveTo(quadPoints[0].x.toFloat(), quadPoints[0].y.toFloat())
                for (index in 1 until quadPoints.size) {
                    lineTo(quadPoints[index].x.toFloat(), quadPoints[index].y.toFloat())
                }
                close()
            }
            canvas.drawPath(path, emerald)
        }

        // Measurement lines following the detected handles.
        val handles = measurement.handles
        val widthA = handles.widthA.toPixel(source)
        val widthB = handles.widthB.toPixel(source)
        val lengthA = handles.lengthA.toPixel(source)
        val lengthB = handles.lengthB.toPixel(source)

        canvas.drawLine(widthA.x.toFloat(), widthA.y.toFloat(), widthB.x.toFloat(), widthB.y.toFloat(), cyan)
        canvas.drawLine(lengthA.x.toFloat(), lengthA.y.toFloat(), lengthB.x.toFloat(), lengthB.y.toFloat(), cyan)
        drawTicks(canvas, widthA, widthB, cyan, stroke * 3f)
        drawTicks(canvas, lengthA, lengthB, cyan, stroke * 3f)

        return result
    }

    /**
     * Crop a padded rectangle around the four measurement handles. This is the
     * image saved to the "My Hardware" history.
     */
    fun cropAroundFastener(source: Bitmap, handles: CaliperHandles): Bitmap {
        val points = listOf(
            handles.widthA.toPixel(source),
            handles.widthB.toPixel(source),
            handles.lengthA.toPixel(source),
            handles.lengthB.toPixel(source),
        )
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val padding = max(maxX - minX, maxY - minY) * 0.22f + source.width * 0.02f
        val left = (minX - padding).roundToInt().coerceIn(0, source.width - 1)
        val top = (minY - padding).roundToInt().coerceIn(0, source.height - 1)
        val right = (maxX + padding).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (maxY + padding).roundToInt().coerceIn(top + 1, source.height)
        val cropWidth = max(48, right - left).coerceAtMost(source.width - left)
        val cropHeight = max(48, bottom - top).coerceAtMost(source.height - top)
        return try {
            val subset = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
            // Make sure the history crop owns its pixels: the analysis frame is
            // recycled as soon as this method returns.
            val independent = subset.copy(Bitmap.Config.ARGB_8888, false)
            if (independent !== subset) subset.recycle()
            independent
        } catch (exception: IllegalArgumentException) {
            Bitmap.createScaledBitmap(source, 256, 256, true)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun com.realmeasure.app.model.NormPt.toPixel(bitmap: Bitmap): Pt =
        Pt(x * bitmap.width, y * bitmap.height)

    private fun drawTicks(canvas: Canvas, a: Pt, b: Pt, paint: Paint, length: Float) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val norm = kotlin.math.hypot(dx, dy)
        if (norm < 1e-3) return
        val nx = (-dy / norm) * length
        val ny = (dx / norm) * length
        canvas.drawLine((a.x - nx).toFloat(), (a.y - ny).toFloat(), (a.x + nx).toFloat(), (a.y + ny).toFloat(), paint)
        canvas.drawLine((b.x - nx).toFloat(), (b.y - ny).toFloat(), (b.x + nx).toFloat(), (b.y + ny).toFloat(), paint)
    }

    /** Compose Color -> ARGB int without importing the Compose graphics package here. */
    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        android.graphics.Color.argb(
            (alpha * 255).roundToInt(),
            (red * 255).roundToInt(),
            (green * 255).roundToInt(),
            (blue * 255).roundToInt(),
        )
}
