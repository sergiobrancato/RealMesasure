package com.realmeasure.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.cv.CalibrationException
import com.realmeasure.app.cv.Geometry
import com.realmeasure.app.cv.IrregularShapeAnalyzer
import com.realmeasure.app.cv.PrecisionCalibrator
import com.realmeasure.app.cv.ReferenceDetector
import com.realmeasure.app.cv.ScanAnalysis
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

// ---------------------------------------------------------------------------
// Guidance copy (exact strings required by the workflow)
// ---------------------------------------------------------------------------

private const val GUIDANCE_CARD = "Place ID card flat beside your object"
private const val GUIDANCE_A4 = "Place A4 sheet flat beside your object"
private const val GUIDANCE_TILT = "Hold phone flatter to reduce tilt"
private const val GUIDANCE_LOCKED = "Reference locked! Hold steady..."
private const val GUIDANCE_DARK = "Scene is dark — tap the flashlight for accuracy"

/**
 * Strict scanner state machine.
 *
 * Exactly one of these values is live at a time. Frames are consumed only in
 * [Scanning]; [Analyzing] runs the full-resolution still through calibration
 * and contour analysis, and [Success] / [Error] keep the pipeline paused until
 * the user dismisses the result.
 */
sealed interface ScanUiState {

    data class Scanning(
        val referenceDetected: Boolean,
        val guidanceMessage: String,
        val stabilityProgress: Float,
        /** Detected reference polygon normalised to the viewfinder stage. */
        val referenceQuad: List<Pt>?,
        val tiltDeg: Double,
    ) : ScanUiState

    data object Analyzing : ScanUiState

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
)

/**
 * Hands-free planar scanner.
 *
 * Live preview frames (~7 fps) only run the cheap reference detector. Once the
 * selected ID card / A4 sheet is stable for five consecutive frames, the ViewModel
 * emits a capture request; the screen grabs a full-resolution still with
 * CameraX ImageCapture at MAXIMIZE_QUALITY and hands it back for the precise
 * cornerSubPix + homography + contour pipeline.
 */
class ScannerViewModel : ViewModel() {

    private val _ui = MutableStateFlow(
        ScannerUi(scan = searchingState(ReferenceMode.CARD)),
    )
    val ui: StateFlow<ScannerUi> = _ui.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "realmeasure-planar-analysis").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val frameInFlight = AtomicBoolean(false)
    private val stillInFlight = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var consecutiveValid = 0

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
        consecutiveValid = 0
        _ui.update {
            it.copy(
                mode = newMode,
                scan = searchingState(newMode),
            )
        }
    }

    fun toggleTorch() = _ui.update { it.copy(torch = !it.torch) }

    /** Called by "Scan Another" / "Try Again". */
    fun resumeScanning() {
        consecutiveValid = 0
        _ui.update {
            it.copy(scan = searchingState(it.mode))
        }
    }

    /**
     * Consume one upright, 3:4 stage-cropped preview frame. Takes ownership of
     * [frame] and recycles it after the cheap reference inspection.
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
        if (!frameInFlight.compareAndSet(false, true)) {
            frame.recycle()
            return
        }
        lastFrameAt = now

        try {
            analysisExecutor.execute {
                try {
                    inspectPreview(frame, _ui.value.mode)
                } finally {
                    frameInFlight.set(false)
                    if (!frame.isRecycled) frame.recycle()
                }
            }
        } catch (exception: RuntimeException) {
            frameInFlight.set(false)
            if (!frame.isRecycled) frame.recycle()
        }
    }

    /** Called by the screen with a full-resolution still from ImageCapture. */
    fun onStillCaptured(still: Bitmap) {
        if (_ui.value.scan !is ScanUiState.Analyzing) {
            still.recycle()
            return
        }
        if (!stillInFlight.compareAndSet(false, true)) {
            still.recycle()
            return
        }
        try {
            analysisExecutor.execute {
                try {
                    processStill(still, _ui.value.mode)
                } finally {
                    stillInFlight.set(false)
                    if (!still.isRecycled) still.recycle()
                }
            }
        } catch (exception: RuntimeException) {
            stillInFlight.set(false)
            still.recycle()
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "Analyzer unavailable",
                        reason = "The analysis thread was stopped. Reopen the scanner and try again.",
                    ),
                )
            }
        }
    }

    fun onCaptureFailed(reason: String) {
        if (_ui.value.scan !is ScanUiState.Analyzing) return
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
        analysisExecutor.shutdownNow()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Live preview detection
    // ------------------------------------------------------------------

    private fun inspectPreview(frame: Bitmap, mode: ReferenceMode) {
        if (externallyPaused || _ui.value.scan !is ScanUiState.Scanning) return

        try {
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
                        _ui.update {
                            it.copy(
                                scan = ScanUiState.Analyzing,
                            )
                        }
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
        } catch (throwable: Throwable) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "Live detection failed",
                        reason = throwable.message ?: "Unexpected error while scanning the frame.",
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Full-resolution still pipeline
    // ------------------------------------------------------------------

    private fun processStill(still: Bitmap, mode: ReferenceMode) {
        if (_ui.value.scan !is ScanUiState.Analyzing) return
        try {
            val calibration = PrecisionCalibrator.calibrate(still, mode)
            val objects = IrregularShapeAnalyzer.analyze(
                warped = calibration.warpedBitmap,
                calibration = calibration,
            )
            _ui.update {
                it.copy(
                    scan = ScanUiState.Success(
                        analysis = ScanAnalysis(
                            mode = mode,
                            calibration = calibration,
                            objects = objects,
                            selectedIndex = 0,
                        ),
                    ),
                )
            }
        } catch (exception: CalibrationException) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = calibrationTitle(exception.code),
                        reason = exception.message ?: "Could not calibrate the reference.",
                    ),
                )
            }
        } catch (exception: IrregularShapeAnalyzer.AnalysisException) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "No object found",
                        reason = exception.message ?: "Could not isolate an object.",
                    ),
                )
            }
        } catch (throwable: Throwable) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "Scan failed",
                        reason = throwable.message ?: "Unexpected error during measurement.",
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    /** Coarse luma sample; avoids a full-frame OpenCV pass on the UI thread. */
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
                sum += 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0.0 else sum / count
    }
}
