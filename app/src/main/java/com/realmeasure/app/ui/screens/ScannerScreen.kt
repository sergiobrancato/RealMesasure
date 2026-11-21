package com.realmeasure.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.util.concurrent.ListenableFuture
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.model.ReferenceMode
import com.realmeasure.app.ui.ScanUiState
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.ScansViewModel
import com.realmeasure.app.ui.components.ErrorModal
import com.realmeasure.app.ui.components.ReferenceModeSelector
import com.realmeasure.app.ui.components.ScannerOverlay
import com.realmeasure.app.ui.theme.Mono
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Hands-free planar scanner screen.
 *
 * The preview stream runs the cheap reference detector at ~7 fps. When the
 * selected reference is stable, the ViewModel emits a capture request and this
 * screen grabs a full-resolution ImageCapture still at MAXIMIZE_QUALITY, which
 * is then flattened and traced by the ViewModel.
 */
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    scansViewModel: ScansViewModel,
    onShowOnboarding: () -> Unit,
) {
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
            .statusBarsPadding(),
    ) {
        ScannerHeader(onShowOnboarding = onShowOnboarding)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReferenceModeSelector(
                mode = ui.mode,
                onChange = viewModel::setMode,
            )

            GuidancePill(scan = ui.scan)

            if (hasPermission) {
                CameraStage(
                    mode = ui.mode,
                    scanState = ui.scan,
                    torchOn = ui.torch,
                    onFrame = viewModel::onPreviewFrame,
                    onStill = viewModel::onStillCaptured,
                    onCaptureFailed = viewModel::onCaptureFailed,
                    onTorch = viewModel::toggleTorch,
                )
            } else {
                PermissionRationale(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { context.openAppSettings() },
                )
            }

            Text(
                text = ui.mode.sizeHint,
                color = WorkshopPalette.TextFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "NO SHUTTER · STABLE REFERENCE → AUTO CAPTURE",
                color = WorkshopPalette.TextFaint,
                fontFamily = Mono,
                fontSize = 8.sp,
                letterSpacing = 1.0.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
        }
    }

    when (val scan = ui.scan) {
        is ScanUiState.Success -> ResultScreen(
            analysis = scan.analysis,
            scansViewModel = scansViewModel,
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
private fun ScannerHeader(onShowOnboarding: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
                    text = "PLANAR METROLOGY · 1:1 CAD",
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
    }
}

@Composable
private fun GuidancePill(scan: ScanUiState) {
    val message: String
    val accent: Color
    val analyzing = scan is ScanUiState.Analyzing

    when (scan) {
        is ScanUiState.Scanning -> {
            message = scan.guidanceMessage
            accent = when {
                scan.referenceDetected &&
                    scan.tiltDeg <= AppConfig.Detection.MAX_LIVE_TILT_DEG -> WorkshopPalette.Emerald
                else -> WorkshopPalette.Amber
            }
        }
        ScanUiState.Analyzing -> {
            message = "Flattening scene · tracing contours…"
            accent = WorkshopPalette.Cyan
        }
        else -> return
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
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
// Camera stage
// ---------------------------------------------------------------------------

@Composable
private fun CameraStage(
    mode: ReferenceMode,
    scanState: ScanUiState,
    torchOn: Boolean,
    onFrame: (Bitmap) -> Unit,
    onStill: (Bitmap) -> Unit,
    onCaptureFailed: (String) -> Unit,
    onTorch: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "realmeasure-preview-analysis").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
    val captureExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "realmeasure-still-capture")
        }
    }
    val imageAnalysis = remember {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(
                        AppConfig.Detection.WORKING_WIDTH_PX * 4 / 3,
                        AppConfig.Detection.WORKING_WIDTH_PX,
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
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val lastAnalyzedAt = remember { AtomicLong(0L) }
    val capturing = remember { AtomicBoolean(false) }

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
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                if (latestScanState.value !is ScanUiState.Scanning) {
                    proxy.close()
                    return@setAnalyzer
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalyzedAt.get() < AppConfig.Detection.FRAME_INTERVAL_MS) {
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
                imageCapture,
            )
        } catch (exception: Exception) {
            cameraError = exception.message ?: "Camera failed to start."
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    // Full-resolution capture triggered by the stability lock. This is driven
    // by the state itself (not a separate event) so a request can never race a
    // stale composition.
    val latestOnStill = rememberUpdatedState(onStill)
    val latestOnCaptureFailed = rememberUpdatedState(onCaptureFailed)
    LaunchedEffect(scanState, camera) {
        if (scanState !is ScanUiState.Analyzing || camera == null) return@LaunchedEffect
        if (!capturing.compareAndSet(false, true)) return@LaunchedEffect
        try {
            imageCapture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = image.toUprightBitmap()
                            if (bitmap != null) {
                                latestOnStill.value(bitmap)
                            } else {
                                latestOnCaptureFailed.value("Could not decode the captured photo.")
                            }
                        } catch (throwable: Throwable) {
                            latestOnCaptureFailed.value(
                                throwable.message ?: "Could not decode the captured photo.",
                            )
                        } finally {
                            image.close()
                            capturing.set(false)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing.set(false)
                        latestOnCaptureFailed.value(
                            exception.message ?: "The camera could not take the still photo.",
                        )
                    }
                },
            )
        } catch (throwable: Throwable) {
            capturing.set(false)
            latestOnCaptureFailed.value(
                throwable.message ?: "The camera could not take the still photo.",
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            imageAnalysis.clearAnalyzer()
            provider?.unbindAll()
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }

    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    val scanning = scanState as? ScanUiState.Scanning
    val analyzing = scanState is ScanUiState.Analyzing

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ScannerOverlay(
                mode = mode,
                scanning = scanning,
            )

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
                        imageVector = if (torchOn) {
                            Icons.Filled.FlashlightOn
                        } else {
                            Icons.Filled.FlashlightOff
                        },
                        contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                        tint = if (torchOn) WorkshopPalette.Amber else WorkshopPalette.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (analyzing) {
                AnalyzingOverlay()
            }

            cameraError?.let { message ->
                CameraErrorOverlay(message = message, onRetry = { bindAttempt += 1 })
            }
        }
    }
}

@Composable
private fun AnalyzingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkshopPalette.Background.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                color = WorkshopPalette.Cyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = "FLATTENING SCENE",
                color = WorkshopPalette.Cyan,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = "Full-resolution capture · corner refinement · contour tracing",
                color = WorkshopPalette.TextMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
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
                imageVector = Icons.Filled.Warning,
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
                textAlign = TextAlign.Center,
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
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = null,
            tint = WorkshopPalette.Amber,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = "Camera access needed",
            color = WorkshopPalette.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "RealMeasure finds an ID card or A4 sheet, flattens the scene through a " +
                "planar homography and traces object contours. Photos never leave this device.",
            color = WorkshopPalette.TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        Surface(
            onClick = onRequest,
            shape = CircleShape,
            color = WorkshopPalette.Amber,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(
                text = "ALLOW CAMERA",
                color = WorkshopPalette.Background,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 13.dp),
            )
        }
        Surface(
            onClick = onOpenSettings,
            shape = CircleShape,
            color = WorkshopPalette.Surface,
            border = BorderStroke(1.dp, WorkshopPalette.Border),
        ) {
            Text(
                text = "OPEN SETTINGS",
                color = WorkshopPalette.Cyan,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
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
 * Convert a preview ImageProxy to an upright bitmap centre-cropped to the 3:4
 * viewfinder stage. Reference polygon coordinates are normalised to this crop,
 * so the overlay maps 1:1 to the preview.
 */
private fun ImageProxy.toUprightStageBitmap(): Bitmap? {
    return try {
        var current: Bitmap = toBitmap()
        val rotation = imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (rotated !== current) current.recycle()
            current = rotated
        }

        val targetAspect = 3.0 / 4.0
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

        val maxWidth = AppConfig.Detection.WORKING_WIDTH_PX * 2
        if (current.width > maxWidth) {
            val height = (current.height.toDouble() * maxWidth / current.width).toInt()
            val scaled = Bitmap.createScaledBitmap(current, maxWidth, height, true)
            if (scaled !== current) current.recycle()
            current = scaled
        }
        current
    } catch (throwable: Throwable) {
        null
    }
}

/**
 * Full-resolution still, rotated upright but **not** cropped: the calibrator
 * needs the whole sensor image so the translated homography can preserve every
 * pixel of the scene.
 *
 * JPEG stills are decoded with a power-of-two inSampleSize so a 48–108 MP
 * sensor never allocates a full-size ARGB bitmap. The decoded long edge is
 * capped at [AppConfig.Calibration.MAX_SOURCE_WIDTH_PX] pixels, which is still
 * far more resolution than the 4096 px warp canvas can use.
 */
private fun ImageProxy.toUprightBitmap(): Bitmap? {
    return try {
        var current: Bitmap = if (format == ImageFormat.JPEG) {
            decodeJpegSampled() ?: return null
        } else {
            toBitmap()
        }
        val rotation = imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (rotated !== current) current.recycle()
            current = rotated
        }
        current
    } catch (throwable: Throwable) {
        null
    }
}

private fun ImageProxy.decodeJpegSampled(): Bitmap? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (bytes.isEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val maxEdge = AppConfig.Calibration.MAX_SOURCE_WIDTH_PX
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
