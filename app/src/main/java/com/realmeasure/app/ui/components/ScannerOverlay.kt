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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.ui.theme.Mono

/**
 * Hands-free viewfinder HUD drawn with the native Compose Canvas.
 *
 * Both alignment frames start **amber** while searching. Each frame switches
 * independently to **emerald green** the moment the live detector reports a
 * valid card / fastener inside it. When both are green, the bottom chip turns
 * into a stability progress bar - once it fills, the app captures
 * automatically (there is no shutter button).
 */
@Composable
fun ScannerOverlay(
    cardDetected: Boolean,
    fastenerDetected: Boolean,
    stabilityProgress: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val guides = AppConfig.Guides
    val cardColor = if (cardDetected) WorkshopPalette.Emerald else WorkshopPalette.Amber
    val partColor = if (fastenerDetected) WorkshopPalette.Emerald else WorkshopPalette.Amber
    val bothAligned = cardDetected && fastenerDetected

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Slight dim so the guides and colours read clearly over any scene.
        drawRect(color = WorkshopPalette.Background.copy(alpha = 0.20f))

        // ---------------------------------------------------------------
        // Left zone - ISO/IEC 7810 ID-1 card frame
        // ---------------------------------------------------------------
        val card = Rect(
            left = (guides.CARD_X * width).toFloat(),
            top = (guides.CARD_Y * height).toFloat(),
            right = ((guides.CARD_X + guides.CARD_W) * width).toFloat(),
            bottom = ((guides.CARD_Y + guides.CARD_H) * height).toFloat(),
        )
        drawSearchFrame(
            rect = card,
            color = cardColor,
            textMeasurer = textMeasurer,
            label = if (cardDetected) "BANK / ID CARD - OK" else "ALIGN BANK / ID CARD",
            meta = "85.60 x 53.98 mm",
        )

        // ---------------------------------------------------------------
        // Right zone - fastener target frame
        // ---------------------------------------------------------------
        val target = Rect(
            left = (guides.TARGET_X * width).toFloat(),
            top = (guides.TARGET_Y * height).toFloat(),
            right = ((guides.TARGET_X + guides.TARGET_W) * width).toFloat(),
            bottom = ((guides.TARGET_Y + guides.TARGET_H) * height).toFloat(),
        )
        drawSearchFrame(
            rect = target,
            color = partColor,
            textMeasurer = textMeasurer,
            label = if (fastenerDetected) "FASTENER - OK" else "PLACE FASTENER",
            meta = "screw / bolt / hex key",
            drawRuler = true,
            drawCenterDot = true,
        )

        // ---------------------------------------------------------------
        // Bottom status chip + stability progress
        // ---------------------------------------------------------------
        val chipWidth = minOf(width * 0.72f, 260.dp.toPx())
        val chipHeight = 40.dp.toPx()
        val chipLeft = (width - chipWidth) / 2f
        val chipTop = height - chipHeight - 16.dp.toPx()
        drawRoundRect(
            color = WorkshopPalette.Background.copy(alpha = 0.86f),
            topLeft = Offset(chipLeft, chipTop),
            size = Size(chipWidth, chipHeight),
            cornerRadius = CornerRadius(chipHeight / 2f, chipHeight / 2f),
        )

        val accent = if (bothAligned) WorkshopPalette.Emerald else WorkshopPalette.Amber
        drawCircle(
            color = accent,
            radius = 3.dp.toPx(),
            center = Offset(chipLeft + 14.dp.toPx(), chipTop + chipHeight * 0.34f),
        )
        drawText(
            textMeasurer,
            if (bothAligned) "ALIGNED - HOLD STEADY" else "AUTO-SCANNING",
            topLeft = Offset(chipLeft + 22.dp.toPx(), chipTop + 6.dp.toPx()),
            style = TextStyle(
                color = accent,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.0.sp,
            ),
        )

        // Progress track + fill (fills over ~750 ms).
        val trackLeft = chipLeft + 12.dp.toPx()
        val trackTop = chipTop + chipHeight - 10.dp.toPx()
        val trackWidth = chipWidth - 24.dp.toPx()
        val progress = stabilityProgress.coerceIn(0f, 1f)
        drawRoundRect(
            color = WorkshopPalette.Border,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, 3.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        if (progress > 0f) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(trackLeft, trackTop),
                size = Size(trackWidth * progress, 3.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

private fun DrawScope.drawSearchFrame(
    rect: Rect,
    color: Color,
    textMeasurer: TextMeasurer,
    label: String,
    meta: String,
    drawRuler: Boolean = false,
    drawCenterDot: Boolean = false,
) {
    val stroke = 1.5.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
        style = Stroke(width = stroke, pathEffect = dash),
    )
    drawRoundRect(
        color = color.copy(alpha = 0.20f),
        topLeft = Offset(rect.left + 6.dp.toPx(), rect.top + 6.dp.toPx()),
        size = Size(
            (rect.width - 12.dp.toPx()).coerceAtLeast(1f),
            (rect.height - 12.dp.toPx()).coerceAtLeast(1f),
        ),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        style = Stroke(width = 1f),
    )
    drawCornerBrackets(rect, color, stroke * 1.6f, 16.dp.toPx())

    if (drawCenterDot) {
        drawCircle(
            color = color.copy(alpha = 0.5f),
            radius = 7.dp.toPx(),
            center = rect.center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    if (drawRuler) {
        val rulerWidth = minOf(rect.width * 0.16f, 16.dp.toPx())
        for (index in 0..10) {
            val y = rect.top + (rect.height / 10f) * index
            val major = index % 5 == 0
            drawLine(
                color = color.copy(alpha = if (major) 0.55f else 0.30f),
                start = Offset(rect.left + 6.dp.toPx(), y),
                end = Offset(
                    rect.left + if (major) rulerWidth else rulerWidth * 0.62f,
                    y,
                ),
                strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
            )
        }
    }

    val labelStyle = TextStyle(
        color = color,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 0.8.sp,
    )
    val metaStyle = TextStyle(
        color = WorkshopPalette.TextMuted,
        fontFamily = Mono,
        fontSize = 8.sp,
    )
    // Stack the two lines above the frame (label on top, meta below it) with
    // enough room for the actual font metrics at any density.
    drawText(
        textMeasurer = textMeasurer,
        text = label,
        topLeft = Offset(rect.left, (rect.top - 27.dp.toPx()).coerceAtLeast(2.dp.toPx())),
        style = labelStyle,
    )
    drawText(
        textMeasurer = textMeasurer,
        text = meta,
        topLeft = Offset(rect.left, (rect.top - 14.dp.toPx()).coerceAtLeast(2.dp.toPx())),
        style = metaStyle,
    )
}

/** Four L-shaped corner marks for crisp edges. */
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
