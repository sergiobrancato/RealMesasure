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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.ReferenceMode
import com.realmeasure.app.ui.theme.Mono

/**
 * First-run tutorial, also reopenable from the `?` button. The diagram adapts
 * to the selected reference mode and shows the true relative scale of an ID
 * card or A4 sheet beside an irregular bracket with two holes.
 */
@Composable
fun OnboardingModal(
    mode: ReferenceMode,
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()
    val referenceName = if (mode == ReferenceMode.CARD) "ID card" else "A4 sheet"

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
                .background(WorkshopPalette.Background.copy(alpha = 0.82f))
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = WorkshopPalette.Surface,
                border = BorderStroke(1.dp, WorkshopPalette.Border),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 820.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
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
                        text = "Flatten any shape into CAD",
                        color = WorkshopPalette.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        letterSpacing = (-0.3).sp,
                    )
                    Text(
                        text = "Choose a reference that matches the object size. Lay the $referenceName " +
                            "flat beside the object on a contrasting matte surface. The camera tracks the " +
                            "reference live and automatically captures a full-resolution photo when it is " +
                            "stable — there is no shutter button.",
                        color = WorkshopPalette.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(228.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WorkshopPalette.Sunken),
                    ) {
                        drawOnboardingDiagram(mode, textMeasurer)
                    }

                    TipRow(
                        number = "1",
                        title = "Use a contrasting matte surface",
                        body = "A dark object on light paper — or a light object on a dark mat — gives the " +
                            "cleanest contour and avoids glare being mistaken for an edge.",
                    )
                    TipRow(
                        number = "2",
                        title = "Keep the reference flat and fully visible",
                        body = "All four edges must be in frame. Smooth the sheet or card, avoid reflections " +
                            "and keep a small gap between the reference and the object.",
                    )
                    TipRow(
                        number = "3",
                        title = "Hold the phone parallel to the table",
                        body = "The live tilt gate needs corners within " +
                            "${AppConfig.Detection.MAX_LIVE_TILT_DEG.toInt()}° of square. The app " +
                            "captures automatically after five stable frames, then flattens the whole scene.",
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
                            fontSize = 11.sp,
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

private fun DrawScope.drawOnboardingDiagram(
    mode: ReferenceMode,
    textMeasurer: TextMeasurer,
) {
    val emerald = WorkshopPalette.Emerald
    val cyan = WorkshopPalette.Cyan
    val amber = WorkshopPalette.Amber
    val matte = Color(0xFF1B2028)

    drawRoundRect(
        color = matte,
        topLeft = Offset.Zero,
        size = size,
        cornerRadius = CornerRadius(14f, 14f),
    )
    // Subtle workbench grid.
    for (index in 1..6) {
        val y = size.height * index / 7f
        drawLine(
            color = WorkshopPalette.Border.copy(alpha = 0.45f),
            start = Offset(12f, y),
            end = Offset(size.width - 12f, y),
            strokeWidth = 1f,
        )
    }

    if (mode == ReferenceMode.CARD) {
        drawCardReference(textMeasurer, emerald, amber)
        drawBracketObject(
            textMeasurer = textMeasurer,
            left = size.width * 0.58f,
            top = size.height * 0.20f,
            width = size.width * 0.32f,
            height = size.height * 0.60f,
            accent = cyan,
        )
    } else {
        drawA4Reference(textMeasurer, emerald, amber)
        drawBracketObject(
            textMeasurer = textMeasurer,
            left = size.width * 0.68f,
            top = size.height * 0.20f,
            width = size.width * 0.26f,
            height = size.height * 0.60f,
            accent = cyan,
        )
    }

    drawText(
        textMeasurer,
        "CONTRASTING MATTE BACKGROUND",
        topLeft = Offset(size.width * 0.05f, size.height - 18f),
        style = TextStyle(
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 7.sp,
            letterSpacing = 1.0.sp,
        ),
    )
}

private fun DrawScope.drawCardReference(
    textMeasurer: TextMeasurer,
    emerald: Color,
    amber: Color,
) {
    val width = size.width * 0.34f
    val height = width / 1.5858f
    val left = size.width * 0.08f
    val top = (size.height - height) / 2f

    drawRoundRect(
        color = emerald.copy(alpha = 0.08f),
        topLeft = Offset(left - 8f, top - 8f),
        size = Size(width + 16f, height + 16f),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = emerald,
        topLeft = Offset(left - 8f, top - 8f),
        size = Size(width + 16f, height + 16f),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(
            width = 1.8f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f),
        ),
    )
    drawRoundRect(
        color = Color(0xFFE8EAEF),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(6f, 6f),
    )
    drawRoundRect(
        color = amber,
        topLeft = Offset(left + width * 0.07f, top + height * 0.28f),
        size = Size(width * 0.16f, height * 0.24f),
        cornerRadius = CornerRadius(2f, 2f),
    )
    drawRect(
        color = Color(0xFF374151),
        topLeft = Offset(left + width * 0.07f, top + height * 0.66f),
        size = Size(width * 0.50f, height * 0.06f),
    )
    drawRect(
        color = Color(0xFF9CA3AF),
        topLeft = Offset(left + width * 0.07f, top + height * 0.78f),
        size = Size(width * 0.34f, height * 0.05f),
    )
    drawCircle(
        color = amber.copy(alpha = 0.6f),
        radius = height * 0.10f,
        center = Offset(left + width * 0.82f, top + height * 0.30f),
        style = Stroke(width = 1.5f),
    )
    drawCircle(
        color = Color(0xFF38BDF8).copy(alpha = 0.7f),
        radius = height * 0.10f,
        center = Offset(left + width * 0.72f, top + height * 0.30f),
        style = Stroke(width = 1.5f),
    )

    drawText(
        textMeasurer,
        "ID CARD · REFERENCE",
        topLeft = Offset(left - 8f, top - 22f),
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
        "85.60 × 53.98 mm",
        topLeft = Offset(left - 8f, top + height + 12f),
        style = TextStyle(
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 8.sp,
        ),
    )
}

private fun DrawScope.drawA4Reference(
    textMeasurer: TextMeasurer,
    emerald: Color,
    amber: Color,
) {
    val width = size.width * 0.53f
    val height = width / 1.41429f
    val left = size.width * 0.06f
    val top = (size.height - height) / 2f

    drawRoundRect(
        color = emerald.copy(alpha = 0.08f),
        topLeft = Offset(left - 7f, top - 7f),
        size = Size(width + 14f, height + 14f),
        cornerRadius = CornerRadius(9f, 9f),
    )
    drawRoundRect(
        color = emerald,
        topLeft = Offset(left - 7f, top - 7f),
        size = Size(width + 14f, height + 14f),
        cornerRadius = CornerRadius(9f, 9f),
        style = Stroke(
            width = 1.8f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f),
        ),
    )
    drawRoundRect(
        color = Color(0xFFF1F3F6),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(3f, 3f),
    )
    // Printed header + ruler lines to read as an A4 sheet.
    drawRect(
        color = amber.copy(alpha = 0.85f),
        topLeft = Offset(left + width * 0.08f, top + height * 0.10f),
        size = Size(width * 0.36f, height * 0.035f),
    )
    for (index in 0..5) {
        val y = top + height * (0.22f + index * 0.08f)
        drawLine(
            color = Color(0xFF64748B),
            start = Offset(left + width * 0.08f, y),
            end = Offset(left + width * (0.50f + (index % 3) * 0.10f), y),
            strokeWidth = 1.2f,
        )
    }
    drawCircle(
        color = Color(0xFF94A3B8),
        radius = width * 0.05f,
        center = Offset(left + width * 0.84f, top + height * 0.25f),
        style = Stroke(width = 1.2f),
    )

    drawText(
        textMeasurer,
        "A4 SHEET · REFERENCE",
        topLeft = Offset(left - 7f, top - 22f),
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
        "297 × 210 mm",
        topLeft = Offset(left - 7f, top + height + 12f),
        style = TextStyle(
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 8.sp,
        ),
    )
}

private fun DrawScope.drawBracketObject(
    textMeasurer: TextMeasurer,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    accent: Color,
) {
    val right = left + width
    val bottom = top + height
    val path = Path().apply {
        moveTo(left, top + height * 0.20f)
        lineTo(left + width * 0.58f, top + height * 0.20f)
        lineTo(left + width * 0.58f, top)
        lineTo(right, top)
        lineTo(right, top + height * 0.52f)
        lineTo(left + width * 0.78f, top + height * 0.52f)
        lineTo(left + width * 0.78f, bottom)
        lineTo(left, bottom)
        close()
    }
    drawPath(path, color = accent.copy(alpha = 0.07f))
    drawPath(
        path,
        color = accent,
        style = Stroke(width = 2f, cap = StrokeCap.Round),
    )

    data class Hole(val x: Float, val y: Float, val r: Float)
    val holes = listOf(
        Hole(left + width * 0.22f, top + height * 0.36f, width * 0.075f),
        Hole(left + width * 0.22f, top + height * 0.76f, width * 0.075f),
        Hole(right - width * 0.16f, top + height * 0.25f, width * 0.065f),
    )
    holes.forEach { hole ->
        drawCircle(color = WorkshopPalette.Sunken, radius = hole.r, center = Offset(hole.x, hole.y))
        drawCircle(
            color = WorkshopPalette.Amber,
            radius = hole.r,
            center = Offset(hole.x, hole.y),
            style = Stroke(width = 1.4f),
        )
        drawLine(
            color = WorkshopPalette.Amber,
            start = Offset(hole.x - hole.r * 1.7f, hole.y),
            end = Offset(hole.x + hole.r * 1.7f, hole.y),
            strokeWidth = 1f,
        )
        drawLine(
            color = WorkshopPalette.Amber,
            start = Offset(hole.x, hole.y - hole.r * 1.7f),
            end = Offset(hole.x, hole.y + hole.r * 1.7f),
            strokeWidth = 1f,
        )
    }
    // Dashed centre-to-centre callout.
    drawLine(
        color = WorkshopPalette.Amber.copy(alpha = 0.75f),
        start = Offset(holes[0].x, holes[0].y),
        end = Offset(holes[1].x, holes[1].y),
        strokeWidth = 1.2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f),
    )
    drawText(
        textMeasurer,
        "24.0 mm",
        topLeft = Offset(holes[0].x + 8f, (holes[0].y + holes[1].y) / 2f - 4f),
        style = TextStyle(
            color = WorkshopPalette.Amber,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 7.sp,
        ),
    )

    drawText(
        textMeasurer,
        "IRREGULAR OBJECT",
        topLeft = Offset(left, top - 22f),
        style = TextStyle(
            color = accent,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
        ),
    )
}
