package com.realmeasure.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.cv.AnalysisRenderer
import com.realmeasure.app.cv.CalibrationResult
import com.realmeasure.app.cv.ErrorEstimator
import com.realmeasure.app.cv.ScanAnalysis
import com.realmeasure.app.export.SvgExporter
import com.realmeasure.app.model.HoleGeometry
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.Pt
import com.realmeasure.app.ui.Formats
import com.realmeasure.app.ui.ScansViewModel
import com.realmeasure.app.ui.components.InfoChip
import com.realmeasure.app.ui.components.InteractiveCaliper
import com.realmeasure.app.ui.components.MetricRow
import com.realmeasure.app.ui.components.PrimaryAction
import com.realmeasure.app.ui.components.QualityBadge
import com.realmeasure.app.ui.components.SecondaryAction
import com.realmeasure.app.ui.components.SectionLabel
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Interactive flattened result.
 *
 * The top of the readout reports the **2D planar footprint** (area, perimeter,
 * bounding box) with its propagated margins. An optional 3D height slider
 * applies the first-order parallax correction k = 1 - h / H_cam to the
 * displayed linear metrics (×k) and areas (×k²). The image and exported SVG
 * remain the projected 2D footprint.
 */
@Composable
fun ResultScreen(
    analysis: ScanAnalysis,
    scansViewModel: ScansViewModel,
    onScanAnother: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calibration = analysis.calibration
    val mode = analysis.mode
    val pixelsPerMm = calibration.pixelsPerMm
    val bitmap = calibration.warpedBitmap
    val canvasWidthMm = bitmap.width / pixelsPerMm
    val canvasHeightMm = bitmap.height / pixelsPerMm
    val saving by scansViewModel.saving.collectAsStateWithLifecycle()

    var selectedIndex by remember(analysis) {
        mutableStateOf(analysis.selectedIndex.coerceIn(0, analysis.objects.lastIndex))
    }
    val selectedObject = analysis.objects[selectedIndex]
    val initial = initialPins(selectedObject, canvasWidthMm, canvasHeightMm)

    var pin1 by remember(analysis) { mutableStateOf(initial.first) }
    var pin2 by remember(analysis) { mutableStateOf(initial.second) }
    var objectHeightMm by remember(analysis) { mutableFloatStateOf(0f) }
    var heightExpanded by remember { mutableStateOf(false) }
    var saved by remember(analysis) { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        val pins = initialPins(
            analysis.objects[selectedIndex],
            canvasWidthMm,
            canvasHeightMm,
        )
        pin1 = pins.first
        pin2 = pins.second
        objectHeightMm = 0f
        saved = false
    }

    val parallaxFactor = ErrorEstimator.parallaxFactor(objectHeightMm.toDouble())
    val apparentPinDistance = hypot(
        (pin2.x - pin1.x) * canvasWidthMm,
        (pin2.y - pin1.y) * canvasHeightMm,
    )
    val apparentPinError = ErrorEstimator.distanceError(
        apparentPinDistance,
        calibration.nativePixelsPerMm,
        mode.widthMm,
        calibration.epsilonCal,
    )
    val pinDistance = apparentPinDistance * parallaxFactor
    val pinError = apparentPinError * parallaxFactor

    val correctedQuality = ErrorEstimator.quality(
        selectedObject.lengthErrorMm * parallaxFactor,
        mode,
    )
    val correctedRecommendation = ErrorEstimator.recommendation(
        correctedQuality,
        mode,
        selectedObject.lengthMm * parallaxFactor,
    )

    Dialog(
        onDismissRequest = onScanAnother,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        Surface(
            color = WorkshopPalette.Background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                ResultHeader(
                    modeLabel = mode.label,
                    pixelsPerMm = pixelsPerMm,
                    onClose = onScanAnother,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InteractiveCaliper(
                        analysis = analysis,
                        selectedIndex = selectedIndex,
                        pin1 = pin1,
                        pin2 = pin2,
                        onPin1Change = { pin1 = it },
                        onPin2Change = { pin2 = it },
                        onSelectObject = { selectedIndex = it },
                        pinDistanceMm = pinDistance,
                        pinErrorMm = pinError,
                        parallaxFactor = parallaxFactor,
                    )

                    if (analysis.objects.size > 1) {
                        ObjectSelector(
                            objects = analysis.objects,
                            selectedIndex = selectedIndex,
                            onSelect = { selectedIndex = it },
                        )
                    }

                    QualityBadge(
                        quality = correctedQuality,
                        recommendation = correctedRecommendation,
                    )

                    PlanarHeroCard(
                        objectGeometry = selectedObject,
                        parallaxFactor = parallaxFactor,
                        objectHeightMm = objectHeightMm.toDouble(),
                    )

                    HeightParallaxCard(
                        heightMm = objectHeightMm,
                        onHeightChange = { objectHeightMm = it },
                        expanded = heightExpanded,
                        onToggle = { heightExpanded = !heightExpanded },
                        parallaxFactor = parallaxFactor,
                    )

                    SpecCard(
                        objectGeometry = selectedObject,
                        calibration = calibration,
                        parallaxFactor = parallaxFactor,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrimaryAction(
                            text = when {
                                saved -> "SAVED TO MY SCANS"
                                saving -> "SAVING..."
                                else -> "SAVE TO MY SCANS"
                            },
                            onClick = {
                                if (saved || saving) return@PrimaryAction
                                scope.launch {
                                    val annotated = withContext(Dispatchers.Default) {
                                        AnalysisRenderer.renderAnnotated(
                                            warped = bitmap,
                                            objects = analysis.objects,
                                            selectedIndex = selectedIndex,
                                            pixelsPerMm = pixelsPerMm,
                                        )
                                    }
                                    scansViewModel.save(
                                        mode = mode,
                                        objectGeometry = selectedObject,
                                        annotatedBitmap = annotated,
                                    ) { saved = true }
                                }
                            },
                            enabled = !saved && !saving,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryAction(
                            text = "EXPORT 1:1 SVG",
                            accent = WorkshopPalette.Cyan,
                            onClick = {
                                SvgExporter.exportAndShare(
                                    context = context,
                                    outerMm = selectedObject.outerContour,
                                    holesMm = selectedObject.holes.map { it.contour },
                                    title = "RealMeasure_${mode.name}_" +
                                        "${selectedObject.lengthMm.roundToInt()}mm",
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    SecondaryAction(
                        text = "SCAN ANOTHER",
                        onClick = onScanAnother,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header, object selector, hero and height cards
// ---------------------------------------------------------------------------

@Composable
private fun ResultHeader(
    modeLabel: String,
    pixelsPerMm: Double,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("SCAN RESULT", WorkshopPalette.Amber)
                Spacer(Modifier.size(8.dp))
                InfoChip(modeLabel.uppercase(), WorkshopPalette.Cyan)
            }
            Text(
                text = "FLATTENED 1:1 · %.1f px/mm".format(pixelsPerMm),
                color = WorkshopPalette.TextFaint,
                fontFamily = Mono,
                fontSize = 8.sp,
                letterSpacing = 1.1.sp,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close result",
                tint = WorkshopPalette.TextMuted,
            )
        }
    }
}

@Composable
private fun ObjectSelector(
    objects: List<ObjectGeometry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        objects.forEachIndexed { index, geometry ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(50),
                color = if (selected) {
                    WorkshopPalette.Cyan.copy(alpha = 0.15f)
                } else {
                    WorkshopPalette.Sunken
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) WorkshopPalette.Cyan else WorkshopPalette.Border,
                ),
            ) {
                Text(
                    text = "OBJ ${index + 1} · ${geometry.lengthMm.roundToInt()} mm",
                    color = if (selected) WorkshopPalette.Cyan else WorkshopPalette.TextMuted,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PlanarHeroCard(
    objectGeometry: ObjectGeometry,
    parallaxFactor: Double,
    objectHeightMm: Double,
) {
    val areaCm2 = objectGeometry.areaNetCm2 * parallaxFactor * parallaxFactor
    val areaErrorCm2 = objectGeometry.areaErrorCm2 * parallaxFactor * parallaxFactor
    val areaMm2 = objectGeometry.areaNetMm2 * parallaxFactor * parallaxFactor
    val areaErrorMm2 = objectGeometry.areaErrorCm2 * 100.0 * parallaxFactor * parallaxFactor
    val perimeterMm = objectGeometry.perimeterMm * parallaxFactor
    val perimeterErrorMm = objectGeometry.perimeterErrorMm * parallaxFactor
    val lengthMm = objectGeometry.lengthMm * parallaxFactor
    val lengthErrorMm = objectGeometry.lengthErrorMm * parallaxFactor
    val widthMm = objectGeometry.widthMm * parallaxFactor
    val widthErrorMm = objectGeometry.widthErrorMm * parallaxFactor

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Cyan.copy(alpha = 0.36f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SectionLabel("2D PLANAR FOOTPRINT (FROM PHOTO)", WorkshopPalette.Emerald)
                }
                InfoChip("2D", WorkshopPalette.Emerald)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "2D PLANAR AREA",
                        color = WorkshopPalette.TextFaint,
                        fontFamily = Mono,
                        fontSize = 8.sp,
                        letterSpacing = 0.9.sp,
                    )
                    Text(
                        text = Formats.cm2WithError(areaCm2, areaErrorCm2),
                        color = WorkshopPalette.Emerald,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                    )
                    Text(
                        text = Formats.mm2WithError(areaMm2, areaErrorMm2),
                        color = WorkshopPalette.TextMuted,
                        fontFamily = Mono,
                        fontSize = 9.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "2D OUTLINE PERIMETER",
                        color = WorkshopPalette.TextFaint,
                        fontFamily = Mono,
                        fontSize = 8.sp,
                        letterSpacing = 0.9.sp,
                    )
                    Text(
                        text = Formats.mmWithError(perimeterMm, perimeterErrorMm),
                        color = WorkshopPalette.Cyan,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                    )
                    Text(
                        text = "2D outline",
                        color = WorkshopPalette.TextMuted,
                        fontFamily = Mono,
                        fontSize = 9.sp,
                    )
                }
            }

            HorizontalDivider(color = WorkshopPalette.Border)

            MetricRow(
                label = "Bounding box",
                value = Formats.dimensionsWithError(
                    lengthMm,
                    lengthErrorMm,
                    widthMm,
                    widthErrorMm,
                ),
                accent = WorkshopPalette.Cyan,
            )
            MetricRow(
                label = "Internal holes",
                value = Formats.count(objectGeometry.holes.size, "hole", "holes"),
            )

            Text(
                text = "Measures the flat 2D cross-section on the reference surface. " +
                    "For 3D containers, this represents the projected footprint area and perimeter.",
                color = WorkshopPalette.TextMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )

            if (objectHeightMm > 0.0) {
                Text(
                    text = "3D parallax correction: h = %.0f mm · k = %.3f · linear ×k, area ×k²".format(
                        Locale.US,
                        objectHeightMm,
                        parallaxFactor,
                    ),
                    color = WorkshopPalette.Amber,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun HeightParallaxCard(
    heightMm: Float,
    onHeightChange: (Float) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    parallaxFactor: Double,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("3D OBJECT HEIGHT (PARALLAX)", WorkshopPalette.Amber)
                    Text(
                        text = if (heightMm > 0f) {
                            "Parallax compensation active"
                        } else {
                            "Optional · containers and tall parts"
                        },
                        color = WorkshopPalette.TextMuted,
                        fontSize = 10.sp,
                    )
                }
                Text(
                    text = "%.0f mm".format(Locale.US, heightMm),
                    color = WorkshopPalette.Amber,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "Collapse height" else "Expand height",
                    tint = WorkshopPalette.TextMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Slider(
                        value = heightMm,
                        onValueChange = onHeightChange,
                        valueRange = 0f..AppConfig.ErrorModel.MAX_PARALLAX_HEIGHT_MM.toFloat(),
                        steps = 149,
                        colors = SliderDefaults.colors(
                            thumbColor = WorkshopPalette.Amber,
                            activeTrackColor = WorkshopPalette.Amber,
                            inactiveTrackColor = WorkshopPalette.Border,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "0 mm",
                            color = WorkshopPalette.TextFaint,
                            fontFamily = Mono,
                            fontSize = 8.sp,
                        )
                        Text(
                            text = "H_cam = 400 mm",
                            color = WorkshopPalette.TextFaint,
                            fontFamily = Mono,
                            fontSize = 8.sp,
                        )
                        Text(
                            text = "${AppConfig.ErrorModel.MAX_PARALLAX_HEIGHT_MM.toInt()} mm",
                            color = WorkshopPalette.TextFaint,
                            fontFamily = Mono,
                            fontSize = 8.sp,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "linear ×%.3f · area ×%.3f".format(
                                Locale.US,
                                parallaxFactor,
                                parallaxFactor * parallaxFactor,
                            ),
                            color = WorkshopPalette.Amber,
                            fontFamily = Mono,
                            fontSize = 9.sp,
                        )
                        TextButton(onClick = { onHeightChange(0f) }) {
                            Text(
                                text = "RESET",
                                color = WorkshopPalette.Cyan,
                                fontFamily = Mono,
                                fontSize = 9.sp,
                            )
                        }
                    }
                    Text(
                        text = "D_true = D_apparent × (1 − h / H_cam). Saved scans and the SVG " +
                            "keep the projected 2D footprint; the height correction changes the " +
                            "reported dimensions, not the vector outline.",
                        color = WorkshopPalette.TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hole / spacing / calibration detail
// ---------------------------------------------------------------------------

@Composable
private fun SpecCard(
    objectGeometry: ObjectGeometry,
    calibration: CalibrationResult,
    parallaxFactor: Double,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("GEOMETRY DETAIL", WorkshopPalette.TextMuted)

            if (objectGeometry.holes.isNotEmpty()) {
                SectionLabel("HOLE CENTRES · Ø", WorkshopPalette.Amber)
                objectGeometry.holes.forEach { hole ->
                    HoleRow(hole, parallaxFactor)
                }
            }

            if (objectGeometry.holeSpacings.isNotEmpty()) {
                if (objectGeometry.holes.isNotEmpty()) {
                    HorizontalDivider(color = WorkshopPalette.Border)
                }
                SectionLabel("CENTER-TO-CENTER", WorkshopPalette.Amber)
                objectGeometry.holeSpacings.forEach { spacing ->
                    MetricRow(
                        label = "H${spacing.firstIndex + 1} ↔ H${spacing.secondIndex + 1}",
                        value = Formats.mmWithError(
                            spacing.distanceMm * parallaxFactor,
                            spacing.errorMm * parallaxFactor,
                        ),
                    )
                }
            }

            HorizontalDivider(color = WorkshopPalette.Border)
            SectionLabel("CALIBRATION AUDIT", WorkshopPalette.TextFaint)
            MetricRow(
                label = "Reference",
                value = "${calibration.mode.label} · ${Formats.degrees(calibration.tiltDeviationDeg)} tilt",
            )
            MetricRow(
                label = "Residual ε_cal",
                value = "%.2f %%".format(calibration.epsilonCal * 100.0),
            )
            MetricRow(
                label = "Native / warped scale",
                value = "%.2f / %.2f px/mm".format(
                    calibration.nativePixelsPerMm,
                    calibration.pixelsPerMm,
                ),
            )
        }
    }
}

@Composable
private fun HoleRow(hole: HoleGeometry, parallaxFactor: Double) {
    MetricRow(
        label = "H${hole.index + 1} at (%.1f, %.1f) mm".format(
            hole.centroid.x,
            hole.centroid.y,
        ),
        value = "Ø " + Formats.mmWithError(
            hole.diameterMm * parallaxFactor,
            hole.diameterErrorMm * parallaxFactor,
        ),
    )
}

// ---------------------------------------------------------------------------
// Pin initialisation
// ---------------------------------------------------------------------------

private fun initialPins(
    objectGeometry: ObjectGeometry,
    canvasWidthMm: Double,
    canvasHeightMm: Double,
): Pair<Pt, Pt> {
    val mmPins = if (objectGeometry.holes.size >= 2) {
        listOf(
            objectGeometry.holes[0].centroid,
            objectGeometry.holes[1].centroid,
        )
    } else {
        val centreY = (objectGeometry.boundsMin.y + objectGeometry.boundsMax.y) / 2.0
        val width = objectGeometry.boundsMax.x - objectGeometry.boundsMin.x
        listOf(
            Pt(objectGeometry.boundsMin.x + width * 0.25, centreY),
            Pt(objectGeometry.boundsMin.x + width * 0.75, centreY),
        )
    }
    return normalize(mmPins[0], canvasWidthMm, canvasHeightMm) to
        normalize(mmPins[1], canvasWidthMm, canvasHeightMm)
}

private fun normalize(
    point: Pt,
    canvasWidthMm: Double,
    canvasHeightMm: Double,
): Pt = Pt(
    x = (point.x / canvasWidthMm).coerceIn(0.0, 1.0),
    y = (point.y / canvasHeightMm).coerceIn(0.0, 1.0),
)
