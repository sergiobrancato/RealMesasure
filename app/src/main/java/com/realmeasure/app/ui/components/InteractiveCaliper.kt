package com.realmeasure.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.model.CalibrationResult
import com.realmeasure.app.model.CaliperHandles
import com.realmeasure.app.model.NormPt
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.UnitSystem
import com.realmeasure.app.ui.theme.Mono
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Post-capture verification overlay.
 *
 * Handles live in normalised bitmap coordinates and are transformed through
 * the card homography before distances are computed, so 0.1 mm nudges stay
 * physically meaningful despite camera tilt. The frozen bitmap is fitted
 * (ContentScale.Fit) inside the frame; handle positions are mapped through
 * the same fitted rectangle.
 */
@Composable
fun InteractiveCaliper(
    bitmap: Bitmap,
    calibration: CalibrationResult,
    handles: CaliperHandles,
    onHandlesChange: (CaliperHandles) -> Unit,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf<HandleKey?>(null) }

    val (widthMm, lengthMm) = remember(handles, calibration) {
        Geometry.caliperValues(handles, calibration.homography, bitmap.width, bitmap.height)
    }

    val inverseHomography = remember(calibration) {
        runCatching { Geometry.invert3x3(calibration.homography) }.getOrDefault(calibration.homography)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // -------------------------------------------------------------
        // Frozen photo + draggable caliper
        // -------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(WorkshopPalette.Sunken)
                .onSizeChanged { frameSize = it },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            if (frameSize != IntSize.Zero) {
                val fit = fitRect(frameSize, bitmap.width, bitmap.height)
                val handleOffsets = mapOf(
                    HandleKey.WIDTH_A to normToOffset(fit, handles.widthA),
                    HandleKey.WIDTH_B to normToOffset(fit, handles.widthB),
                    HandleKey.LENGTH_A to normToOffset(fit, handles.lengthA),
                    HandleKey.LENGTH_B to normToOffset(fit, handles.lengthB),
                )
                val touchRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    AppConfig.Caliper.TOUCH_RADIUS_DP.dp.toPx()
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(handles, fit) {
                            detectDragGestures(
                                onDragStart = { position ->
                                    activeHandle = nearestHandle(
                                        position, handleOffsets, touchRadiusPx,
                                    )
                                },
                                onDrag = { change, dragAmount ->
                                    val key = activeHandle
                                    if (key != null) {
                                        val updated = moveHandle(
                                            handles, key, dragAmount, fit,
                                        )
                                        onHandlesChange(updated)
                                    }
                                    change.consume()
                                },
                                onDragEnd = { activeHandle = null },
                                onDragCancel = { activeHandle = null },
                            )
                        },
                ) {
                    // Width measurement line (cyan).
                    drawLine(
                        color = WorkshopPalette.Cyan,
                        start = handleOffsets.getValue(HandleKey.WIDTH_A),
                        end = handleOffsets.getValue(HandleKey.WIDTH_B),
                        strokeWidth = 2.5f,
                    )
                    drawTicks(
                        handleOffsets.getValue(HandleKey.WIDTH_A),
                        handleOffsets.getValue(HandleKey.WIDTH_B),
                        WorkshopPalette.Cyan,
                    )

                    // Length measurement line (cyan + amber dashed overlay).
                    drawLine(
                        color = WorkshopPalette.Cyan.copy(alpha = 0.9f),
                        start = handleOffsets.getValue(HandleKey.LENGTH_A),
                        end = handleOffsets.getValue(HandleKey.LENGTH_B),
                        strokeWidth = 2f,
                    )
                    drawLine(
                        color = WorkshopPalette.Amber.copy(alpha = 0.35f),
                        start = handleOffsets.getValue(HandleKey.LENGTH_A),
                        end = handleOffsets.getValue(HandleKey.LENGTH_B),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f)),
                    )
                    drawTicks(
                        handleOffsets.getValue(HandleKey.LENGTH_A),
                        handleOffsets.getValue(HandleKey.LENGTH_B),
                        WorkshopPalette.Cyan,
                    )

                    // Dashed mid-circle on the width line.
                    val widthMid = midpoint(
                        handleOffsets.getValue(HandleKey.WIDTH_A),
                        handleOffsets.getValue(HandleKey.WIDTH_B),
                    )
                    drawCircle(
                        color = WorkshopPalette.Cyan.copy(alpha = 0.55f),
                        radius = 8f,
                        center = widthMid,
                        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f))),
                    )

                    // Handle rings.
                    handleOffsets.forEach { (key, offset) ->
                        val color = when (key) {
                            HandleKey.WIDTH_A, HandleKey.WIDTH_B -> WorkshopPalette.Cyan
                            HandleKey.LENGTH_A, HandleKey.LENGTH_B -> WorkshopPalette.Amber
                        }
                        drawCircle(
                            color = WorkshopPalette.Background.copy(alpha = 0.72f),
                            radius = 11f,
                            center = offset,
                        )
                        drawCircle(color = color, radius = 11f, center = offset, style = Stroke(width = 2f))
                        drawCircle(color = color, radius = 3.5f, center = offset)
                    }

                    // Live value labels.
                    drawText(
                        textMeasurer,
                        formatMeasured(widthMm, unit),
                        topLeft = Offset(widthMid.x - 30f, widthMid.y - 26f),
                        style = TextStyle(
                            color = WorkshopPalette.Cyan,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        ),
                    )
                    val lengthMid = midpoint(
                        handleOffsets.getValue(HandleKey.LENGTH_A),
                        handleOffsets.getValue(HandleKey.LENGTH_B),
                    )
                    drawText(
                        textMeasurer,
                        formatMeasured(lengthMm, unit),
                        topLeft = Offset(lengthMid.x + 16f, lengthMid.y - 7f),
                        style = TextStyle(
                            color = WorkshopPalette.Cyan,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Fine-tune controls (± 0.1 mm)
        // -------------------------------------------------------------
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = WorkshopPalette.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, WorkshopPalette.Border),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FineTuneRow(
                    label = "WIDTH",
                    value = formatMeasured(widthMm, unit),
                    accent = WorkshopPalette.Cyan,
                    onDecrease = {
                        onHandlesChange(
                            Geometry.nudgeWidth(
                                handles, -AppConfig.Caliper.STEP_MM,
                                calibration.homography, inverseHomography,
                                bitmap.width, bitmap.height, AppConfig.Caliper.MIN_WIDTH_MM,
                            ),
                        )
                    },
                    onIncrease = {
                        onHandlesChange(
                            Geometry.nudgeWidth(
                                handles, +AppConfig.Caliper.STEP_MM,
                                calibration.homography, inverseHomography,
                                bitmap.width, bitmap.height, AppConfig.Caliper.MIN_WIDTH_MM,
                            ),
                        )
                    },
                )
                FineTuneRow(
                    label = "LENGTH",
                    value = formatMeasured(lengthMm, unit),
                    accent = WorkshopPalette.Amber,
                    onDecrease = {
                        onHandlesChange(
                            Geometry.nudgeLength(
                                handles, -AppConfig.Caliper.STEP_MM,
                                calibration.homography, inverseHomography,
                                bitmap.width, bitmap.height, AppConfig.Caliper.MIN_LENGTH_MM,
                            ),
                        )
                    },
                    onIncrease = {
                        onHandlesChange(
                            Geometry.nudgeLength(
                                handles, +AppConfig.Caliper.STEP_MM,
                                calibration.homography, inverseHomography,
                                bitmap.width, bitmap.height, AppConfig.Caliper.MIN_LENGTH_MM,
                            ),
                        )
                    },
                )
                Text(
                    text = "Drag handles for coarse edits - arrows nudge by ${AppConfig.Caliper.STEP_MM} mm",
                    color = WorkshopPalette.TextFaint,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Handle plumbing
// ---------------------------------------------------------------------------

private enum class HandleKey { WIDTH_A, WIDTH_B, LENGTH_A, LENGTH_B }

private data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Contain-rect of a bitmap inside a frame (matches ContentScale.Fit). */
private fun fitRect(frame: IntSize, bitmapW: Int, bitmapH: Int): FitRect {
    if (frame.width == 0 || frame.height == 0 || bitmapW == 0 || bitmapH == 0) {
        return FitRect(0f, 0f, 0f, 0f)
    }
    val scale = minOf(
        frame.width.toFloat() / bitmapW,
        frame.height.toFloat() / bitmapH,
    )
    val width = bitmapW * scale
    val height = bitmapH * scale
    return FitRect(
        left = (frame.width - width) / 2f,
        top = (frame.height - height) / 2f,
        width = width,
        height = height,
    )
}

private fun normToOffset(fit: FitRect, point: NormPt): Offset =
    Offset(fit.left + (point.x * fit.width).toFloat(), fit.top + (point.y * fit.height).toFloat())

private fun offsetToNorm(fit: FitRect, offset: Offset): NormPt = NormPt(
    x = ((offset.x - fit.left) / fit.width).coerceIn(0.0f, 1.0f).toDouble(),
    y = ((offset.y - fit.top) / fit.height).coerceIn(0.0f, 1.0f).toDouble(),
)

private fun nearestHandle(
    position: Offset,
    offsets: Map<HandleKey, Offset>,
    touchRadiusPx: Float,
): HandleKey? {
    var best: HandleKey? = null
    var bestDistance = touchRadiusPx
    offsets.forEach { (key, offset) ->
        val distance = hypot(position.x - offset.x, position.y - offset.y)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = key
        }
    }
    return best
}

private fun moveHandle(
    handles: CaliperHandles,
    key: HandleKey,
    dragAmount: Offset,
    fit: FitRect,
): CaliperHandles {
    val current = when (key) {
        HandleKey.WIDTH_A -> handles.widthA
        HandleKey.WIDTH_B -> handles.widthB
        HandleKey.LENGTH_A -> handles.lengthA
        HandleKey.LENGTH_B -> handles.lengthB
    }
    val currentOffset = normToOffset(fit, current)
    val moved = offsetToNorm(fit, currentOffset + dragAmount)
    return when (key) {
        HandleKey.WIDTH_A -> handles.copy(widthA = moved)
        HandleKey.WIDTH_B -> handles.copy(widthB = moved)
        HandleKey.LENGTH_A -> handles.copy(lengthA = moved)
        HandleKey.LENGTH_B -> handles.copy(lengthB = moved)
    }
}

private fun midpoint(a: Offset, b: Offset): Offset = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

/** Perpendicular end ticks for a measurement line. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTicks(
    a: Offset,
    b: Offset,
    color: Color,
) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = hypot(dx, dy)
    if (length == 0f) return
    val nx = (-dy / length) * 7f
    val ny = (dx / length) * 7f
    drawLine(color, Offset(a.x - nx, a.y - ny), Offset(a.x + nx, a.y + ny), strokeWidth = 2f)
    drawLine(color, Offset(b.x - nx, b.y - ny), Offset(b.x + nx, b.y + ny), strokeWidth = 2f)
}

// ---------------------------------------------------------------------------
// Fine-tune row
// ---------------------------------------------------------------------------

@Composable
private fun FineTuneRow(
    label: String,
    value: String,
    accent: Color,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        SectionLabel(label, accent)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NudgeButton("-", onClick = onDecrease)
            Text(
                text = value,
                color = accent,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            NudgeButton("+", onClick = onIncrease)
        }
    }
}

@Composable
private fun NudgeButton(symbol: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = WorkshopPalette.SurfaceRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.size(30.dp),
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                text = symbol,
                color = WorkshopPalette.TextPrimary,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers (unit-aware, Locale-stable)
// ---------------------------------------------------------------------------

/** Format a millimetre measurement in the preferred unit. */
fun formatMeasured(mm: Double, unit: UnitSystem): String {
    if (mm.isNaN() || mm.isInfinite()) return "-"
    return if (unit == UnitSystem.MM) {
        String.format(java.util.Locale.US, "%.2f mm", mm)
    } else {
        String.format(java.util.Locale.US, "%.3f in", mm / AppConfig.MM_PER_INCH)
    }
}

/** Signed delta label, e.g. "+0.08 mm" / "-0.003 in". */
fun formatDelta(deltaMm: Double, unit: UnitSystem): String {
    val sign = if (deltaMm >= 0) "+" else ""
    return if (unit == UnitSystem.MM) {
        String.format(java.util.Locale.US, "$sign%.2f mm", deltaMm)
    } else {
        String.format(java.util.Locale.US, "$sign%.3f in", deltaMm / AppConfig.MM_PER_INCH)
    }
}
