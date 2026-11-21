package com.realmeasure.app.ui.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.ui.Formats
import com.realmeasure.app.ui.ScanUiState
import com.realmeasure.app.ui.theme.Mono

/**
 * Result modal shown only while [ScanUiState.Success] is current.
 *
 * Displays the annotated capture, the snapped standard, the raw measured
 * values and the two actions required by the workflow: save to "My Hardware"
 * or scan another part.
 */
@Composable
fun ResultModal(
    success: ScanUiState.Success,
    saving: Boolean,
    onSave: () -> Unit,
    onScanAnother: () -> Unit,
) {
    var saved by remember(success) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onScanAnother,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = WorkshopPalette.Surface,
                border = BorderStroke(1.dp, WorkshopPalette.Amber.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 760.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel("SCAN RESULT", WorkshopPalette.Amber)
                        Spacer(Modifier.size(8.dp))
                        InfoChip(
                            text = success.scanMode.label.uppercase(),
                            accent = WorkshopPalette.Amber,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onScanAnother) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close and scan another",
                                tint = WorkshopPalette.TextMuted,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WorkshopPalette.Sunken),
                    ) {
                        Image(
                            bitmap = success.annotatedBitmap.asImageBitmap(),
                            contentDescription = "Annotated scan",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Text(
                        text = success.result.primaryLabel,
                        color = WorkshopPalette.Amber,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                    )

                    success.result.secondaryAlternative?.let { alternative ->
                        Text(
                            text = alternative,
                            color = WorkshopPalette.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    success.result.pitchInfo?.let { pitch ->
                        Text(
                            text = "Pitch: $pitch",
                            color = WorkshopPalette.TextMuted,
                            fontFamily = Mono,
                            fontSize = 11.sp,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WorkshopPalette.Sunken, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ValueCell(
                            label = if (success.scanMode.name == "HEX_KEY") "ACROSS FLATS" else "SHAFT",
                            value = Formats.mm(success.result.rawDiameterMm),
                            modifier = Modifier.weight(1f),
                            accent = WorkshopPalette.Cyan,
                        )
                        if (success.result.rawLengthMm != null) {
                            ValueCell(
                                label = "LENGTH",
                                value = Formats.length(success.result.rawLengthMm),
                                modifier = Modifier.weight(1f),
                                accent = WorkshopPalette.Amber,
                            )
                        }
                        ValueCell(
                            label = "MATCH",
                            value = "\u00B1${Formats.matchError(success.result.diameterErrorMm)}",
                            modifier = Modifier.weight(1f),
                            accent = WorkshopPalette.Emerald,
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    Surface(
                        onClick = {
                            if (!saved && !saving) {
                                onSave()
                                saved = true
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (saved) WorkshopPalette.SurfaceRaised else WorkshopPalette.Amber,
                        border = if (saved) BorderStroke(1.dp, WorkshopPalette.Border) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 15.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = null,
                                tint = if (saved) WorkshopPalette.Emerald else WorkshopPalette.Background,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = when {
                                    saved -> "SAVED TO MY HARDWARE"
                                    saving -> "SAVING..."
                                    else -> "SAVE TO MY HARDWARE"
                                },
                                color = if (saved) WorkshopPalette.Emerald else WorkshopPalette.Background,
                                fontFamily = Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.1.sp,
                            )
                        }
                    }

                    Surface(
                        onClick = onScanAnother,
                        shape = RoundedCornerShape(50),
                        color = WorkshopPalette.Surface,
                        border = BorderStroke(1.dp, WorkshopPalette.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "SCAN ANOTHER",
                            color = WorkshopPalette.TextSecondary,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.1.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueCell(
    label: String,
    value: String,
    modifier: Modifier,
    accent: androidx.compose.ui.graphics.Color,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = value,
            color = accent,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}
