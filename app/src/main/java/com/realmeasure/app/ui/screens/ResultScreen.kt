package com.realmeasure.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.cv.AnalysisRenderer
import com.realmeasure.app.cv.ErrorEstimator
import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.cv.ScanAnalysis
import com.realmeasure.app.export.SvgExporter
import com.realmeasure.app.model.HoleGeometry
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.Pt
import com.realmeasure.app.ui.Formats
import com.realmeasure.app.ui.ScansViewModel
import com.realmeasure.app.ui.components.InfoChip
import com.realmeasure.app.ui.components.MetricRow
import com.realmeasure.app.ui.components.PrimaryAction
import com.realmeasure.app.ui.components.QualityBadge
import com.realmeasure.app.ui.components.SecondaryAction
import com.realmeasure.app.ui.components.SectionLabel
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive flattened result.
 *
 * The warped canvas is displayed at a fit scale. Cyan contours and amber hole
 * callouts are drawn directly in Compose so the object selection, the two
 * draggable measurement pins and the live distance readout update without
 * re-running computer vision.
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
    val saving by scansViewModel.saving.collectAsStateWithLifecycle()

    var selectedIndex by remember(analysis) {
        mutableStateOf(analysis.selectedIndex.coerceIn(0, analysis.objects.lastIndex))
    }
    val selectedObject = analysis.objects[selectedIndex]

    var pins by remember(analysis) { mutableStateOf(initialPins(selectedObject)) }
    var saved by remember(analysis) { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        pins = initialPins(analysis.objects[selectedIndex])
        saved = false
    }

    val pinDistance = Geometry.distance(pins[0], pins[1])
    val pinError = ErrorEstimator.distanceError(
        pinDistance,
        calibration.nativePixelsPerMm,
        mode.widthMm,
        calibration.epsilonCal,
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
                    FlattenedCanvas(
                        analysis = analysis,
                        selectedIndex = selectedIndex,
                        pins = pins,
                        onPinsChange = { pins = it },
                        onSelectObject = { selectedIndex = it },
                        pinDistance = pinDistance,
                        pinError = pinError,
                    )

                    if (analysis.objects.size > 1) {
                        ObjectSelector(
                            objects = analysis.objects,
                            selectedIndex = selectedIndex,
                            onSelect = { selectedIndex = it },
                        )
                    }

                    QualityBadge(
                        quality = selectedObject.quality,
                        recommendation = selectedObject.qualityRecommendation,
                    )

                    SpecCard(
                        objectGeometry = selectedObject,
                        calibration = calibration,
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
                                            warped = calibration.warpedBitmap,
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
// Header + selector + spec card
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
                border = androidx.compose.foundation.BorderStroke(
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
private fun SpecCard(
    objectGeometry: ObjectGeometry,
    calibration: com.realmeasure.app.cv.CalibrationResult,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WorkshopPalette.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("MEASUREMENTS", WorkshopPalette.TextMuted)
            MetricRow(
                label = "Bounding box",
                value = Formats.dimensionsWithError(
                    objectGeometry.lengthMm,
                    objectGeometry.lengthErrorMm,
                    objectGeometry.widthMm,
                    objectGeometry.widthErrorMm,
                ),
                accent = WorkshopPalette.Cyan,
            )
            MetricRow(
                label = "Net surface area",
                value = Formats.cm2WithError(
                    objectGeometry.areaNetCm2,
                    objectGeometry.areaErrorCm2,
                ),
                accent = WorkshopPalette.Emerald,
            )
            MetricRow(
                label = "Perimeter",
                value = Formats.mmWithError(
                    objectGeometry.perimeterMm,
                    objectGeometry.perimeterErrorMm,
                ),
            )
            MetricRow(
                label = "Holes",
                value = Formats.count(objectGeometry.holes.size, "hole", "holes"),
            )

            if (objectGeometry.holes.isNotEmpty()) {
                HorizontalDivider(color = WorkshopPalette.Border)
                SectionLabel("HOLE CENTRES · Ø", WorkshopPalette.Amber)
                objectGeometry.holes.forEach { hole ->
                    HoleRow(hole)
                }
            }

            if (objectGeometry.holeSpacings.isNotEmpty()) {
                HorizontalDivider(color = WorkshopPalette.Border)
                SectionLabel("CENTER-TO-CENTER", WorkshopPalette.Amber)
                objectGeometry.holeSpacings.forEach { spacing ->
                    MetricRow(
                        label = "H${spacing.firstIndex + 1} ↔ H${spacing.secondIndex + 1}",
                        value = Formats.mmWithError(spacing.distanceMm, spacing.errorMm),
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
private fun HoleRow(hole: HoleGeometry) {
    MetricRow(
        label = "H${hole.index + 1} at (%.1f, %.1f) mm".format(
            hole.centroid.x,
            hole.centroid.y,
        ),
        value = "Ø " + Formats.mmWithError(hole.diameterMm, hole.diameterErrorMm),
    )
}

// ---------------------------------------------------------------------------
// Flattened interactive canvas
// ---------------------------------------------------------------------------

@Composable
private fun FlattenedCanvas(
    analysis: ScanAnalysis,
    selectedIndex: Int,
    pins: List<Pt>,
    onPinsChange: (List<Pt>) -> Unit,
    onSelectObject: (Int) -> Unit,
    pinDistance: Double,
    pinError: Double,
) {
    val calibration = analysis.calibration
    val bitmap = calibration.warpedBitmap
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val pixelsPerMm = calibration.pixelsPerMm
    val textMeasurer = rememberTextMeasurer()
    val selectedObject = analysis.objects[selectedIndex]

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(WorkshopPalette.Sunken),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(analysis, imageBitmap) {
                        val transform = viewTransform(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            bitmap,
                            pixelsPerMm,
                        )
                        val thresholdMm = 30.dp.toPx() / transform.scale / pixelsPerMm
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downMm = transform.toMm(down.position)
                            val pinIndex = nearestPin(pins, downMm, thresholdMm)
                            if (pinIndex != null) {
                                drag(down.id) { change ->
                                    val mm = transform.toMm(change.position)
                                    val next = pins.toMutableList()
                                    next[pinIndex] = Pt(
                                        mm.x.coerceIn(0.0, transform.widthMm),
                                        mm.y.coerceIn(0.0, transform.heightMm),
                                    )
                                    onPinsChange(next)
                                    change.consume()
                                }
                            } else {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val mm = transform.toMm(up.position)
                                    val hit = analysis.objects.indexOfFirst { objectGeometry ->
                                        Geometry.contains(objectGeometry.outerContour, mm)
                                    }
                                    if (hit >= 0) onSelectObject(hit)
                                }
                            }
                        }
                    },
            ) {
                val transform = viewTransform(size, bitmap, pixelsPerMm)
                drawRect(color = WorkshopPalette.Sunken)
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(
                        transform.offsetX.roundToInt(),
                        transform.offsetY.roundToInt(),
                    ),
                    dstSize = IntSize(
                        (bitmap.width * transform.scale).roundToInt(),
                        (bitmap.height * transform.scale).roundToInt(),
                    ),
                    filterQuality = FilterQuality.Medium,
                )

                analysis.objects.forEachIndexed { index, objectGeometry ->
                    drawObjectContour(
                        objectGeometry = objectGeometry,
                        selected = index == selectedIndex,
                        transform = transform,
                    )
                }

                drawObjectAnnotations(
                    objectGeometry = selectedObject,
                    transform = transform,
                    textMeasurer = textMeasurer,
                )

                drawPins(
                    pins = pins,
                    transform = transform,
                    textMeasurer = textMeasurer,
                    distanceMm = pinDistance,
                    errorMm = pinError,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = WorkshopPalette.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, WorkshopPalette.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TWO-PIN CALIPER",
                        color = WorkshopPalette.Amber,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.0.sp,
                    )
                    Text(
                        text = Formats.mmWithError(pinDistance, pinError),
                        color = WorkshopPalette.Amber,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    text = "Drag P1 / P2 · tap an object to select it",
                    color = WorkshopPalette.TextFaint,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun DrawScope.drawObjectContour(
    objectGeometry: ObjectGeometry,
    selected: Boolean,
    transform: ViewTransform,
) {
    val path = Path()
    objectGeometry.outerContour.forEachIndexed { index, point ->
        val screen = transform.toScreen(point)
        if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    path.close()

    if (selected) {
        drawPath(path, color = WorkshopPalette.Cyan.copy(alpha = 0.10f))
    }
    drawPath(
        path = path,
        color = if (selected) WorkshopPalette.Cyan else WorkshopPalette.Cyan.copy(alpha = 0.35f),
        style = Stroke(
            width = if (selected) 2.2.dp.toPx() else 1.2.dp.toPx(),
            join = StrokeJoin.Round,
            cap = StrokeCap.Round,
        ),
    )
}

private fun DrawScope.drawObjectAnnotations(
    objectGeometry: ObjectGeometry,
    transform: ViewTransform,
    textMeasurer: TextMeasurer,
) {
    objectGeometry.holes.forEach { hole ->
        val centre = transform.toScreen(hole.centroid)
        val radius = max(3.dp.toPx(), (hole.diameterMm / 2.0 * transform.pxPerMm * transform.scale).toFloat())
        drawCircle(
            color = WorkshopPalette.Amber,
            radius = radius,
            center = centre,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawLine(
            color = WorkshopPalette.Amber,
            start = Offset(centre.x - radius * 1.5f, centre.y),
            end = Offset(centre.x + radius * 1.5f, centre.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = WorkshopPalette.Amber,
            start = Offset(centre.x, centre.y - radius * 1.5f),
            end = Offset(centre.x, centre.y + radius * 1.5f),
            strokeWidth = 1.dp.toPx(),
        )
    }

    val pairs = if (objectGeometry.holes.size <= 6) {
        objectGeometry.holeSpacings
    } else {
        objectGeometry.holeSpacings.filter { it.secondIndex == it.firstIndex + 1 }
    }
    pairs.forEachIndexed { pairIndex, spacing ->
        val first = transform.toScreen(objectGeometry.holes[spacing.firstIndex].centroid)
        val second = transform.toScreen(objectGeometry.holes[spacing.secondIndex].centroid)
        drawLine(
            color = WorkshopPalette.Amber.copy(alpha = 0.75f),
            start = first,
            end = second,
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(5.dp.toPx(), 4.dp.toPx()),
                0f,
            ),
        )
        val mid = Offset((first.x + second.x) / 2f, (first.y + second.y) / 2f)
        drawChipLabel(
            textMeasurer = textMeasurer,
            text = Formats.mmWithError(spacing.distanceMm, spacing.errorMm, digits = 1),
            x = mid.x,
            y = mid.y + if (pairIndex % 2 == 0) -10.dp.toPx() else 12.dp.toPx(),
            textColor = WorkshopPalette.Amber,
        )
    }
}

private fun DrawScope.drawPins(
    pins: List<Pt>,
    transform: ViewTransform,
    textMeasurer: TextMeasurer,
    distanceMm: Double,
    errorMm: Double,
) {
    if (pins.size < 2) return
    val first = transform.toScreen(pins[0])
    val second = transform.toScreen(pins[1])
    drawLine(
        color = WorkshopPalette.Emerald,
        start = first,
        end = second,
        strokeWidth = 1.6.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(7.dp.toPx(), 5.dp.toPx()),
            0f,
        ),
    )

    val colours = listOf(WorkshopPalette.Emerald, WorkshopPalette.Amber)
    pins.take(2).forEachIndexed { index, pin ->
        val point = transform.toScreen(pin)
        val colour = colours[index]
        drawCircle(
            color = WorkshopPalette.Background.copy(alpha = 0.65f),
            radius = 13.dp.toPx(),
            center = point,
        )
        drawCircle(
            color = colour,
            radius = 10.dp.toPx(),
            center = point,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(color = colour, radius = 2.5.dp.toPx(), center = point)
        drawLine(
            color = colour,
            start = Offset(point.x - 15.dp.toPx(), point.y),
            end = Offset(point.x + 15.dp.toPx(), point.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = colour,
            start = Offset(point.x, point.y - 15.dp.toPx()),
            end = Offset(point.x, point.y + 15.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        drawChipLabel(
            textMeasurer = textMeasurer,
            text = "P${index + 1}",
            x = point.x,
            y = point.y - 24.dp.toPx(),
            textColor = colour,
        )
    }

    drawChipLabel(
        textMeasurer = textMeasurer,
        text = Formats.mmWithError(distanceMm, errorMm),
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f - 16.dp.toPx(),
        textColor = WorkshopPalette.Emerald,
    )
}

private fun DrawScope.drawChipLabel(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    textColor: Color,
) {
    val style = TextStyle(
        color = textColor,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
    )
    val measured = textMeasurer.measure(text, style)
    val padX = 4.dp.toPx()
    val padY = 2.dp.toPx()
    val width = measured.size.width + padX * 2f
    val height = measured.size.height + padY * 2f
    drawRoundRect(
        color = WorkshopPalette.Background.copy(alpha = 0.82f),
        topLeft = Offset(x - width / 2f, y - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
    )
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(x - measured.size.width / 2f, y - measured.size.height / 2f),
        style = style,
    )
}

// ---------------------------------------------------------------------------
// View transform + pin helpers
// ---------------------------------------------------------------------------

private data class ViewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val pxPerMm: Double,
    val widthMm: Double,
    val heightMm: Double,
) {
    fun toScreen(point: Pt): Offset = Offset(
        x = offsetX + (point.x * pxPerMm * scale).toFloat(),
        y = offsetY + (point.y * pxPerMm * scale).toFloat(),
    )

    fun toMm(position: Offset): Pt = Pt(
        x = ((position.x - offsetX) / scale / pxPerMm),
        y = ((position.y - offsetY) / scale / pxPerMm),
    )
}

private fun viewTransform(
    canvas: Size,
    bitmap: Bitmap,
    pixelsPerMm: Double,
): ViewTransform {
    val scale = min(
        canvas.width / bitmap.width.toFloat(),
        canvas.height / bitmap.height.toFloat(),
    ).coerceAtLeast(0.0001f)
    val drawWidth = bitmap.width * scale
    val drawHeight = bitmap.height * scale
    return ViewTransform(
        scale = scale,
        offsetX = (canvas.width - drawWidth) / 2f,
        offsetY = (canvas.height - drawHeight) / 2f,
        pxPerMm = pixelsPerMm,
        widthMm = bitmap.width / pixelsPerMm,
        heightMm = bitmap.height / pixelsPerMm,
    )
}

private fun initialPins(objectGeometry: ObjectGeometry): List<Pt> {
    if (objectGeometry.holes.size >= 2) {
        return listOf(
            objectGeometry.holes[0].centroid,
            objectGeometry.holes[1].centroid,
        )
    }
    val centreY = (objectGeometry.boundsMin.y + objectGeometry.boundsMax.y) / 2.0
    val width = objectGeometry.boundsMax.x - objectGeometry.boundsMin.x
    return listOf(
        Pt(objectGeometry.boundsMin.x + width * 0.25, centreY),
        Pt(objectGeometry.boundsMin.x + width * 0.75, centreY),
    )
}

private fun nearestPin(pins: List<Pt>, point: Pt, thresholdMm: Double): Int? {
    var bestIndex: Int? = null
    var bestDistance = thresholdMm
    pins.forEachIndexed { index, pin ->
        val distance = Geometry.distance(pin, point)
        if (distance <= bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}
