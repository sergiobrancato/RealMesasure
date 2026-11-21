package com.realmeasure.app.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.cv.ScanAnalysis
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.Pt
import com.realmeasure.app.ui.Formats
import com.realmeasure.app.ui.theme.Mono
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive two-pin caliper over the flattened canvas.
 *
 * Pins are stored in **normalised image coordinates** so layout, zoom and
 * recomposition can never shift them. Each pin has a crosshair tip and a grip
 * rendered 36 dp below, keeping the user's finger off the target edge. While a
 * pin is being dragged a 3× circular loupe appears in the top corner opposite
 * the pin, and the fine-tune bar moves the selected pin in exact 0.1 mm steps.
 */
@Composable
fun InteractiveCaliper(
    analysis: ScanAnalysis,
    selectedIndex: Int,
    pin1: Pt,
    pin2: Pt,
    onPin1Change: (Pt) -> Unit,
    onPin2Change: (Pt) -> Unit,
    onSelectObject: (Int) -> Unit,
    pinDistanceMm: Double,
    pinErrorMm: Double,
    parallaxFactor: Double,
) {
    val calibration = analysis.calibration
    val bitmap = calibration.warpedBitmap
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val pixelsPerMm = calibration.pixelsPerMm
    val canvasWidthMm = bitmap.width / pixelsPerMm
    val canvasHeightMm = bitmap.height / pixelsPerMm
    val textMeasurer = rememberTextMeasurer()
    val selectedObject = analysis.objects[selectedIndex]

    var activePin by remember { mutableStateOf<Int?>(null) }
    var nudgeTarget by remember { mutableIntStateOf(1) }

    // The pointerInput coroutine is not restarted when the pins move; these
    // wrappers always expose the latest coordinates for hit testing.
    val currentPin1 by rememberUpdatedState(pin1)
    val currentPin2 by rememberUpdatedState(pin2)

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
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val transform = viewTransform(
                                Size(size.width.toFloat(), size.height.toFloat()),
                                bitmap,
                                pixelsPerMm,
                            )
                            val gripOffset = 36.dp.toPx()
                            val threshold = 48.dp.toPx()
                            val p1 = transform.toScreenNormalized(currentPin1) +
                                Offset(0f, gripOffset)
                            val p2 = transform.toScreenNormalized(currentPin2) +
                                Offset(0f, gripOffset)
                            val d1 = distance(down.position, p1)
                            val d2 = distance(down.position, p2)

                            // activePin is chosen once, at drag start, and is
                            // never switched while the gesture is active.
                            val chosen = when {
                                d1 <= threshold && d1 <= d2 -> 1
                                d2 <= threshold -> 2
                                else -> null
                            }
                            activePin = chosen

                            if (chosen != null) {
                                try {
                                    drag(down.id) { change ->
                                        val tip = Offset(
                                            change.position.x,
                                            change.position.y - gripOffset,
                                        )
                                        val next = transform.toNormalized(tip)
                                        if (chosen == 1) {
                                            onPin1Change(next)
                                        } else {
                                            onPin2Change(next)
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    activePin = null
                                }
                            } else {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val mm = transform.toMm(up.position)
                                    val hit = analysis.objects.indexOfFirst {
                                        Geometry.contains(it.outerContour, mm)
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
                    parallaxFactor = parallaxFactor,
                )

                drawCaliperPins(
                    pin1 = pin1,
                    pin2 = pin2,
                    activePin = activePin,
                    transform = transform,
                    textMeasurer = textMeasurer,
                    distanceMm = pinDistanceMm,
                    errorMm = pinErrorMm,
                )

                if (activePin != null) {
                    drawLoupe(
                        imageBitmap = imageBitmap,
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        pin = if (activePin == 1) pin1 else pin2,
                        color = if (activePin == 1) {
                            WorkshopPalette.Emerald
                        } else {
                            WorkshopPalette.Amber
                        },
                        transform = transform,
                        textMeasurer = textMeasurer,
                    )
                }
            }
        }

        CaliperReadout(
            distanceMm = pinDistanceMm,
            errorMm = pinErrorMm,
            parallaxFactor = parallaxFactor,
        )

        FineTuneBar(
            target = nudgeTarget,
            onTarget = { nudgeTarget = it },
            onNudge = { dx, dy ->
                val stepX = 0.1 / canvasWidthMm
                val stepY = 0.1 / canvasHeightMm
                val current = if (nudgeTarget == 1) currentPin1 else currentPin2
                val next = Pt(
                    (current.x + dx * stepX).coerceIn(0.0, 1.0),
                    (current.y + dy * stepY).coerceIn(0.0, 1.0),
                )
                if (nudgeTarget == 1) onPin1Change(next) else onPin2Change(next)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Readout + fine tune
// ---------------------------------------------------------------------------

@Composable
private fun CaliperReadout(
    distanceMm: Double,
    errorMm: Double,
    parallaxFactor: Double,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "TWO-PIN CALIPER",
                        color = WorkshopPalette.Amber,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.0.sp,
                    )
                    if (parallaxFactor < 0.999) {
                        InfoChip(
                            text = "3D ×%.2f".format(parallaxFactor),
                            accent = WorkshopPalette.Amber,
                        )
                    }
                }
                Text(
                    text = Formats.mmWithError(distanceMm, errorMm),
                    color = WorkshopPalette.Amber,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
            Text(
                text = "Drag the grips below the crosshairs · tap an object to select",
                color = WorkshopPalette.TextFaint,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun FineTuneBar(
    target: Int,
    onTarget: (Int) -> Unit,
    onNudge: (dx: Double, dy: Double) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "FINE-TUNE",
                    color = WorkshopPalette.TextSecondary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.0.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "0.1 mm steps",
                    color = WorkshopPalette.TextFaint,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PinSelector(
                    label = "PIN 1",
                    selected = target == 1,
                    accent = WorkshopPalette.Emerald,
                    onClick = { onTarget(1) },
                )
                PinSelector(
                    label = "PIN 2",
                    selected = target == 2,
                    accent = WorkshopPalette.Amber,
                    onClick = { onTarget(2) },
                )
                Spacer(Modifier.weight(1f))
                NudgeButton(
                    icon = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Move selected pin left 0.1 mm",
                    onClick = { onNudge(-1.0, 0.0) },
                )
                NudgeButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move selected pin up 0.1 mm",
                    onClick = { onNudge(0.0, -1.0) },
                )
                NudgeButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move selected pin down 0.1 mm",
                    onClick = { onNudge(0.0, 1.0) },
                )
                NudgeButton(
                    icon = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Move selected pin right 0.1 mm",
                    onClick = { onNudge(1.0, 0.0) },
                )
            }
        }
    }
}

@Composable
private fun PinSelector(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.16f) else WorkshopPalette.Sunken,
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.75f) else WorkshopPalette.Border,
        ),
    ) {
        Text(
            text = label,
            color = if (selected) accent else WorkshopPalette.TextMuted,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = WorkshopPalette.Sunken,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = WorkshopPalette.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawObjectContour(
    objectGeometry: ObjectGeometry,
    selected: Boolean,
    transform: ViewTransform,
) {
    val path = Path()
    objectGeometry.outerContour.forEachIndexed { index, point ->
        val screen = transform.toScreenMm(point)
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
    parallaxFactor: Double,
) {
    objectGeometry.holes.forEach { hole ->
        val centre = transform.toScreenMm(hole.centroid)
        val radius = max(
            3.dp.toPx(),
            (hole.diameterMm / 2.0 * transform.pxPerMm * transform.scale).toFloat(),
        )
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
        val first = transform.toScreenMm(objectGeometry.holes[spacing.firstIndex].centroid)
        val second = transform.toScreenMm(objectGeometry.holes[spacing.secondIndex].centroid)
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
            text = Formats.mmWithError(
                spacing.distanceMm * parallaxFactor,
                spacing.errorMm * parallaxFactor,
                digits = 1,
            ),
            x = mid.x,
            y = mid.y + if (pairIndex % 2 == 0) -10.dp.toPx() else 12.dp.toPx(),
            textColor = WorkshopPalette.Amber,
        )
    }
}

private fun DrawScope.drawCaliperPins(
    pin1: Pt,
    pin2: Pt,
    activePin: Int?,
    transform: ViewTransform,
    textMeasurer: TextMeasurer,
    distanceMm: Double,
    errorMm: Double,
) {
    val first = transform.toScreenNormalized(pin1)
    val second = transform.toScreenNormalized(pin2)
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

    drawOnePin(
        tip = first,
        index = 1,
        color = WorkshopPalette.Emerald,
        active = activePin == 1,
        textMeasurer = textMeasurer,
    )
    drawOnePin(
        tip = second,
        index = 2,
        color = WorkshopPalette.Amber,
        active = activePin == 2,
        textMeasurer = textMeasurer,
    )

    drawChipLabel(
        textMeasurer = textMeasurer,
        text = Formats.mmWithError(distanceMm, errorMm),
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f - 20.dp.toPx(),
        textColor = WorkshopPalette.Emerald,
    )
}

/** Crosshair tip + circular grip rendered 36 dp below the finger target. */
private fun DrawScope.drawOnePin(
    tip: Offset,
    index: Int,
    color: Color,
    active: Boolean,
    textMeasurer: TextMeasurer,
) {
    val gripOffset = 36.dp.toPx()
    val grip = Offset(tip.x, tip.y + gripOffset)
    val gripRadius = 13.dp.toPx()

    drawLine(
        color = color.copy(alpha = 0.75f),
        start = Offset(tip.x, tip.y + 7.dp.toPx()),
        end = Offset(grip.x, grip.y - gripRadius),
        strokeWidth = 1.4.dp.toPx(),
    )

    drawCircle(
        color = WorkshopPalette.Background.copy(alpha = 0.55f),
        radius = 7.dp.toPx(),
        center = tip,
    )
    drawCircle(
        color = color,
        radius = 4.dp.toPx(),
        center = tip,
        style = Stroke(width = 1.6.dp.toPx()),
    )
    drawCircle(color = color, radius = 1.5.dp.toPx(), center = tip)
    drawLine(
        color = color,
        start = Offset(tip.x - 10.dp.toPx(), tip.y),
        end = Offset(tip.x + 10.dp.toPx(), tip.y),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = color,
        start = Offset(tip.x, tip.y - 10.dp.toPx()),
        end = Offset(tip.x, tip.y + 10.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )

    if (active) {
        drawCircle(
            color = color.copy(alpha = 0.30f),
            radius = gripRadius + 5.dp.toPx(),
            center = grip,
        )
    }
    drawCircle(
        color = WorkshopPalette.Background.copy(alpha = 0.80f),
        radius = gripRadius + 2.dp.toPx(),
        center = grip,
    )
    drawCircle(color = color, radius = gripRadius, center = grip)
    val style = TextStyle(
        color = WorkshopPalette.Background,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
    )
    val measured = textMeasurer.measure("P$index", style)
    drawText(
        textMeasurer = textMeasurer,
        text = "P$index",
        topLeft = Offset(
            grip.x - measured.size.width / 2f,
            grip.y - measured.size.height / 2f,
        ),
        style = style,
    )
}

private fun DrawScope.drawLoupe(
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap,
    bitmapWidth: Int,
    bitmapHeight: Int,
    pin: Pt,
    color: Color,
    transform: ViewTransform,
    textMeasurer: TextMeasurer,
) {
    val radius = 52.dp.toPx()
    val margin = 10.dp.toPx()
    val center = if (pin.x > 0.5) {
        Offset(radius + margin, radius + margin)
    } else {
        Offset(size.width - radius - margin, radius + margin)
    }
    val zoom = transform.scale * 3f
    val pinPxX = pin.x * bitmapWidth
    val pinPxY = pin.y * bitmapHeight
    val dstLeft = center.x - pinPxX * zoom
    val dstTop = center.y - pinPxY * zoom
    val circle = Path().apply {
        addOval(
            Rect(
                left = center.x - radius,
                top = center.y - radius,
                right = center.x + radius,
                bottom = center.y + radius,
            ),
        )
    }

    drawCircle(
        color = WorkshopPalette.Background.copy(alpha = 0.92f),
        radius = radius + 3.dp.toPx(),
        center = center,
    )
    clipPath(circle) {
        drawImage(
            image = imageBitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmapWidth, bitmapHeight),
            dstOffset = IntOffset(
                dstLeft.roundToInt(),
                dstTop.roundToInt(),
            ),
            dstSize = IntSize(
                (bitmapWidth * zoom).roundToInt(),
                (bitmapHeight * zoom).roundToInt(),
            ),
            filterQuality = FilterQuality.High,
        )
    }
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = Stroke(width = 3.dp.toPx()),
    )
    drawCircle(
        color = WorkshopPalette.Background.copy(alpha = 0.75f),
        radius = 6.dp.toPx(),
        center = center,
    )
    drawLine(
        color = color,
        start = Offset(center.x - 14.dp.toPx(), center.y),
        end = Offset(center.x + 14.dp.toPx(), center.y),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = color,
        start = Offset(center.x, center.y - 14.dp.toPx()),
        end = Offset(center.x, center.y + 14.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
    drawChipLabel(
        textMeasurer = textMeasurer,
        text = "3×",
        x = center.x,
        y = center.y + radius + 10.dp.toPx(),
        textColor = color,
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
// Coordinate transform
// ---------------------------------------------------------------------------

private data class ViewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val pxPerMm: Double,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val widthMm: Double get() = imageWidth / pxPerMm
    val heightMm: Double get() = imageHeight / pxPerMm

    fun toScreenMm(point: Pt): Offset = Offset(
        x = offsetX + (point.x * pxPerMm * scale).toFloat(),
        y = offsetY + (point.y * pxPerMm * scale).toFloat(),
    )

    fun toScreenNormalized(point: Pt): Offset = Offset(
        x = offsetX + (point.x * imageWidth * scale).toFloat(),
        y = offsetY + (point.y * imageHeight * scale).toFloat(),
    )

    fun toMm(position: Offset): Pt = Pt(
        x = ((position.x - offsetX) / scale / pxPerMm),
        y = ((position.y - offsetY) / scale / pxPerMm),
    )

    fun toNormalized(position: Offset): Pt = Pt(
        x = ((((position.x - offsetX) / scale) / imageWidth).toDouble()).coerceIn(0.0, 1.0),
        y = ((((position.y - offsetY) / scale) / imageHeight).toDouble()).coerceIn(0.0, 1.0),
    )
}

private fun viewTransform(
    canvas: Size,
    bitmap: android.graphics.Bitmap,
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
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
    )
}

private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)
