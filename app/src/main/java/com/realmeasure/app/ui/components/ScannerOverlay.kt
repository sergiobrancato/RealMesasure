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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.ui.theme.Mono

/**
 * Viewfinder HUD drawn with the native Compose Canvas.
 *
 * The amber frame mirrors the ISO/IEC 7810 ID-1 aspect ratio (85.60 : 53.98).
 * The cyan target zone is intentionally tall: it must accept M16 bolts and
 * long hex keys. Geometry comes straight from [AppConfig.Guides] so the
 * overlay always matches what the analyzers crop.
 */
@Composable
fun ScannerOverlay(modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val guides = AppConfig.Guides

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Dim the whole stage slightly so the guides pop.
        drawRect(color = WorkshopPalette.Background.copy(alpha = 0.22f))

        // ---------------------------------------------------------------
        // Card alignment frame (amber, true ID-1 aspect ratio)
        // ---------------------------------------------------------------
        val card = Rect(
            left = (guides.CARD_X * width).toFloat(),
            top = (guides.CARD_Y * height).toFloat(),
            right = ((guides.CARD_X + guides.CARD_W) * width).toFloat(),
            bottom = ((guides.CARD_Y + guides.CARD_H) * height).toFloat(),
        )
        val amberDash = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))

        drawRoundRect(
            color = WorkshopPalette.Amber,
            topLeft = Offset(card.left, card.top),
            size = Size(card.width, card.height),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(width = 2.5f, pathEffect = amberDash),
        )
        drawRoundRect(
            color = WorkshopPalette.Amber.copy(alpha = 0.35f),
            topLeft = Offset(card.left + 7f, card.top + 7f),
            size = Size(card.width.coerceAtLeast(14f) - 14f, card.height.coerceAtLeast(14f) - 14f),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 1f),
        )
        drawCornerBrackets(card, WorkshopPalette.Amber, 4f, minOf(26f, card.width * 0.14f))

        drawLine(
            color = WorkshopPalette.Amber.copy(alpha = 0.18f),
            start = Offset(card.center.x, card.top + 10f),
            end = Offset(card.center.x, card.bottom - 10f),
            strokeWidth = 1f,
        )
        drawLine(
            color = WorkshopPalette.Amber.copy(alpha = 0.18f),
            start = Offset(card.left + 10f, card.center.y),
            end = Offset(card.right - 10f, card.center.y),
            strokeWidth = 1f,
        )
        listOf(
            Offset(card.left, card.top),
            Offset(card.right, card.top),
            Offset(card.right, card.bottom),
            Offset(card.left, card.bottom),
        ).forEach { corner ->
            drawCircle(color = WorkshopPalette.Amber, radius = 4f, center = corner)
        }

        drawText(
            textMeasurer,
            "ALIGN BANK / ID CARD",
            topLeft = Offset(card.left, (card.top - 38f).coerceAtLeast(4f)),
            style = TextStyle(
                color = WorkshopPalette.Amber,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp,
            ),
        )
        drawText(
            textMeasurer,
            "85.60 x 53.98 mm",
            topLeft = Offset(card.right - 140f, (card.top - 22f).coerceAtLeast(4f)),
            style = TextStyle(
                color = WorkshopPalette.TextMuted,
                fontFamily = Mono,
                fontSize = 9.sp,
            ),
        )

        // ---------------------------------------------------------------
        // Fastener target zone (cyan)
        // ---------------------------------------------------------------
        val target = Rect(
            left = (guides.TARGET_X * width).toFloat(),
            top = (guides.TARGET_Y * height).toFloat(),
            right = ((guides.TARGET_X + guides.TARGET_W) * width).toFloat(),
            bottom = ((guides.TARGET_Y + guides.TARGET_H) * height).toFloat(),
        )
        val cyanDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

        drawRoundRect(
            color = WorkshopPalette.Cyan,
            topLeft = Offset(target.left, target.top),
            size = Size(target.width, target.height),
            cornerRadius = CornerRadius(18f, 18f),
            style = Stroke(width = 2f, pathEffect = cyanDash),
        )
        drawRoundRect(
            color = WorkshopPalette.Cyan.copy(alpha = 0.18f),
            topLeft = Offset(target.left + 6f, target.top + 6f),
            size = Size(target.width.coerceAtLeast(12f) - 12f, target.height.coerceAtLeast(12f) - 12f),
            cornerRadius = CornerRadius(14f, 14f),
            style = Stroke(width = 1f),
        )
        drawCornerBrackets(target, WorkshopPalette.Cyan, 3f, minOf(24f, target.width * 0.24f))

        drawLine(
            color = WorkshopPalette.Cyan.copy(alpha = 0.22f),
            start = Offset(target.center.x, target.top + 24f),
            end = Offset(target.center.x, target.bottom - 24f),
            strokeWidth = 1f,
        )
        drawLine(
            color = WorkshopPalette.Cyan.copy(alpha = 0.22f),
            start = Offset(target.left + 18f, target.center.y),
            end = Offset(target.right - 18f, target.center.y),
            strokeWidth = 1f,
        )
        drawCircle(
            color = WorkshopPalette.Cyan.copy(alpha = 0.45f),
            radius = 9f,
            center = target.center,
            style = Stroke(width = 1.5f),
        )

        // Ruler ticks along the target zone's left edge.
        for (index in 0..10) {
            val y = target.top + (target.height / 10f) * index
            val major = index % 5 == 0
            drawLine(
                color = WorkshopPalette.Cyan.copy(alpha = if (major) 0.55f else 0.32f),
                start = Offset(target.left + 8f, y),
                end = Offset(target.left + (if (major) 22f else 15f), y),
                strokeWidth = if (major) 2f else 1f,
            )
        }

        drawText(
            textMeasurer,
            "TARGET ZONE",
            topLeft = Offset(target.left, (target.top - 38f).coerceAtLeast(4f)),
            style = TextStyle(
                color = WorkshopPalette.Cyan,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp,
            ),
        )
        drawText(
            textMeasurer,
            "PLACE SCREW / BOLT / HEX KEY",
            topLeft = Offset(target.left, (target.top - 22f).coerceAtLeast(4f)),
            style = TextStyle(
                color = WorkshopPalette.TextSecondary,
                fontFamily = Mono,
                fontSize = 9.sp,
            ),
        )

        // ---------------------------------------------------------------
        // Bottom status chip
        // ---------------------------------------------------------------
        val chipWidth = 214f
        val chipLeft = (width - chipWidth) / 2f
        val chipTop = height - 40f
        drawRoundRect(
            color = WorkshopPalette.Background.copy(alpha = 0.82f),
            topLeft = Offset(chipLeft, chipTop),
            size = Size(chipWidth, 26f),
            cornerRadius = CornerRadius(13f, 13f),
        )
        drawLine(
            color = WorkshopPalette.Amber,
            start = Offset(chipLeft + 18f, chipTop + 13f),
            end = Offset(chipLeft + 78f, chipTop + 13f),
            strokeWidth = 3f,
        )
        drawCircle(
            color = WorkshopPalette.Amber,
            radius = 3.5f,
            center = Offset(chipLeft + 12f, chipTop + 13f),
        )
        drawText(
            textMeasurer,
            "HOLD STEADY - TAP TO CAPTURE",
            topLeft = Offset(chipLeft + 88f, chipTop + 6f),
            style = TextStyle(
                color = WorkshopPalette.Amber,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            ),
        )
    }
}

/** Four L-shaped corner marks drawn with plain lines for crisp edges. */
private fun DrawScope.drawCornerBrackets(
    rect: Rect,
    color: Color,
    strokeWidth: Float,
    length: Float,
) {
    val pairs = listOf(
        Offset(rect.left, rect.top) to Offset(rect.left + length, rect.top),
        Offset(rect.left, rect.top) to Offset(rect.left, rect.top + length),
        Offset(rect.right, rect.top) to Offset(rect.right - length, rect.top),
        Offset(rect.right, rect.top) to Offset(rect.right, rect.top + length),
        Offset(rect.left, rect.bottom) to Offset(rect.left + length, rect.bottom),
        Offset(rect.left, rect.bottom) to Offset(rect.left, rect.bottom - length),
        Offset(rect.right, rect.bottom) to Offset(rect.right - length, rect.bottom),
        Offset(rect.right, rect.bottom) to Offset(rect.right, rect.bottom - length),
    )
    pairs.forEach { (start, end) ->
        drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth)
    }
}
