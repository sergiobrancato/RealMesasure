package com.realmeasure.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.cv.CalibrationException
import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.cv.IrregularShapeAnalyzer
import com.realmeasure.app.cv.PixelRect
import com.realmeasure.app.cv.PrecisionCalibrator
import com.realmeasure.app.cv.ReferenceDetector
import com.realmeasure.app.cv.ScanAnalysis
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

// ---------------------------------------------------------------------------
// Guidance copy
// ---------------------------------------------------------------------------

private const val GUIDANCE_CARD = "Place ID card flat beside your object"
private const val GUIDANCE_A4 = "Place A4 sheet flat beside your object"
private const val GUIDANCE_TILT = "Hold phone flatter to reduce tilt"
private const val GUIDANCE_LOCKED = "Reference locked! Hold steady..."
private const val GUIDANCE_DARK = "Scene is dark — tap the flashlight for accuracy"

/** Hard limit for capture + full-resolution analysis. */
private const val SCAN_TIMEOUT_MS = 6_000L

private const val TIMEOUT_TITLE = "Scan Timed Out"
private const val TIMEOUT_REASON =
    "Could not process frame. Ensure good lighting and hold steady."

/**
 * Strict scanner state machine.
 *
 * Exactly one of these values is live at a time. Frames are consumed only in
 * [Scanning]; [Analyzing] carries a token identifying the capture/analysis
 * generation, and [Success] / [Error] keep the pipeline paused until dismissed.
 */
sealed interface ScanUiState {

    data class Scanning(
        val referenceDetected: Boolean,
        val guidanceMessage: String,
        val stabilityProgress: Float,
        val referenceQuad: List<Pt>?,
        val tiltDeg: Double,
    ) : ScanUiState

    /** [token] prevents a stale capture from completing a newer scan. */
    data class Analyzing(val token: Long) : ScanUiState

    data class Success(val analysis: ScanAnalysis) : ScanUiState

    data class Error(
        val title: String,
        val reason: String,
    ) : ScanUiState
}

data class ScannerUi(
    val scan: ScanUiState,
    val mode: ReferenceMode = ReferenceMode.CARD,
    val torch: Boolean = false,
    /** True while a GrabCut refinement is running on the result screen. */
    val refining: Boolean = false,
)

/**
 * Hands-free planar scanner.
 *
 * All preview and still processing runs on [Dispatchers.Default]; nothing
 * touches the main thread except StateFlow publication and the UI's camera
 * callbacks. A tokenized Analyzing state plus a 6 s watchdog guarantees the UI
 * can never remain stuck on a loading state after a capture or OpenCV stall.
 */
class ScannerViewModel : ViewModel() {

    private val _ui = MutableStateFlow(
        ScannerUi(scan = searchingState(ReferenceMode.CARD)),
    )
    val ui: StateFlow<ScannerUi> = _ui.asStateFlow()

    private val isFrameBusy = AtomicBoolean(false)
    private val stillInFlight = AtomicBoolean(false)
    private val refineInFlight = AtomicBoolean(false)
    private val nextToken = AtomicLong(0L)
    private val refineGeneration = AtomicLong(0L)

    private var watchdogJob: Job? = null
    private var refineWatchdog: Job? = null
    private var lastFrameAt = 0L
    private var consecutiveValid = 0
    private var cooldownUntil = 0L

    @Volatile
    private var externallyPaused = false

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    fun setPaused(paused: Boolean) {
        externallyPaused = paused
        if (!paused) consecutiveValid = 0
    }

    fun setMode(newMode: ReferenceMode) {
        if (newMode == _ui.value.mode) return
        cancelWatchdog()
        invalidateRefinement()
        releaseCurrentSuccessBitmap()
        stillInFlight.set(false)
        cooldownUntil = SystemClock.elapsedRealtime() + AppConfig.Detection.SCAN_COOLDOWN_MS
        consecutiveValid = 0
        _ui.update {
            it.copy(
                mode = newMode,
                scan = searchingState(newMode),
                refining = false,
            )
        }
    }

    fun setTorch(enabled: Boolean) {
        if (_ui.value.torch == enabled) return
        _ui.update { it.copy(torch = enabled) }
    }

    fun toggleTorch() = setTorch(!_ui.value.torch)

    /** Called by "Scan Another" / "Try Again". */
    fun resumeScanning() {
        cancelWatchdog()
        invalidateRefinement()
        releaseCurrentSuccessBitmap()
        stillInFlight.set(false)
        cooldownUntil = SystemClock.elapsedRealtime() + AppConfig.Detection.SCAN_COOLDOWN_MS
        consecutiveValid = 0
        _ui.update {
            it.copy(
                scan = searchingState(it.mode),
                refining = false,
            )
        }
    }

    /**
     * Consume one upright, 3:4 stage-cropped preview frame. Takes ownership of
     * [frame] and recycles it after the reference inspection.
     */
    fun onPreviewFrame(frame: Bitmap) {
        val state = _ui.value.scan
        if (externallyPaused || state !is ScanUiState.Scanning) {
            frame.recycle()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < AppConfig.Detection.FRAME_INTERVAL_MS) {
            frame.recycle()
            return
        }
        if (!isFrameBusy.compareAndSet(false, true)) {
            frame.recycle()
            return
        }
        lastFrameAt = now

        if (!viewModelScope.isActive) {
            isFrameBusy.set(false)
            frame.recycle()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                inspectPreview(frame, _ui.value.mode)
            } catch (throwable: Throwable) {
                cancelWatchdog()
                _ui.update {
                    it.copy(
                        scan = ScanUiState.Error(
                            title = "Live detection failed",
                            reason = throwable.message
                                ?: "Unexpected error while scanning the frame.",
                        ),
                    )
                }
            } finally {
                isFrameBusy.set(false)
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    /**
     * Called by the screen with a full-resolution still. [token] must match the
     * current Analyzing state, otherwise the still is stale and discarded.
     */
    fun onStillCaptured(still: Bitmap, token: Long) {
        val state = _ui.value.scan
        if (state !is ScanUiState.Analyzing || state.token != token) {
            still.recycle()
            return
        }
        if (!stillInFlight.compareAndSet(false, true)) {
            still.recycle()
            return
        }
        if (!viewModelScope.isActive) {
            stillInFlight.set(false)
            still.recycle()
            return
        }
        val mode = _ui.value.mode

        viewModelScope.launch(Dispatchers.Default) {
            try {
                withTimeout(SCAN_TIMEOUT_MS) {
                    val calibration = PrecisionCalibrator.calibrate(still, mode)
                    val objects = IrregularShapeAnalyzer.analyze(
                        warped = calibration.warpedBitmap,
                        calibration = calibration,
                    )
                    if (isCurrentAnalyzing(token)) {
                        _ui.update {
                            it.copy(
                                torch = false,
                                scan = ScanUiState.Success(
                                    analysis = ScanAnalysis(
                                        mode = mode,
                                        calibration = calibration,
                                        objects = objects,
                                        selectedIndex = 0,
                                        token = token,
                                    ),
                                ),
                            )
                        }
                    }
                }
            } catch (exception: TimeoutCancellationException) {
                setErrorIfCurrent(token, TIMEOUT_TITLE, TIMEOUT_REASON)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: CalibrationException) {
                setErrorIfCurrent(
                    token,
                    calibrationTitle(exception.code),
                    exception.message ?: "Could not calibrate the reference.",
                )
            } catch (exception: IrregularShapeAnalyzer.AnalysisException) {
                setErrorIfCurrent(
                    token,
                    "No object found",
                    exception.message ?: "Could not isolate an object.",
                )
            } catch (throwable: Throwable) {
                setErrorIfCurrent(
                    token,
                    "Scan failed",
                    throwable.message ?: "Unexpected error during measurement.",
                )
            } finally {
                stillInFlight.set(false)
                cancelWatchdog()
                if (!still.isRecycled) still.recycle()
            }
        }
    }

    fun onCaptureFailed(reason: String, token: Long) {
        if (!isCurrentAnalyzing(token)) return
        cancelWatchdog()
        stillInFlight.set(false)
        _ui.update {
            it.copy(
                scan = ScanUiState.Error(
                    title = "Capture failed",
                    reason = reason,
                ),
            )
        }
    }

    override fun onCleared() {
        cancelWatchdog()
        refineWatchdog?.cancel()
        val bitmap = (_ui.value.scan as? ScanUiState.Success)
            ?.analysis?.calibration?.warpedBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        super.onCleared()
    }

    /**
     * GrabCut refinement of one object on the existing flattened canvas.
     * The rectangle is normalised to the image (0..1).
     */
    fun refineObject(index: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val state = _ui.value.scan as? ScanUiState.Success ?: return
        val analysis = state.analysis
        if (index !in analysis.objects.indices) return
        val bitmap = analysis.calibration.warpedBitmap
        if (bitmap.isRecycled) return

        val x1 = (minOf(left, right).coerceIn(0f, 1f) * bitmap.width).toInt()
        val x2 = (maxOf(left, right).coerceIn(0f, 1f) * bitmap.width).toInt()
        val y1 = (minOf(top, bottom).coerceIn(0f, 1f) * bitmap.height).toInt()
        val y2 = (maxOf(top, bottom).coerceIn(0f, 1f) * bitmap.height).toInt()
        val rect = PixelRect(
            left = x1.coerceIn(0, bitmap.width - 2),
            top = y1.coerceIn(0, bitmap.height - 2),
            right = x2.coerceIn(1, bitmap.width),
            bottom = y2.coerceIn(1, bitmap.height),
        )
        if (rect.width < 12 || rect.height < 12) return
        if (!refineInFlight.compareAndSet(false, true)) return

        val generation = refineGeneration.incrementAndGet()
        _ui.update { it.copy(refining = true) }
        refineWatchdog?.cancel()
        refineWatchdog = viewModelScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (refineGeneration.get() == generation) {
                refineGeneration.incrementAndGet()
                refineInFlight.set(false)
                _ui.update { it.copy(refining = false) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val refined = withTimeout(SCAN_TIMEOUT_MS) {
                    IrregularShapeAnalyzer.refineWithGrabCut(
                        warped = bitmap,
                        calibration = analysis.calibration,
                        rect = rect,
                    ).copy(index = index)
                }
                val current = _ui.value.scan as? ScanUiState.Success
                if (refineGeneration.get() == generation &&
                    current != null &&
                    current.analysis.token == analysis.token &&
                    !bitmap.isRecycled
                ) {
                    val objects = current.analysis.objects.toMutableList()
                    if (index in objects.indices) {
                        objects[index] = refined
                        _ui.update {
                            it.copy(
                                scan = ScanUiState.Success(
                                    analysis = current.analysis.copy(
                                        objects = objects,
                                        revision = current.analysis.revision + 1,
                                    ),
                                ),
                            )
                        }
                    }
                }
            } catch (exception: TimeoutCancellationException) {
                // Keep the previous geometry; the user can redraw a tighter box.
            } catch (exception: CancellationException) {
                throw exception
            } catch (throwable: Throwable) {
                // GrabCut can fail on a box that cuts through the object.
            } finally {
                if (refineGeneration.get() == generation) {
                    refineInFlight.set(false)
                    refineWatchdog?.cancel()
                    _ui.update { it.copy(refining = false) }
                }
            }
        }
    }

    /** Invalidate any in-flight GrabCut when leaving the result screen. */
    private fun invalidateRefinement() {
        refineGeneration.incrementAndGet()
        refineWatchdog?.cancel()
        refineWatchdog = null
        refineInFlight.set(false)
    }

    // ------------------------------------------------------------------
    // Live preview detection
    // ------------------------------------------------------------------

    private fun inspectPreview(frame: Bitmap, mode: ReferenceMode) {
        if (externallyPaused || _ui.value.scan !is ScanUiState.Scanning) return

        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntil) {
            consecutiveValid = 0
            _ui.update {
                it.copy(scan = searchingState(mode))
            }
            return
        }

        val detection = ReferenceDetector.detect(
            bitmap = frame,
            mode = mode,
            maxWidth = AppConfig.Detection.WORKING_WIDTH_PX,
        )
        val brightness = meanBrightness(frame)
        val lowLight = brightness < AppConfig.Detection.LOW_LIGHT_MEAN

        if (detection == null) {
            consecutiveValid = 0
            _ui.update {
                it.copy(
                    scan = ScanUiState.Scanning(
                        referenceDetected = false,
                        guidanceMessage = if (lowLight) GUIDANCE_DARK else searchMessage(mode),
                        stabilityProgress = 0f,
                        referenceQuad = null,
                        tiltDeg = 0.0,
                    ),
                )
            }
            return
        }

        val ordered = Geometry.orderQuad(detection.quad)
        val tilt = Geometry.maxTiltDeviationDeg(ordered)
        val normalized = ordered.map {
            Pt(it.x / frame.width, it.y / frame.height)
        }

        when {
            tilt > AppConfig.Detection.MAX_LIVE_TILT_DEG -> {
                consecutiveValid = 0
                _ui.update {
                    it.copy(
                        scan = ScanUiState.Scanning(
                            referenceDetected = true,
                            guidanceMessage = GUIDANCE_TILT,
                            stabilityProgress = 0f,
                            referenceQuad = normalized,
                            tiltDeg = tilt,
                        ),
                    )
                }
            }

            else -> {
                consecutiveValid++
                val progress = (
                    consecutiveValid.toFloat() /
                        AppConfig.Detection.REQUIRED_CONSECUTIVE_FRAMES
                    ).coerceIn(0f, 1f)

                if (progress >= 1f) {
                    consecutiveValid = 0
                    val token = nextToken.incrementAndGet()
                    _ui.update {
                        it.copy(scan = ScanUiState.Analyzing(token))
                    }
                    armWatchdog(token)
                } else {
                    _ui.update {
                        it.copy(
                            scan = ScanUiState.Scanning(
                                referenceDetected = true,
                                guidanceMessage = GUIDANCE_LOCKED,
                                stabilityProgress = progress,
                                referenceQuad = normalized,
                                tiltDeg = tilt,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Watchdog / recovery
    // ------------------------------------------------------------------

    private fun armWatchdog(token: Long) {
        cancelWatchdog()
        watchdogJob = viewModelScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (isCurrentAnalyzing(token)) {
                stillInFlight.set(false)
                setErrorIfCurrent(token, TIMEOUT_TITLE, TIMEOUT_REASON)
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun isCurrentAnalyzing(token: Long): Boolean {
        val state = _ui.value.scan
        return state is ScanUiState.Analyzing && state.token == token
    }

    private fun setErrorIfCurrent(token: Long, title: String, reason: String) {
        if (!isCurrentAnalyzing(token)) return
        _ui.update {
            it.copy(scan = ScanUiState.Error(title = title, reason = reason))
        }
        cancelWatchdog()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Free the previous flattened bitmap after the result dialog has left composition. */
    private fun releaseCurrentSuccessBitmap() {
        val bitmap = (_ui.value.scan as? ScanUiState.Success)
            ?.analysis?.calibration?.warpedBitmap
            ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }, 200L)
    }

    private fun searchingState(mode: ReferenceMode) = ScanUiState.Scanning(
        referenceDetected = false,
        guidanceMessage = searchMessage(mode),
        stabilityProgress = 0f,
        referenceQuad = null,
        tiltDeg = 0.0,
    )

    private fun searchMessage(mode: ReferenceMode): String = when (mode) {
        ReferenceMode.CARD -> GUIDANCE_CARD
        ReferenceMode.A4 -> GUIDANCE_A4
    }

    private fun calibrationTitle(code: String): String = when (code) {
        "REFERENCE_NOT_FOUND" -> "Reference not found"
        "REFERENCE_CLIPPED" -> "Reference cut off"
        "TILT_TOO_HIGH" -> "Tilt too high"
        "REFERENCE_TOO_SMALL" -> "Move closer"
        "REFERENCE_DISTORTED" -> "Reference distorted"
        "WARP_FAILED" -> "Flattening failed"
        else -> "Scan failed"
    }

    /** Coarse luma sample; runs on Dispatchers.Default with the rest of the CV work. */
    private fun meanBrightness(bitmap: Bitmap): Double {
        val stepX = max(1, bitmap.width / 48)
        val stepY = max(1, bitmap.height / 64)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                sum += 0.299 * Color.red(pixel) +
                    0.587 * Color.green(pixel) +
                    0.114 * Color.blue(pixel)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0.0 else sum / count
    }
}
