package com.realmeasure.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.util.concurrent.ListenableFuture
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.ScanMode
import com.realmeasure.app.model.UnitSystem
import com.realmeasure.app.ui.ScanError
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.components.InfoChip
import com.realmeasure.app.ui.components.ModeToggle
import com.realmeasure.app.ui.components.ScannerOverlay
import com.realmeasure.app.ui.components.UnitToggle
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Live scanner screen.
 *
 * CameraX preview + Canvas HUD live in a fixed 3:4 stage. Captures are
 * centre-cropped to that same aspect ratio before analysis, so the guide
 * coordinates map exactly to analysed pixels. Torch, mode and unit controls
 * stay within thumb reach.
 */
@Composable
fun ScannerScreen(viewModel: ScannerViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionRequested) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkshopPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScannerHeader(
            mode = ui.mode,
            unit = ui.unit,
            onMode = viewModel::setMode,
            onUnit = viewModel::setUnit,
        )

        if (hasPermission) {
            CameraStage(
                torchOn = ui.torch,
                onTorch = viewModel::toggleTorch,
                onCaptureSaved = viewModel::processCapture,
                onCaptureError = viewModel::onCaptureFailed,
            )
        } else {
            PermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )
        }

        ui.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            ErrorBanner(error = error, onDismiss = viewModel::clearError)
        }
    }

    if (ui.processing) {
        ProcessingOverlay(step = ui.processingStep)
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun ScannerHeader(
    mode: ScanMode,
    unit: UnitSystem,
    onMode: (ScanMode) -> Unit,
    onUnit: (UnitSystem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = AppConfig.APP_NAME.uppercase(),
                    color = WorkshopPalette.TextPrimary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    text = "PRECISION - OFFLINE",
                    color = WorkshopPalette.TextFaint,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                    letterSpacing = 1.5.sp,
                )
            }
            UnitToggle(unit = unit, onChange = onUnit)
        }
        ModeToggle(mode = mode, onChange = onMode)
    }
}

// ---------------------------------------------------------------------------
// Camera stage
// ---------------------------------------------------------------------------

@Composable
private fun CameraStage(
    torchOn: Boolean,
    onTorch: () -> Unit,
    onCaptureSaved: (File) -> Unit,
    onCaptureError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).await(context)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        } catch (exception: Exception) {
            onCaptureError("Camera failed to start: ${exception.message}")
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ScannerOverlay()
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onTorch, enabled = hasFlash) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                    tint = if (torchOn) WorkshopPalette.Amber else WorkshopPalette.TextMuted,
                )
            }

            Spacer(Modifier.size(28.dp))

            ShutterButton(onTap = {
                val photoFile = File(context.cacheDir, "realmeasure_scan.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onCaptureSaved(photoFile)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            onCaptureError(exception.message ?: "Capture failed")
                        }
                    },
                )
            })

            Spacer(Modifier.size(28.dp))

            Icon(
                imageVector = Icons.Filled.Construction,
                contentDescription = null,
                tint = WorkshopPalette.TextFaint,
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "NO PHOTO LEAVES THIS DEVICE",
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Lay a bank/ID card flat in the amber frame, the fastener vertically in the cyan zone, then tap the shutter.",
            color = WorkshopPalette.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
    }
}

/** Big amber shutter button. */
@Composable
private fun ShutterButton(onTap: () -> Unit) {
    Surface(
        onClick = onTap,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(2.dp, WorkshopPalette.Amber),
        modifier = Modifier.size(84.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Surface(
                shape = CircleShape,
                color = WorkshopPalette.Amber,
                modifier = Modifier.size(62.dp),
            ) {}
        }
    }
}

// ---------------------------------------------------------------------------
// Permission rationale
// ---------------------------------------------------------------------------

@Composable
private fun PermissionRationale(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = null,
            tint = WorkshopPalette.Amber,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Camera access needed",
            color = WorkshopPalette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "RealMeasure uses the rear camera to calibrate against a bank/ID card and measure fasteners. Photos never leave your device.",
            color = WorkshopPalette.TextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = onRequest,
            shape = CircleShape,
            color = WorkshopPalette.Amber,
        ) {
            Text(
                text = "ALLOW CAMERA",
                color = WorkshopPalette.Background,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "If the system no longer shows a prompt, enable Camera for RealMeasure in Settings.",
            color = WorkshopPalette.TextFaint,
            fontSize = 11.sp,
        )
        Surface(
            onClick = onOpenSettings,
            shape = CircleShape,
            color = WorkshopPalette.Surface,
            border = BorderStroke(1.dp, WorkshopPalette.Border),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text(
                text = "OPEN SETTINGS",
                color = WorkshopPalette.Cyan,
                fontFamily = Mono,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Processing + error
// ---------------------------------------------------------------------------

@Composable
private fun ProcessingOverlay(step: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkshopPalette.Background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = WorkshopPalette.Surface,
            border = BorderStroke(1.dp, WorkshopPalette.Border),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = WorkshopPalette.Cyan)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Measuring on-device",
                    color = WorkshopPalette.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = step,
                    color = WorkshopPalette.TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                InfoChip("NO NETWORK - NO CLOUD", WorkshopPalette.Cyan)
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: ScanError, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = WorkshopPalette.Surface,
        border = BorderStroke(1.dp, WorkshopPalette.Danger.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = WorkshopPalette.Danger,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = error.title,
                    color = WorkshopPalette.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    text = error.message,
                    color = WorkshopPalette.TextSecondary,
                    fontSize = 12.sp,
                )
                error.hint?.let { hint ->
                    Text(
                        text = hint,
                        color = WorkshopPalette.TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = WorkshopPalette.SurfaceRaised,
                border = BorderStroke(1.dp, WorkshopPalette.Border),
            ) {
                Text(
                    text = "RETRY",
                    color = WorkshopPalette.Cyan,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun android.content.Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    startActivity(intent)
}

/** Await a CameraX ListenableFuture on the main executor. */
private suspend fun <T> ListenableFuture<T>.await(context: android.content.Context): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (exception: Exception) {
                    continuation.resumeWithException(exception)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
