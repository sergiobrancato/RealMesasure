package com.realmeasure.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.ScanMode
import com.realmeasure.app.model.UnitSystem
import com.realmeasure.app.ui.theme.Mono

/**
 * Small shared controls used by both the scanner and the result screen:
 * pill toggles (mode / unit), section labels, info chips and thin progress
 * bars. Keeping them here stops the screens from drifting apart visually.
 */

// ---------------------------------------------------------------------------
// Pills / toggles
// ---------------------------------------------------------------------------

@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.14f) else WorkshopPalette.Sunken,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.45f) else WorkshopPalette.Border),
    ) {
        Text(
            text = text,
            color = if (selected) accent else WorkshopPalette.TextMuted,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** mm / inch switch. */
@Composable
fun UnitToggle(unit: UnitSystem, onChange: (UnitSystem) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("MM", unit == UnitSystem.MM, WorkshopPalette.Cyan) { onChange(UnitSystem.MM) }
        Pill("IN", unit == UnitSystem.INCH, WorkshopPalette.Cyan) { onChange(UnitSystem.INCH) }
    }
}

/** Screw/Bolt vs Hex Key switch. */
@Composable
fun ModeToggle(mode: ScanMode, onChange: (ScanMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(
            "SCREW / BOLT",
            mode == ScanMode.SCREW_BOLT,
            WorkshopPalette.Amber,
        ) { onChange(ScanMode.SCREW_BOLT) }
        Pill(
            "HEX KEY",
            mode == ScanMode.HEX_KEY,
            WorkshopPalette.Amber,
        ) { onChange(ScanMode.HEX_KEY) }
    }
}

// ---------------------------------------------------------------------------
// Labels & chips
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

/** Small rounded info chip (thread options, badges). */
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

/** Thin horizontal progress/score bar for a 0..1 value. */
@Composable
fun ScoreBar(value: Double, accent: Color, modifier: Modifier = Modifier) {
    val clamped = value.toFloat().coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(WorkshopPalette.Sunken),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}
