package com.realmeasure.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.realmeasure.app.config.WorkshopPalette

private val WorkshopColors = darkColorScheme(
    primary = WorkshopPalette.Amber,
    onPrimary = WorkshopPalette.Background,
    secondary = WorkshopPalette.Cyan,
    onSecondary = WorkshopPalette.Background,
    background = WorkshopPalette.Background,
    onBackground = WorkshopPalette.TextPrimary,
    surface = WorkshopPalette.Surface,
    onSurface = WorkshopPalette.TextPrimary,
    surfaceVariant = WorkshopPalette.SurfaceRaised,
    onSurfaceVariant = WorkshopPalette.TextSecondary,
    error = WorkshopPalette.Danger,
    outline = WorkshopPalette.Border,
)

/** Monospace family used for every numeric readout in the app. */
val Mono: FontFamily = FontFamily.Monospace

/**
 * Precision dark-workshop theme.
 *
 * RealMeasure is a measurement tool, so the palette is intentionally always
 * dark regardless of the system setting: charcoal backgrounds keep the
 * viewfinder neutral and the amber/cyan guides high-contrast.
 */
@Composable
fun RealMeasureTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() is read so future light variants remain possible,
    // but the workshop palette is fixed to dark for calibration consistency.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = WorkshopColors,
        typography = Typography(),
        content = content,
    )
}
