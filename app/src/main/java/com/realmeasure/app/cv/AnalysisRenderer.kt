package com.realmeasure.app.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.realmeasure.app.model.ObjectGeometry
import java.util.Locale
import kotlin.math.max

/**
 * Renders the saved history thumbnail from the flattened metric canvas:
 *
 * - cyan outer contour,
 * - amber hole centre crosshairs,
 * - dashed amber hole-to-hole distance callouts,
 * - selected object highlighted.
 *
 * The output is capped to [maxWidthPx] so saving a 4096 px canvas does not
 * allocate a second full-size bitmap.
 */
object AnalysisRenderer {

    fun renderAnnotated(
        warped: Bitmap,
        objects: List<ObjectGeometry>,
        selectedIndex: Int,
        pixelsPerMm: Double,
        maxWidthPx: Int = 1600,
    ): Bitmap {
        val source = if (warped.width > maxWidthPx) {
            val height = (warped.height.toDouble() * maxWidthPx / warped.width)
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(warped, maxWidthPx, height, true)
        } else {
            warped
        }

        try {
            val output = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(output)
            val scaleFactor = output.width.toDouble() / warped.width.toDouble()
            val scale = (pixelsPerMm * scaleFactor).toFloat()
            val stroke = max(2.0f, 0.15f * scale)
            val labelTextSize = max(18.0f, 1.8f * scale)

            val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(28, 56, 189, 248)
            }
            val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.rgb(245, 158, 11)
                strokeWidth = max(1.5f, 0.08f * scale)
            }
            val calloutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 158, 11)
                strokeWidth = max(1.0f, 0.05f * scale)
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(0.8f * scale, 0.55f * scale), 0f)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 158, 11)
                textSize = labelTextSize
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }

            objects.forEachIndexed { index, objectGeometry ->
                val selected = index == selectedIndex
                val path = Path().apply {
                    objectGeometry.outerContour.forEachIndexed { pointIndex, point ->
                        val x = point.x.toFloat() * scale
                        val y = point.y.toFloat() * scale
                        if (pointIndex == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                if (selected) {
                    canvas.drawPath(path, selectedPaint)
                }
                contourPaint.color = if (selected) {
                    Color.rgb(56, 189, 248)
                } else {
                    Color.argb(130, 56, 189, 248)
                }
                canvas.drawPath(path, contourPaint)

                objectGeometry.holes.forEach { hole ->
                    val x = hole.centroid.x.toFloat() * scale
                    val y = hole.centroid.y.toFloat() * scale
                    val radius = max(3.0f, 0.8f * scale)
                    canvas.drawCircle(x, y, radius, holePaint)
                    canvas.drawLine(x - radius * 1.8f, y, x + radius * 1.8f, y, holePaint)
                    canvas.drawLine(x, y - radius * 1.8f, x, y + radius * 1.8f, holePaint)
                }

                if (selected) {
                    val pairs = if (objectGeometry.holes.size <= 6) {
                        objectGeometry.holeSpacings
                    } else {
                        objectGeometry.holeSpacings.filter {
                            it.secondIndex == it.firstIndex + 1
                        }
                    }
                    pairs.forEach { spacing ->
                        val first = objectGeometry.holes[spacing.firstIndex].centroid
                        val second = objectGeometry.holes[spacing.secondIndex].centroid
                        val startX = first.x.toFloat() * scale
                        val startY = first.y.toFloat() * scale
                        val endX = second.x.toFloat() * scale
                        val endY = second.y.toFloat() * scale
                        canvas.drawLine(startX, startY, endX, endY, calloutPaint)

                        val label = String.format(
                            Locale.US,
                            "%.2f ± %.2f mm",
                            spacing.distanceMm,
                            spacing.errorMm,
                        )
                        canvas.drawText(
                            label,
                            (startX + endX) / 2f,
                            (startY + endY) / 2f - 0.8f * scale,
                            textPaint,
                        )
                    }
                }
            }

            return output
        } finally {
            if (source !== warped && !source.isRecycled) {
                source.recycle()
            }
        }
    }
}
