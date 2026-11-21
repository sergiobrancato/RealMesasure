package com.realmeasure.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.ReferenceMode
import com.realmeasure.app.ui.ScanUiState
import com.realmeasure.app.ui.theme.Mono

/**
 * Viewfinders HUD for the planar scanner.
 *
 * When the reference is detected the full quadrilateral is drawn in emerald
 * green with corner targets; a stability ring at the bottom fills over five
 * consecutive frames and automatically triggers the full-resolution capture.
 * While searching, a subtle amber reticle marks the live detection area.
 */
@Composable
fun ScannerOverlay(
    mode: ReferenceMode,
    scanning: ScanUiState.Scanning?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val quad = scanning?.referenceQuad
    val tilt = scanning?.tiltDeg ?: 0.0
    val progress = scanning?.stabilityProgress ?: 0f
    val detected = scanning?.referenceDetected == true
    val aligned = detected && tilt <= AppConfig.Detection.MAX_LIVE_TILT_DEG
    val accent = when {
        !detected -> WorkshopPalette.Amber
        aligned -> WorkshopPalette.Emerald
        else -> WorkshopPalette.Amber
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = WorkshopPalette.Background.copy(alpha = 0.18f))

        if (quad == null || quad.size != 4) {
            drawSearchReticle(mode, textMeasurer)
        } else {
            val points = quad.map {
                Offset(
                    x = (it.x * size.width).toFloat().coerceIn(0f, size.width),
                    y = (it.y * size.height).toFloat().coerceIn(0f, size.height),
                )
            }
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
                close()
            }
            drawPath(path, color = accent.copy(alpha = 0.08f))
            drawPath(
                path = path,
                color = accent,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(10.dp.toPx(), 6.dp.toPx()),
                        0f,
                    ),
                ),
            )
            points.forEach { point ->
                drawCircle(color = WorkshopPalette.Background, radius = 7.dp.toPx(), center = point)
                drawCircle(
                    color = accent,
                    radius = 5.dp.toPx(),
                    center = point,
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(color = accent, radius = 1.8.dp.toPx(), center = point)
            }

            val minX = points.minOf { it.x }
            val minY = points.minOf { it.y }
            val label = if (mode == ReferenceMode.CARD) "ID CARD TRACKED" else "A4 SHEET TRACKED"
            drawText(
                textMeasurer,
                label,
                topLeft = Offset(minX, (minY - 24.dp.toPx()).coerceAtLeast(6.dp.toPx())),
                style = TextStyle(
                    color = accent,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.0.sp,
                ),
            )
            if (tilt > 6.0) {
                drawText(
                    textMeasurer,
                    "TILT %.1f°".format(tilt),
                    topLeft = Offset(minX, (minY - 10.dp.toPx()).coerceAtLeast(6.dp.toPx())),
                    style = TextStyle(
                        color = if (tilt > AppConfig.Detection.MAX_LIVE_TILT_DEG) {
                            WorkshopPalette.Amber
                        } else {
                            WorkshopPalette.TextMuted
                        },
                        fontFamily = Mono,
                        fontSize = 8.sp,
                    ),
                )
            }
        }

        drawStabilityRing(
            textMeasurer = textMeasurer,
            progress = progress,
            accent = accent,
            detected = detected,
            aligned = aligned,
        )
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

private fun DrawScope.drawSearchReticle(
    mode: ReferenceMode,
    textMeasurer: TextMeasurer,
) {
    val inset = 10.dp.toPx()
    val rect = Rect(
        left = inset,
        top = inset,
        right = size.width - inset,
        bottom = size.height - inset,
    )
    val dash = PathEffect.dashPathEffect(
        floatArrayOf(6.dp.toPx(), 10.dp.toPx()),
        0f,
    )
    drawRoundRect(
        color = WorkshopPalette.Amber.copy(alpha = 0.34f),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
    )
    val label = if (mode == ReferenceMode.CARD) {
        "SEARCHING · ID / CREDIT CARD"
    } else {
        "SEARCHING · A4 PAPER SHEET"
    }
    drawText(
        textMeasurer,
        label,
        topLeft = Offset(
            (size.width - textMeasurer.measure(label, TextStyle(fontSize = 9.sp)).size.width) / 2f,
            22.dp.toPx(),
        ),
        style = TextStyle(
            color = WorkshopPalette.Amber.copy(alpha = 0.9f),
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.9.sp,
        ),
    )
}

private fun DrawScope.drawStabilityRing(
    textMeasurer: TextMeasurer,
    progress: Float,
    accent: Color,
    detected: Boolean,
    aligned: Boolean,
) {
    val radius = 20.dp.toPx()
    val center = Offset(size.width / 2f, size.height - 38.dp.toPx())
    drawCircle(
        color = WorkshopPalette.Background.copy(alpha = 0.82f),
        radius = radius + 7.dp.toPx(),
        center = center,
    )
    drawCircle(
        color = WorkshopPalette.Border,
        radius = radius,
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
    if (progress > 0f) {
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
    drawCircle(
        color = accent,
        radius = 4.dp.toPx(),
        center = center,
    )

    val status = when {
        !detected -> "SEARCHING"
        !aligned -> "ALIGN REFERENCE"
        progress >= 1f -> "CAPTURING"
        progress > 0f -> "HOLD STEADY ${(progress * 5).toInt().coerceIn(1, 5)}/5"
        else -> "HOLD STEADY"
    }
    drawText(
        textMeasurer,
        status,
        topLeft = Offset(center.x + radius + 12.dp.toPx(), center.y - 6.dp.toPx()),
        style = TextStyle(
            color = accent,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
        ),
    )
}
