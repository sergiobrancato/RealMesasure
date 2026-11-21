package com.realmeasure.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.HelpOutline
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.data.ScanHistoryItem
import com.realmeasure.app.model.ScanMode
import com.realmeasure.app.ui.Formats
import com.realmeasure.app.ui.HistoryViewModel
import com.realmeasure.app.ui.components.InfoChip
import com.realmeasure.app.ui.components.SectionLabel
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "My Hardware" tab: dark workshop cards with the saved photo thumbnail, the
 * identified size, a type badge and the scan date. Every card has a delete
 * button with a confirmation dialog.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onShowOnboarding: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ScanHistoryItem?>(null) }

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
                    text = "MY HARDWARE",
                    color = WorkshopPalette.TextPrimary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(
                    text = "${items.size} SAVED SCAN${if (items.size == 1) "" else "S"} \u00B7 ON-DEVICE",
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

        if (items.isEmpty()) {
            EmptyHistory()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    HistoryCard(item = item, onDelete = { pendingDelete = item })
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = WorkshopPalette.Surface,
            titleContentColor = WorkshopPalette.TextPrimary,
            textContentColor = WorkshopPalette.TextMuted,
            title = { Text(text = "Delete scan?") },
            text = {
                Text(
                    text = "Remove ${item.primaryLabel} from My Hardware? This also deletes its saved photo.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
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
private fun HistoryCard(item: ScanHistoryItem, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(path = item.imagePath)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TypeBadge(item.scanMode)
                    Text(
                        text = Formats.timestamp(item.timestamp),
                        color = WorkshopPalette.TextFaint,
                        fontFamily = Mono,
                        fontSize = 8.sp,
                    )
                }
                Text(
                    text = item.primaryLabel,
                    color = if (item.scanMode == ScanMode.HEX_KEY) WorkshopPalette.Cyan else WorkshopPalette.Amber,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.secondaryAlternative?.let { alternative ->
                    Text(
                        text = alternative,
                        color = WorkshopPalette.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = Formats.hardwareSummary(item.rawDiameterMm, item.rawLengthMm),
                    color = WorkshopPalette.TextFaint,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete ${item.primaryLabel}",
                    tint = WorkshopPalette.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(80.dp)
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
private fun TypeBadge(mode: ScanMode) {
    val hex = mode == ScanMode.HEX_KEY
    InfoChip(
        text = if (hex) "HEX KEY" else "SCREW / BOLT",
        accent = if (hex) WorkshopPalette.Cyan else WorkshopPalette.Amber,
    )
}

@Composable
private fun EmptyHistory() {
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
            text = "No saved hardware yet",
            color = WorkshopPalette.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Measure a screw, bolt or hex key, then tap \u201CSave to My Hardware\u201D on the result card. Your scans stay on this device.",
            color = WorkshopPalette.TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SectionLabel("REALMEASURE \u00B7 ${AppConfig.APP_VERSION}", WorkshopPalette.TextFaint)
    }
}
