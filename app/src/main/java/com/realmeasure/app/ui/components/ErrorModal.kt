package com.realmeasure.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.ui.ScanUiState
import com.realmeasure.app.ui.theme.Mono

/**
 * Scan-failed modal. Shown only while [ScanUiState.Error] is current; the
 * single action resumes live scanning immediately.
 */
@Composable
fun ErrorModal(
    error: ScanUiState.Error,
    onTryAgain: () -> Unit,
) {
    Dialog(
        onDismissRequest = onTryAgain,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = WorkshopPalette.Surface,
                border = BorderStroke(1.dp, WorkshopPalette.Danger.copy(alpha = 0.40f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = WorkshopPalette.Danger,
                        modifier = Modifier.size(38.dp),
                    )
                    Text(
                        text = "SCAN FAILED",
                        color = WorkshopPalette.Danger,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.6.sp,
                    )
                    Text(
                        text = error.title,
                        color = WorkshopPalette.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = error.reason,
                        color = WorkshopPalette.TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        onClick = onTryAgain,
                        shape = RoundedCornerShape(50),
                        color = WorkshopPalette.Amber,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "TRY AGAIN",
                            color = WorkshopPalette.Background,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.2.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 15.dp),
                        )
                    }
                }
            }
        }
    }
}
