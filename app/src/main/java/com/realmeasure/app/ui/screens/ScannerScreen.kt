package com.realmeasure.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.realmeasure.app.ui.HistoryViewModel
import com.realmeasure.app.ui.ScanUiState
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.components.ErrorModal
import com.realmeasure.app.ui.components.ModeToggle
import com.realmeasure.app.ui.components.ResultModal
import com.realmeasure.app.ui.components.ScannerOverlay
import com.realmeasure.app.ui.components.UnitToggle
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Hands-free scanner screen.
 *
 * A CameraX [ImageAnalysis] use case feeds OpenCV zone checks at ~6-7 fps.
 * The Canvas HUD turns each alignment frame emerald as soon as its zone is
 * valid; when both are green for ~750 ms the ViewModel locks, runs the full
 * perspective-corrected measurement and opens the result modal. There is no
 * capture button.
 */
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    historyViewModel: HistoryViewModel,
    onShowOnboarding: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val saving by historyViewModel.saving.collectAsStateWithLifecycle()
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
            .statusBarsPadding(),
    ) {
        ScannerHeader(
            mode = ui.mode,
            unit = ui.unit,
            onMode = viewModel::setMode,
            onUnit = viewModel::setUnit,
            onShowOnboarding = onShowOnboarding,
        )

        GuidancePill(scan = ui.scan)

        if (hasPermission) {
            CameraStage(
                scanState = ui.scan,
                torchOn = ui.torch,
                onTorch = viewModel::toggleTorch,
                onFrame = viewModel::onFrame,
            )
        } else {
            PermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )
        }
    }

    // Modals open ONLY from the two terminal states, and dismissing them
    // resumes the live pipeline immediately.
    when (val scan = ui.scan) {
        is ScanUiState.Success -> ResultModal(
            success = scan,
            saving = saving,
            onSave = {
                historyViewModel.save(
                    result = scan.result,
                    mode = scan.scanMode,
                    bitmap = scan.rawCroppedBitmap,
                )
            },
            onScanAnother = viewModel::resumeScanning,
        )
        is ScanUiState.Error -> ErrorModal(
            error = scan,
            onTryAgain = viewModel::resumeScanning,
        )
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Header + guidance
// ---------------------------------------------------------------------------

@Composable
private fun ScannerHeader(
    mode: ScanMode,
    unit: UnitSystem,
    onMode: (ScanMode) -> Unit,
    onUnit: (UnitSystem) -> Unit,
    onShowOnboarding: () -> Unit,
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
            Column(Modifier.weight(1f)) {
                Text(
                    text = AppConfig.APP_NAME.uppercase(),
                    color = WorkshopPalette.TextPrimary,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    text = "HANDS-FREE \u00B7 PRECISION \u00B7 OFFLINE",
                    color = WorkshopPalette.TextFaint,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                    letterSpacing = 1.3.sp,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnitToggle(unit = unit, onChange = onUnit)
                IconButton(onClick = onShowOnboarding) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = "How to scan",
                        tint = WorkshopPalette.TextMuted,
                    )
                }
            }
        }
        ModeToggle(mode = mode, onChange = onMode)
    }
}

/** High-contrast instruction pill that tracks the live state machine. */
@Composable
private fun GuidancePill(scan: ScanUiState) {
    val message: String
    val accent: Color
    val analyzing = scan is ScanUiState.Analyzing

    when (scan) {
        is ScanUiState.Scanning -> {
            message = scan.guidanceMessage
            accent = if (scan.cardDetected && scan.fastenerDetected) {
                WorkshopPalette.Emerald
            } else {
                WorkshopPalette.Amber
            }
        }
        ScanUiState.Analyzing -> {
            message = "Analyzing measurement\u2026"
            accent = WorkshopPalette.Cyan
        }
        else -> return
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (analyzing) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            Text(
                text = message,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Camera stage with live analysis
// ---------------------------------------------------------------------------

@Composable
private fun CameraStage(
    scanState: ScanUiState,
    torchOn: Boolean,
    onTorch: () -> Unit,
    onFrame: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "realmeasure-camera-analysis").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
    val imageAnalysis = remember {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(
                        AppConfig.Live.ANALYSIS_WIDTH_PX,
                        AppConfig.Live.ANALYSIS_HEIGHT_PX,
                    ),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()
    }
    val lastAnalyzedAt = remember { AtomicLong(0L) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var bindAttempt by remember { mutableIntStateOf(0) }

    val latestScanState = rememberUpdatedState(scanState)
    val latestOnFrame = rememberUpdatedState(onFrame)

    LaunchedEffect(bindAttempt) {
        cameraError = null
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await(context)
            provider = cameraProvider

            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build(),
                )
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                if (latestScanState.value !is ScanUiState.Scanning) {
                    proxy.close()
                    return@setAnalyzer
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalyzedAt.get() < AppConfig.Live.FRAME_INTERVAL_MS) {
                    proxy.close()
                    return@setAnalyzer
                }
                lastAnalyzedAt.set(now)

                val frame = proxy.toUprightStageBitmap()
                proxy.close()
                if (frame != null) {
                    latestOnFrame.value(frame)
                }
            }

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
        } catch (exception: Exception) {
            cameraError = exception.message ?: "Camera failed to start."
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    DisposableEffect(Unit) {
        onDispose {
            imageAnalysis.clearAnalyzer()
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    val scanning = scanState as? ScanUiState.Scanning

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ScannerOverlay(
                cardDetected = scanning?.cardDetected == true,
                fastenerDetected = scanning?.fastenerDetected == true,
                stabilityProgress = scanning?.stabilityProgress ?: 0f,
            )

            cameraError?.let { message ->
                CameraErrorOverlay(message = message, onRetry = { bindAttempt += 1 })
            }

            Surface(
                shape = CircleShape,
                color = WorkshopPalette.Background.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, WorkshopPalette.Border),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                IconButton(
                    onClick = onTorch,
                    enabled = hasFlash,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                        tint = if (torchOn) WorkshopPalette.Amber else WorkshopPalette.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "No shutter \u00B7 both frames green for a moment = measured",
            color = WorkshopPalette.TextFaint,
            fontSize = 10.sp,
        )
        Text(
            text = "NO PHOTO LEAVES THIS DEVICE",
            color = WorkshopPalette.TextFaint,
            fontFamily = Mono,
            fontSize = 8.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun CameraErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkshopPalette.Background.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = WorkshopPalette.Amber,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "Camera unavailable",
                color = WorkshopPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                text = message,
                color = WorkshopPalette.TextMuted,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(50),
                color = WorkshopPalette.Amber,
            ) {
                Text(
                    text = "RETRY CAMERA",
                    color = WorkshopPalette.Background,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.1.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
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
            text = "RealMeasure uses the rear camera to calibrate against a bank/ID card and measure fasteners in real time. Photos never leave your device.",
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

/**
 * Convert an RGBA_8888 ImageProxy into an upright bitmap centre-cropped to the
 * 3:4 viewfinder stage. Guide coordinates then map 1:1 to analysed pixels.
 */
private fun ImageProxy.toUprightStageBitmap(): Bitmap? {
    return try {
        // CameraX handles YUV_420_888 (and JPEG) conversion for us; doing it
        // by hand on the RGBA plane produced all-black frames on several
        // MediaTek devices.
        var current: Bitmap = toBitmap()

        val rotation = imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (rotated !== current) current.recycle()
            current = rotated
        }

        val targetAspect = AppConfig.Capture.STAGE_ASPECT
        val aspect = current.width.toDouble() / current.height
        if (abs(aspect - targetAspect) > 0.01) {
            val cropped = if (aspect > targetAspect) {
                val targetWidth = (current.height * targetAspect).toInt()
                Bitmap.createBitmap(
                    current,
                    (current.width - targetWidth) / 2,
                    0,
                    targetWidth.coerceAtLeast(1),
                    current.height,
                )
            } else {
                val targetHeight = (current.width / targetAspect).toInt()
                Bitmap.createBitmap(
                    current,
                    0,
                    (current.height - targetHeight) / 2,
                    current.width,
                    targetHeight.coerceAtLeast(1),
                )
            }
            if (cropped !== current) current.recycle()
            current = cropped
        }

        if (current.width > AppConfig.Live.MEASURE_MAX_WIDTH_PX) {
            val targetWidth = AppConfig.Live.MEASURE_MAX_WIDTH_PX
            val targetHeight = (current.height.toDouble() * targetWidth / current.width).toInt()
            val scaled = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
            if (scaled !== current) current.recycle()
            current = scaled
        }
        current
    } catch (throwable: Throwable) {
        null
    }
}
