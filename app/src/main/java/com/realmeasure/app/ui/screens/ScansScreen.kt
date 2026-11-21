package com.realmeasure.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.data.ScanRecord
import com.realmeasure.app.export.SvgExporter
import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.ui.Formats
import com.realmeasure.app.ui.ScansViewModel
import com.realmeasure.app.ui.components.InfoChip
import com.realmeasure.app.ui.components.SectionLabel
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * "My Scans" tab: persisted planar measurements with the annotated flattened
 * thumbnail, dimensions and uncertainty, area, hole count, SVG re-export and
 * a delete confirmation.
 */
@Composable
fun ScansScreen(
    viewModel: ScansViewModel,
    onShowOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val records by viewModel.records.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ScanRecord?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkshopPalette.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MY SCANS",
                    color = WorkshopPalette.TextPrimary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(
                    text = "${records.size} SAVED SCAN${if (records.size == 1) "" else "S"} · ON-DEVICE",
                    color = WorkshopPalette.TextFaint,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            IconButton(onClick = onShowOnboarding) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = "How to scan",
                    tint = WorkshopPalette.TextMuted,
                )
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyScans()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    ScanCard(
                        record = record,
                        onExport = {
                            if (record.svgOuter.size >= 3) {
                                SvgExporter.exportAndShare(
                                    context = context,
                                    outerMm = record.svgOuter,
                                    holesMm = record.svgHoles,
                                    title = "RealMeasure_${record.mode.name}_" +
                                        "${record.lengthMm.roundToInt()}mm",
                                )
                            }
                        },
                        onDelete = { pendingDelete = record },
                    )
                }
            }
        }
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = WorkshopPalette.Surface,
            titleContentColor = WorkshopPalette.TextPrimary,
            textContentColor = WorkshopPalette.TextMuted,
            title = { Text(text = "Delete scan?") },
            text = {
                Text(
                    text = "Remove ${Formats.dimensionsWithError(
                        record.lengthMm,
                        record.lengthErrorMm,
                        record.widthMm,
                        record.widthErrorMm,
                    )} from My Scans? This also deletes the saved image.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(record)
                    pendingDelete = null
                }) {
                    Text(
                        text = "DELETE",
                        color = WorkshopPalette.Danger,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(
                        text = "CANCEL",
                        color = WorkshopPalette.TextMuted,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Cards + empty state
// ---------------------------------------------------------------------------

@Composable
private fun ScanCard(
    record: ScanRecord,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Thumbnail(path = record.imagePath)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoChip(
                            text = if (record.mode.name == "CARD") "ID CARD" else "A4 SHEET",
                            accent = if (record.mode.name == "CARD") {
                                WorkshopPalette.Cyan
                            } else {
                                WorkshopPalette.Amber
                            },
                        )
                        Text(
                            text = Formats.timestamp(record.timestamp),
                            color = WorkshopPalette.TextFaint,
                            fontFamily = Mono,
                            fontSize = 8.sp,
                        )
                    }
                    Text(
                        text = Formats.dimensionsWithError(
                            record.lengthMm,
                            record.lengthErrorMm,
                            record.widthMm,
                            record.widthErrorMm,
                        ),
                        color = WorkshopPalette.Cyan,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${Formats.cm2WithError(record.areaCm2, record.areaErrorCm2)} · " +
                            Formats.count(record.holeCount, "hole", "holes"),
                        color = WorkshopPalette.TextMuted,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(qualityColour(record.quality), CircleShape),
                        )
                        Text(
                            text = record.quality.label.uppercase(),
                            color = qualityColour(record.quality),
                            fontFamily = Mono,
                            fontSize = 8.sp,
                            letterSpacing = 0.8.sp,
                        )
                        Text(
                            text = "· P ${record.perimeterMm.roundToInt()} mm",
                            color = WorkshopPalette.TextFaint,
                            fontFamily = Mono,
                            fontSize = 8.sp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onExport,
                    shape = RoundedCornerShape(50),
                    color = WorkshopPalette.Sunken,
                    border = BorderStroke(1.dp, WorkshopPalette.Border),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            tint = WorkshopPalette.Cyan,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "RE-EXPORT 1:1 SVG",
                            color = WorkshopPalette.Cyan,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.7.sp,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete scan",
                        tint = WorkshopPalette.Danger.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampled(path, 256) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(82.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(WorkshopPalette.Sunken),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = null,
            tint = WorkshopPalette.TextFaint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EmptyScans() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = WorkshopPalette.Surface,
            border = BorderStroke(1.dp, WorkshopPalette.Border),
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = WorkshopPalette.Amber,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "No saved scans yet",
            color = WorkshopPalette.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Flatten a bracket, gasket, board or template, then tap “Save to My Scans” " +
                "on the result screen. Every scan stays on this device.",
            color = WorkshopPalette.TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SectionLabel("REALMEASURE · ${AppConfig.APP_VERSION}", WorkshopPalette.TextFaint)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun qualityColour(quality: MeasurementQuality) = when (quality) {
    MeasurementQuality.HIGH -> WorkshopPalette.Emerald
    MeasurementQuality.MODERATE -> WorkshopPalette.Amber
    MeasurementQuality.LOW -> WorkshopPalette.Danger
}

private fun decodeSampled(path: String, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > targetPx * 2 &&
        bounds.outHeight / sample > targetPx * 2
    ) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
