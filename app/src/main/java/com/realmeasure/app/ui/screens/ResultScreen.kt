package com.realmeasure.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.cv.StandardMatcher
import com.realmeasure.app.model.CaliperHandles
import com.realmeasure.app.model.HardwareFamily
import com.realmeasure.app.model.HardwareKind
import com.realmeasure.app.model.ScanMode
import com.realmeasure.app.model.StandardMatch
import com.realmeasure.app.model.StandardMatchResult
import com.realmeasure.app.model.UnitSystem
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.components.InfoChip
import com.realmeasure.app.ui.components.ScoreBar
import com.realmeasure.app.ui.components.SectionLabel
import com.realmeasure.app.ui.components.UnitToggle
import com.realmeasure.app.ui.components.InteractiveCaliper
import com.realmeasure.app.ui.components.formatDelta
import com.realmeasure.app.ui.components.formatMeasured
import com.realmeasure.app.ui.theme.Mono

/**
 * Result screen: frozen capture with the draggable caliper, live spec tiles,
 * the closest Metric + Imperial catalogue entries, and full calibration
 * transparency (px/mm, confidence, reprojection error).
 */
@Composable
fun ResultScreen(viewModel: ScannerViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val result = ui.result ?: run {
        // Defensive: state says RESULT but the payload is gone - go back.
        viewModel.resetToScanner()
        return
    }

    var handles by remember(result) { mutableStateOf(result.measurement.handles) }
    val inverseHomography = remember(result.calibration) {
        runCatching { Geometry.invert3x3(result.calibration.homography) }
            .getOrDefault(result.calibration.homography)
    }

    val (liveWidthMm, liveLengthMm) = remember(handles, result.calibration) {
        Geometry.caliperValues(
            handles, result.calibration.homography,
            result.photo.width, result.photo.height,
        )
    }
    val kind = if (result.measurement.mode == ScanMode.HEX_KEY) HardwareKind.HEX_KEY else HardwareKind.FASTENER
    val matches = remember(handles, kind) {
        StandardMatcher.match(kind, liveWidthMm, liveLengthMm)
    }

    BackHandler { viewModel.resetToScanner() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkshopPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // -------------------------------------------------------------
        // Header
        // -------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::resetToScanner) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to scanner",
                    tint = WorkshopPalette.TextSecondary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MEASUREMENT",
                    color = WorkshopPalette.TextPrimary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = "ON-DEVICE WORKSHOP READOUT",
                    color = WorkshopPalette.TextFaint,
                    fontFamily = Mono,
                    fontSize = 7.sp,
                    letterSpacing = 1.4.sp,
                )
            }
            ShareButton(result, liveWidthMm, liveLengthMm)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(
                    text = result.measurement.mode.label.uppercase(),
                    accent = WorkshopPalette.Amber,
                )
                InfoChip(
                    text = if (matches.withinTolerance) "IN TOLERANCE" else "VERIFY",
                    accent = if (matches.withinTolerance) WorkshopPalette.Success else WorkshopPalette.Danger,
                )
            }
            UnitToggle(unit = ui.unit, onChange = viewModel::setUnit)
        }

        Spacer(Modifier.height(14.dp))

        // -------------------------------------------------------------
        // Frozen capture + interactive caliper
        // -------------------------------------------------------------
        Box(Modifier.padding(horizontal = 20.dp)) {
            InteractiveCaliper(
                bitmap = result.photo,
                calibration = result.calibration,
                handles = handles,
                onHandlesChange = { handles = it },
                unit = ui.unit,
            )
        }

        Spacer(Modifier.height(20.dp))

        // -------------------------------------------------------------
        // Measured dimension tiles
        // -------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DimensionTile(
                icon = if (kind == HardwareKind.HEX_KEY) Icons.Filled.Key else Icons.Filled.Construction,
                label = result.measurement.mode.dimensionLabel.uppercase(),
                value = formatMeasured(liveWidthMm, ui.unit),
                footnote = "across shaft",
                accent = WorkshopPalette.Cyan,
                modifier = Modifier.weight(1f),
            )
            DimensionTile(
                icon = Icons.Filled.Construction,
                label = if (kind == HardwareKind.HEX_KEY) "LONG ARM" else "LENGTH",
                value = formatMeasured(liveLengthMm, ui.unit),
                footnote = if (kind == HardwareKind.HEX_KEY) "hex key arm" else "under head",
                accent = WorkshopPalette.Amber,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        MiniStatRow(
            headWidth = result.measurement.headWidthMm,
            overall = result.measurement.overallLengthMm,
            confidence = result.measurement.confidence,
            unit = ui.unit,
        )

        // -------------------------------------------------------------
        // Closest standards
        // -------------------------------------------------------------
        SectionHeader("CLOSEST STANDARDS", "snap tolerance ±${AppConfig.Matching.ABSOLUTE_TOLERANCE_MM} mm")
        MatchPanel(
            match = matches.metric,
            unit = ui.unit,
            familyLabel = "METRIC",
            accent = WorkshopPalette.Amber,
        )
        Spacer(Modifier.height(10.dp))
        MatchPanel(
            match = matches.imperial,
            unit = ui.unit,
            familyLabel = "IMPERIAL",
            accent = WorkshopPalette.Cyan,
        )

        if (matches.alternatives.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AlternativesCard(matches.alternatives, ui.unit)
        }

        // -------------------------------------------------------------
        // Calibration audit
        // -------------------------------------------------------------
        Spacer(Modifier.height(16.dp))
        SectionHeader("CALIBRATION AUDIT", "ISO/IEC 7810 ID-1")
        CalibrationCard(result, ui.unit)

        // -------------------------------------------------------------
        // Warnings
        // -------------------------------------------------------------
        if (result.warnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            WarningsCard(result.warnings)
        }

        // -------------------------------------------------------------
        // Footer
        // -------------------------------------------------------------
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = viewModel::resetToScanner,
            shape = RoundedCornerShape(50),
            color = WorkshopPalette.Amber,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "MEASURE ANOTHER",
                color = WorkshopPalette.Background,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.2.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No photo, measurement or metadata leaves this device.",
            color = WorkshopPalette.TextFaint,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Reusable pieces
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(label: String, meta: String) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionLabel(label, WorkshopPalette.Cyan)
        Text(
            text = meta,
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 9.sp,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DimensionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    footnote: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                SectionLabel(label, accent)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = accent,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
            Text(
                text = footnote,
                color = WorkshopPalette.TextFaint,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun MiniStatRow(
    headWidth: Double,
    overall: Double,
    confidence: Double,
    unit: UnitSystem,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniStat("HEAD / LEG", formatMeasured(headWidth, unit), Modifier.weight(1f))
        MiniStat("OVERALL", formatMeasured(overall, unit), Modifier.weight(1f))
        MiniStat(
            "EDGE CONFIDENCE",
            String.format(java.util.Locale.US, "%.0f%%", confidence * 100.0),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                color = WorkshopPalette.TextFaint,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = value,
                color = WorkshopPalette.TextSecondary,
                fontFamily = Mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MatchPanel(
    match: StandardMatch?,
    unit: UnitSystem,
    familyLabel: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        if (match == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(familyLabel, accent)
                Text(
                    text = "No catalogue entry within the accepted range.",
                    color = WorkshopPalette.TextMuted,
                    fontSize = 12.sp,
                )
            }
            return@Surface
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    SectionLabel(familyLabel, accent)
                    match.standard?.let {
                        Text(text = it, color = WorkshopPalette.TextFaint, fontSize = 10.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        imageVector = if (match.inTolerance) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (match.inTolerance) WorkshopPalette.Success else WorkshopPalette.Danger,
                        modifier = Modifier.size(13.dp),
                    )
                    InfoChip(
                        text = if (match.inTolerance) match.confidence.name else "OUTSIDE",
                        accent = if (match.inTolerance) WorkshopPalette.Success else WorkshopPalette.Danger,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        text = match.designation,
                        color = accent,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    SectionLabel("VS MEASURED", WorkshopPalette.TextFaint)
                    Text(
                        text = formatDelta(match.deltaMm, unit),
                        color = if (match.inTolerance) WorkshopPalette.Success else WorkshopPalette.TextMuted,
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f%%", kotlin.math.abs(match.deltaMm) / match.nominalMm * 100.0),
                        color = WorkshopPalette.TextFaint,
                        fontSize = 10.sp,
                    )
                }
            }

            Text(
                text = String.format(
                    java.util.Locale.US,
                    "Nominal %s (%.4f in / %.2f mm)",
                    if (match.family == HardwareFamily.METRIC) {
                        String.format(java.util.Locale.US, "%.2f mm", match.nominalMm)
                    } else {
                        String.format(java.util.Locale.US, "%.4f in", match.nominalIn)
                    },
                    match.nominalIn,
                    match.nominalMm,
                ),
                color = WorkshopPalette.TextMuted,
                fontSize = 12.sp,
            )

            if (match.threadOptions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    match.threadOptions.forEach { option ->
                        InfoChip(option.label, WorkshopPalette.TextSecondary)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                match.recommendedLengthMm?.let { length ->
                    if (match.kind == HardwareKind.FASTENER) {
                        InfoLine("Nearest standard length: ${formatMeasured(length, unit)}")
                    } else {
                        val arm = if (match.armType == "short") "Short arm" else "Long arm"
                        InfoLine("$arm: ${formatMeasured(length, unit)}")
                    }
                }
                match.socketSizeMm?.let {
                    InfoLine(String.format(java.util.Locale.US, "Hex socket %.1f mm", it))
                }
                match.socketSizeIn?.let {
                    InfoLine(String.format(java.util.Locale.US, "Hex socket %.4f in", it))
                }
                match.headType?.let { InfoLine(it) }
            }

            ScoreBar(value = match.score, accent = accent)
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = WorkshopPalette.TextMuted,
        fontSize = 11.sp,
    )
}

@Composable
private fun AlternativesCard(alternatives: List<StandardMatch>, unit: UnitSystem) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("NEARBY CATALOGUE SIZES")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                alternatives.forEach { alternative ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = WorkshopPalette.Sunken,
                        border = BorderStroke(1.dp, WorkshopPalette.Border),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                text = alternative.family.name,
                                color = WorkshopPalette.TextFaint,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                            Text(
                                text = alternative.designation,
                                color = WorkshopPalette.TextSecondary,
                                fontFamily = Mono,
                                fontSize = 12.sp,
                            )
                            Text(
                                text = formatDelta(alternative.deltaMm, unit),
                                color = WorkshopPalette.TextFaint,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    result: com.realmeasure.app.ui.AnalysisResult,
    unit: UnitSystem,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                result.cardPreview?.let { preview ->
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Rectified calibration card",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 106.dp, height = 67.dp)
                            .background(WorkshopPalette.Sunken, RoundedCornerShape(8.dp)),
                    )
                } ?: Box(
                    modifier = Modifier
                        .size(width = 106.dp, height = 67.dp)
                        .background(WorkshopPalette.Sunken, RoundedCornerShape(8.dp)),
                )
                Column {
                    Text(
                        text = "Card plane locked",
                        color = WorkshopPalette.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = String.format(
                            java.util.Locale.US,
                            "ISO/IEC 7810 ID-1 - %.2f x %.2f mm - perspective rectified",
                            result.calibration.cardWidthMm,
                            result.calibration.cardHeightMm,
                        ),
                        color = WorkshopPalette.TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.2f", result.calibration.pixelsPerMm),
                            color = WorkshopPalette.Cyan,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = " px / mm",
                            color = WorkshopPalette.TextFaint,
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStat(
                    label = "CARD CONF.",
                    value = String.format(java.util.Locale.US, "%.0f%%", result.calibration.confidence * 100.0),
                    modifier = Modifier.weight(1f),
                )
                MiniStat(
                    label = "EDGE CONF.",
                    value = String.format(java.util.Locale.US, "%.0f%%", result.measurement.confidence * 100.0),
                    modifier = Modifier.weight(1f),
                )
                MiniStat(
                    label = "CORNER ERR",
                    value = String.format(java.util.Locale.US, "%.2f px", result.calibration.reprojectionErrorPx),
                    modifier = Modifier.weight(1f),
                )
            }

            ScoreBar(
                value = result.calibration.confidence,
                accent = if (result.calibration.confidence > 0.7) WorkshopPalette.Success else WorkshopPalette.Amber,
            )
            MiniStat(
                label = "ANALYSIS TIME",
                value = String.format(java.util.Locale.US, "%d ms", result.processingMs),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WorkshopPalette.Amber.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, WorkshopPalette.Amber.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = WorkshopPalette.Amber,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Measurement notes",
                    color = WorkshopPalette.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            warnings.forEach { warning ->
                Text(
                    text = "- $warning",
                    color = WorkshopPalette.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ShareButton(
    result: com.realmeasure.app.ui.AnalysisResult,
    widthMm: Double,
    lengthMm: Double,
) {
    val context = LocalContext.current
    IconButton(onClick = {
        val metric = result.matches.metric
        val imperial = result.matches.imperial
        val report = listOf(
            "RealMeasure - on-device fastener report",
            "Mode: ${result.measurement.mode.label}",
            "${result.measurement.mode.dimensionLabel}: ${formatMeasured(widthMm, UnitSystem.MM)} (${formatMeasured(widthMm, UnitSystem.INCH)})",
            "Length: ${formatMeasured(lengthMm, UnitSystem.MM)} (${formatMeasured(lengthMm, UnitSystem.INCH)})",
            "Head / leg: ${formatMeasured(result.measurement.headWidthMm, UnitSystem.MM)}",
            metric?.let { "Metric: ${it.designation} (${formatDelta(it.deltaMm, UnitSystem.MM)})" } ?: "Metric: -",
            imperial?.let { "Imperial: ${it.designation} (${formatDelta(it.deltaMm, UnitSystem.MM)})" } ?: "Imperial: -",
            String.format(java.util.Locale.US, "Calibration: ISO/IEC 7810 ID-1, %.2f px/mm", result.calibration.pixelsPerMm),
            "All processing was performed on-device.",
        ).joinToString(separator = "\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(intent, "Share RealMeasure result"))
    }) {
        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = "Share measurement report",
            tint = WorkshopPalette.Cyan,
        )
    }
}
