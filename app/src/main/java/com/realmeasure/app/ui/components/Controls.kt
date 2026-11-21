package com.realmeasure.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.model.ReferenceMode
import com.realmeasure.app.ui.theme.Mono

// ---------------------------------------------------------------------------
// Reference mode selector
// ---------------------------------------------------------------------------

/**
 * Two-card segmented control for the physical reference anchor. The selected
 * card is the only mode the live detector and still calibrator will accept.
 */
@Composable
fun ReferenceModeSelector(
    mode: ReferenceMode,
    onChange: (ReferenceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModeCard(
            mode = ReferenceMode.CARD,
            title = "SMALL · ID CARD",
            subtitle = "85.60 × 53.98 mm",
            hint = "under 250 mm",
            selected = mode == ReferenceMode.CARD,
            onClick = { onChange(ReferenceMode.CARD) },
            modifier = Modifier.weight(1f),
        )
        ModeCard(
            mode = ReferenceMode.A4,
            title = "LARGE · A4 SHEET",
            subtitle = "297 × 210 mm",
            hint = "250–2000 mm",
            selected = mode == ReferenceMode.A4,
            onClick = { onChange(ReferenceMode.A4) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeCard(
    mode: ReferenceMode,
    title: String,
    subtitle: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            WorkshopPalette.Amber.copy(alpha = 0.12f)
        } else {
            WorkshopPalette.Surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) WorkshopPalette.Amber.copy(alpha = 0.65f) else WorkshopPalette.Border,
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (selected) WorkshopPalette.Emerald else WorkshopPalette.TextFaint,
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    text = title,
                    color = if (selected) WorkshopPalette.Amber else WorkshopPalette.TextSecondary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                )
            }
            Text(
                text = subtitle,
                color = WorkshopPalette.TextMuted,
                fontFamily = Mono,
                fontSize = 9.sp,
            )
            Text(
                text = hint,
                color = if (selected) WorkshopPalette.TextSecondary else WorkshopPalette.TextFaint,
                fontSize = 9.sp,
            )
            if (mode == ReferenceMode.CARD) {
                Text(
                    text = "high precision",
                    color = WorkshopPalette.Emerald.copy(alpha = 0.85f),
                    fontFamily = Mono,
                    fontSize = 8.sp,
                )
            } else {
                Text(
                    text = "large format",
                    color = WorkshopPalette.Cyan.copy(alpha = 0.85f),
                    fontFamily = Mono,
                    fontSize = 8.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Labels, chips, quality
// ---------------------------------------------------------------------------

/** Uppercase mono micro-label. */
@Composable
fun SectionLabel(text: String, accent: Color = WorkshopPalette.TextMuted) {
    Text(
        text = text,
        color = accent,
        fontFamily = Mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}

/** Small rounded info chip. */
@Composable
fun InfoChip(text: String, accent: Color = WorkshopPalette.TextSecondary) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = WorkshopPalette.Sunken,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
    ) {
        Text(
            text = text,
            color = accent,
            fontFamily = Mono,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun QualityBadge(
    quality: MeasurementQuality,
    recommendation: String?,
    modifier: Modifier = Modifier,
) {
    val accent = when (quality) {
        MeasurementQuality.HIGH -> WorkshopPalette.Emerald
        MeasurementQuality.MODERATE -> WorkshopPalette.Amber
        MeasurementQuality.LOW -> WorkshopPalette.Danger
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = accent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(accent, CircleShape),
                )
                Text(
                    text = quality.label.uppercase(),
                    color = accent,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.0.sp,
                )
            }
        }
        if (recommendation != null) {
            Text(
                text = recommendation,
                color = WorkshopPalette.Amber,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Metric rows + actions
// ---------------------------------------------------------------------------

@Composable
fun MetricRow(
    label: String,
    value: String,
    accent: Color = WorkshopPalette.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = WorkshopPalette.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = accent,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = WorkshopPalette.Amber,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (enabled) color else WorkshopPalette.SurfaceRaised,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = if (enabled) WorkshopPalette.Background else WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        )
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = WorkshopPalette.TextSecondary,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = if (enabled) accent else WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.0.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    }
}
