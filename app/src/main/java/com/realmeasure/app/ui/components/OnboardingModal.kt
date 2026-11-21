package com.realmeasure.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.ui.theme.Mono

/**
 * First-run "How to Scan" tutorial, also reopenable from the `?` button.
 *
 * Contains a custom Canvas diagram (top-down matte table, card in the left
 * green frame, bolt in the right green frame), three plain-language tips and
 * the "Don't show again" persistence choice.
 */
@Composable
fun OnboardingModal(onDismiss: (dontShowAgain: Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()

    Dialog(
        onDismissRequest = { onDismiss(false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WorkshopPalette.Background.copy(alpha = 0.80f))
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = WorkshopPalette.Surface,
                border = BorderStroke(1.dp, WorkshopPalette.Border),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 800.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel("HOW TO SCAN", WorkshopPalette.Amber)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDismiss(false) }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close tutorial",
                                tint = WorkshopPalette.TextMuted,
                            )
                        }
                    }

                    Text(
                        text = "Hands-free measuring",
                        color = WorkshopPalette.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        letterSpacing = (-0.3).sp,
                    )
                    Text(
                        text = "Lay everything on a flat surface and let the camera do the work. There is no shutter button - once both frames turn green, RealMeasure captures and measures automatically.",
                        color = WorkshopPalette.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WorkshopPalette.Sunken),
                    ) {
                        drawOnboardingDiagram(textMeasurer)
                    }

                    TipRow(
                        number = "1",
                        title = "Use a plain, matte surface",
                        body = "A dark desk or a sheet of paper beats a reflective or cluttered workbench.",
                    )
                    TipRow(
                        number = "2",
                        title = "Hold the phone flat and parallel",
                        body = "Keep the camera parallel to the table so the card stays a true rectangle.",
                    )
                    TipRow(
                        number = "3",
                        title = "Keep the fastener inside the right box",
                        body = "Head and tip fully visible. Both frames turn green when the scene is aligned.",
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = WorkshopPalette.Amber,
                                uncheckedColor = WorkshopPalette.TextMuted,
                                checkmarkColor = WorkshopPalette.Background,
                            ),
                        )
                        Text(
                            text = "Don't show again",
                            color = WorkshopPalette.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }

                    Surface(
                        onClick = { onDismiss(dontShowAgain) },
                        shape = RoundedCornerShape(50),
                        color = WorkshopPalette.Amber,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "GOT IT, START SCANNING",
                            color = WorkshopPalette.Background,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.1.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 15.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tips
// ---------------------------------------------------------------------------

@Composable
private fun TipRow(number: String, title: String, body: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = WorkshopPalette.Amber.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, WorkshopPalette.Amber.copy(alpha = 0.4f)),
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    color = WorkshopPalette.Amber,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                color = WorkshopPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = body,
                color = WorkshopPalette.TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas diagram
// ---------------------------------------------------------------------------

private fun DrawScope.drawOnboardingDiagram(textMeasurer: TextMeasurer) {
    val emerald = WorkshopPalette.Emerald
    val cardWhite = Color(0xFFE8EAEF)
    val cyan = WorkshopPalette.Cyan

    // Matte table surface.
    drawRoundRect(
        color = Color(0xFF1B2028),
        topLeft = Offset.Zero,
        size = size,
        cornerRadius = CornerRadius(14f, 14f),
    )
    for (index in 1..5) {
        val y = size.height * index / 6f
        drawLine(
            color = WorkshopPalette.Border.copy(alpha = 0.5f),
            start = Offset(12f, y),
            end = Offset(size.width - 12f, y),
            strokeWidth = 1f,
        )
    }

    val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))

    // ---- Left: ID-1 card inside a green guide frame -------------------
    val cardFrame = Rect(
        left = size.width * 0.05f,
        top = size.height * 0.17f,
        right = size.width * 0.47f,
        bottom = size.height * 0.79f,
    )
    drawRoundRect(
        color = emerald,
        topLeft = Offset(cardFrame.left, cardFrame.top),
        size = Size(cardFrame.width, cardFrame.height),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = 2.5f, pathEffect = dash),
    )

    val cardInset = 10f
    val cardWidth = cardFrame.width - cardInset * 2f
    val cardHeight = cardWidth / 1.5858f
    val cardLeft = cardFrame.left + cardInset
    val cardTop = cardFrame.top + (cardFrame.height - cardHeight) / 2f
    drawRoundRect(
        color = cardWhite,
        topLeft = Offset(cardLeft, cardTop),
        size = Size(cardWidth, cardHeight),
        cornerRadius = CornerRadius(7f, 7f),
    )
    // Chip
    drawRoundRect(
        color = WorkshopPalette.Amber,
        topLeft = Offset(cardLeft + cardWidth * 0.07f, cardTop + cardHeight * 0.30f),
        size = Size(cardWidth * 0.16f, cardHeight * 0.22f),
        cornerRadius = CornerRadius(3f, 3f),
    )
    // Number stripes
    drawRect(
        color = Color(0xFF374151),
        topLeft = Offset(cardLeft + cardWidth * 0.07f, cardTop + cardHeight * 0.66f),
        size = Size(cardWidth * 0.52f, cardHeight * 0.07f),
    )
    drawRect(
        color = Color(0xFF9CA3AF),
        topLeft = Offset(cardLeft + cardWidth * 0.07f, cardTop + cardHeight * 0.78f),
        size = Size(cardWidth * 0.36f, cardHeight * 0.05f),
    )

    drawText(
        textMeasurer,
        "ID-1 CARD",
        topLeft = Offset(cardFrame.left, cardFrame.top - 18f),
        style = TextStyle(
            color = emerald,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
        ),
    )
    drawText(
        textMeasurer,
        "85.60 x 53.98 mm",
        topLeft = Offset(cardFrame.left, cardFrame.bottom + 5f),
        style = TextStyle(
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 8.sp,
        ),
    )

    // ---- Right: fastener inside a green guide frame -------------------
    val partFrame = Rect(
        left = size.width * 0.55f,
        top = size.height * 0.10f,
        right = size.width * 0.94f,
        bottom = size.height * 0.90f,
    )
    drawRoundRect(
        color = emerald,
        topLeft = Offset(partFrame.left, partFrame.top),
        size = Size(partFrame.width, partFrame.height),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 2.5f, pathEffect = dash),
    )

    // Bolt: hex head + shaft + thread ticks (vertical).
    val centerX = partFrame.center.x
    val headRadius = minOf(partFrame.width * 0.20f, 26f)
    val headCenterY = partFrame.top + partFrame.height * 0.24f
    val hexPath = Path().apply {
        for (index in 0 until 6) {
            val angle = Math.toRadians(60.0 * index - 30.0)
            val x = centerX + (headRadius * kotlin.math.cos(angle)).toFloat()
            val y = headCenterY + (headRadius * kotlin.math.sin(angle)).toFloat()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(hexPath, color = Color(0xFFC9D2DC))
    drawCircle(
        color = WorkshopPalette.Background,
        radius = headRadius * 0.42f,
        center = Offset(centerX, headCenterY),
    )

    val shaftTop = headCenterY + headRadius * 0.9f
    val shaftBottom = partFrame.bottom - partFrame.height * 0.08f
    val shaftWidth = headRadius * 0.95f
    drawRoundRect(
        color = Color(0xFFB8C1CC),
        topLeft = Offset(centerX - shaftWidth / 2f, shaftTop),
        size = Size(shaftWidth, shaftBottom - shaftTop),
        cornerRadius = CornerRadius(3f, 3f),
    )
    var threadY = shaftTop + 8f
    while (threadY < shaftBottom - 4f) {
        drawLine(
            color = Color(0xFF5C6775),
            start = Offset(centerX - shaftWidth / 2f + 2f, threadY),
            end = Offset(centerX + shaftWidth / 2f - 2f, threadY),
            strokeWidth = 1f,
        )
        threadY += 9f
    }

    drawText(
        textMeasurer,
        "FASTENER",
        topLeft = Offset(partFrame.left, partFrame.top - 18f),
        style = TextStyle(
            color = emerald,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
        ),
    )

    // Small "auto" hint between the zones.
    drawText(
        textMeasurer,
        "AUTO",
        topLeft = Offset(size.width * 0.47f, size.height * 0.46f),
        style = TextStyle(
            color = cyan,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
        ),
    )
    drawLine(
        color = cyan.copy(alpha = 0.6f),
        start = Offset(size.width * 0.48f, size.height * 0.53f),
        end = Offset(size.width * 0.54f, size.height * 0.53f),
        strokeWidth = 1.5f,
    )
}
