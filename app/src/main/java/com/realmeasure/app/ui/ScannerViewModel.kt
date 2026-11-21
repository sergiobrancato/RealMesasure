package com.realmeasure.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.cv.AnalysisRenderer
import com.realmeasure.app.cv.CardCalibrator
import com.realmeasure.app.cv.FastenerAnalyzer
import com.realmeasure.app.cv.FastenerSpecResult
import com.realmeasure.app.cv.LiveZoneDetector
import com.realmeasure.app.cv.StandardMatcher
import com.realmeasure.app.model.HardwareKind
import com.realmeasure.app.model.ScanMode
import com.realmeasure.app.model.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Live guidance copy
// ---------------------------------------------------------------------------

private const val GUIDANCE_CARD = "Step 1: Place any bank or ID card inside the left box"
private const val GUIDANCE_PART = "Step 2: Place a screw, bolt, or hex key in the right box"
private const val GUIDANCE_EDGE = "Keep the hardware fully inside the right box"
private const val GUIDANCE_DARK = "Scene is dark \u2014 tap the flash icon for accuracy"
private const val GUIDANCE_HOLD = "Aligned! Hold steady..."

/**
 * Strict unidirectional scanner state.
 *
 * Exactly one of these values is live at any time. The camera frame pipeline
 * only runs while [Scanning] is current; entering [Analyzing] pauses it, and
 * [Success] / [Error] keep it paused while the corresponding modal is open.
 */
sealed interface ScanUiState {

    data class Scanning(
        val cardDetected: Boolean,
        val fastenerDetected: Boolean,
        val guidanceMessage: String,
        val stabilityProgress: Float,
    ) : ScanUiState

    data object Analyzing : ScanUiState

    data class Success(
        val result: FastenerSpecResult,
        val scanMode: ScanMode,
        val annotatedBitmap: Bitmap,
        val rawCroppedBitmap: Bitmap,
    ) : ScanUiState

    data class Error(
        val title: String,
        val reason: String,
    ) : ScanUiState
}

/** Settings that live alongside the scan state machine. */
data class ScannerUi(
    val scan: ScanUiState = ScanUiState.Scanning(
        cardDetected = false,
        fastenerDetected = false,
        guidanceMessage = GUIDANCE_CARD,
        stabilityProgress = 0f,
    ),
    val mode: ScanMode = ScanMode.SCREW_BOLT,
    val unit: UnitSystem = UnitSystem.MM,
    val torch: Boolean = false,
)

/**
 * Hands-free scanner state holder (MVVM).
 *
 * Frames arrive from CameraX's [androidx.camera.core.ImageAnalysis] callback
 * at roughly 6-7 fps. Each frame is inspected cheaply by
 * [LiveZoneDetector] on a single background thread; when both zones are
 * detected on [AppConfig.Live.REQUIRED_CONSECUTIVE_FRAMES] consecutive frames
 * the state flips to [ScanUiState.Analyzing] and the full perspective-
 * correction + measurement pipeline runs on the same frame. There is no
 * shutter button anywhere.
 */
class ScannerViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ScannerUi())
    val ui: StateFlow<ScannerUi> = _ui.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "realmeasure-live-analysis").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val frameInFlight = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var consecutiveValid = 0

    /**
     * Set while a non-scan modal (currently the onboarding tutorial) covers the
     * viewfinder. No frames are consumed until it closes.
     */
    @Volatile
    private var externallyPaused = false

    /** True only while the live pipeline should consume frames. */
    val isScanningActive: Boolean
        get() = !externallyPaused && _ui.value.scan is ScanUiState.Scanning

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    /** Pause / resume frame consumption for overlays outside the scan state. */
    fun setPaused(paused: Boolean) {
        externallyPaused = paused
        if (!paused) consecutiveValid = 0
    }

    fun setMode(mode: ScanMode) {
        if (mode == _ui.value.mode) return
        consecutiveValid = 0
        _ui.update {
            it.copy(
                mode = mode,
                scan = ScanUiState.Scanning(
                    cardDetected = false,
                    fastenerDetected = false,
                    guidanceMessage = GUIDANCE_CARD,
                    stabilityProgress = 0f,
                ),
            )
        }
    }

    fun setUnit(unit: UnitSystem) = _ui.update { it.copy(unit = unit) }

    fun toggleTorch() = _ui.update { it.copy(torch = !it.torch) }

    /** Called by "Scan Another" / "Try Again" to resume live scanning. */
    fun resumeScanning() {
        consecutiveValid = 0
        _ui.update {
            it.copy(
                scan = ScanUiState.Scanning(
                    cardDetected = false,
                    fastenerDetected = false,
                    guidanceMessage = GUIDANCE_CARD,
                    stabilityProgress = 0f,
                ),
            )
        }
    }

    /**
     * Consume one upright, 3:4-cropped frame from CameraX.
     *
     * Takes ownership of [frame]: it is recycled once processed (or
     * immediately when the state machine is not scanning).
     */
    fun onFrame(frame: Bitmap) {
        val state = _ui.value.scan
        if (externallyPaused || state !is ScanUiState.Scanning) {
            frame.recycle()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < AppConfig.Live.FRAME_INTERVAL_MS) {
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
                    processFrame(frame)
                } finally {
                    frameInFlight.set(false)
                    if (!frame.isRecycled) frame.recycle()
                }
            }
        } catch (exception: RuntimeException) {
            // Executor already shut down (ViewModel cleared).
            frameInFlight.set(false)
            if (!frame.isRecycled) frame.recycle()
        }
    }

    override fun onCleared() {
        analysisExecutor.shutdownNow()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Live pipeline
    // ------------------------------------------------------------------

    private fun processFrame(frame: Bitmap) {
        if (externallyPaused || _ui.value.scan !is ScanUiState.Scanning) return

        try {
            val inspection = LiveZoneDetector.inspect(frame)
            val lowLight = inspection.meanBrightness < AppConfig.Live.LOW_LIGHT_MEAN
            val cardOk = inspection.card.detected
            val partOk = inspection.part.detected && !inspection.part.touchesBorder
            val both = cardOk && partOk

            consecutiveValid = if (both) consecutiveValid + 1 else 0
            val progress = (
                consecutiveValid.toFloat() / AppConfig.Live.REQUIRED_CONSECUTIVE_FRAMES
                ).coerceIn(0f, 1f)

            val guidance = guidanceFor(
                cardOk = cardOk,
                partOk = partOk,
                touchesBorder = inspection.part.touchesBorder,
                lowLight = lowLight,
            )

            if (progress >= 1f) {
                _ui.update { it.copy(scan = ScanUiState.Analyzing) }
                consecutiveValid = 0
                runFullMeasurement(frame)
            } else {
                _ui.update {
                    it.copy(
                        scan = ScanUiState.Scanning(
                            cardDetected = cardOk,
                            fastenerDetected = partOk,
                            guidanceMessage = guidance,
                            stabilityProgress = progress,
                        ),
                    )
                }
            }
        } catch (throwable: Throwable) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "Scan failed",
                        reason = throwable.message ?: "Unexpected error while inspecting the frame.",
                    ),
                )
            }
        }
    }

    private fun guidanceFor(
        cardOk: Boolean,
        partOk: Boolean,
        touchesBorder: Boolean,
        lowLight: Boolean,
    ): String = when {
        cardOk && partOk -> GUIDANCE_HOLD
        !cardOk -> if (lowLight) GUIDANCE_DARK else GUIDANCE_CARD
        touchesBorder -> GUIDANCE_EDGE
        else -> GUIDANCE_PART
    }

    /**
     * Full-quality measurement pass, executed once the stability timer locks.
     * Runs on the analysis thread; the UI only receives the finished state.
     */
    private fun runFullMeasurement(frame: Bitmap) {
        val mode = _ui.value.mode
        try {
            val outcome = CardCalibrator.calibrate(frame, includePreview = false)
            val measurement = FastenerAnalyzer.analyze(frame, outcome.result, mode)

            val kind = if (mode == ScanMode.HEX_KEY) HardwareKind.HEX_KEY else HardwareKind.FASTENER
            val catalogue = StandardMatcher.match(
                kind,
                measurement.shaftDiameterMm,
                measurement.lengthMm,
            )
            if (!catalogue.withinTolerance) {
                _ui.update {
                    it.copy(
                        scan = ScanUiState.Error(
                            title = "Measurement out of range",
                            reason = String.format(
                                java.util.Locale.US,
                                "Measured %.2f mm across the %s, which does not match a Metric or Imperial standard within tolerance. Lay the part flat, keep it fully inside the right box and try again.",
                                measurement.shaftDiameterMm,
                                if (mode == ScanMode.HEX_KEY) "flats" else "shaft",
                            ),
                        ),
                    )
                }
                return
            }

            val spec = if (mode == ScanMode.HEX_KEY) {
                StandardMatcher.matchHexKey(measurement.shaftDiameterMm.toFloat())
            } else {
                StandardMatcher.matchScrewOrBolt(
                    measurement.shaftDiameterMm.toFloat(),
                    measurement.lengthMm.toFloat(),
                )
            }

            val annotated = AnalysisRenderer.annotate(frame, outcome.result, measurement)
            val rawCrop = AnalysisRenderer.cropAroundFastener(frame, measurement.handles)

            _ui.update {
                it.copy(
                    scan = ScanUiState.Success(
                        result = spec,
                        scanMode = mode,
                        annotatedBitmap = annotated,
                        rawCroppedBitmap = rawCrop,
                    ),
                )
            }
        } catch (exception: CardCalibrator.CalibrationException) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "Scan failed",
                        reason = cardErrorReason(exception),
                    ),
                )
            }
        } catch (exception: FastenerAnalyzer.MeasurementException) {
            _ui.update {
                it.copy(
                    scan = ScanUiState.Error(
                        title = "Scan failed",
                        reason = measurementErrorReason(exception),
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

    private fun cardErrorReason(exception: CardCalibrator.CalibrationException): String =
        when (exception.code) {
            "CARD_NOT_FOUND" ->
                "Could not detect the bank/ID card in the left box. Keep all four corners visible and avoid glare."
            "CARD_CLIPPED" ->
                "Part of the card is outside the left box. Pull the phone back slightly so the whole card fits."
            "CARD_TOO_SMALL" ->
                "The card appears too small. Move the phone closer to the table."
            "SCALE_MISMATCH" ->
                "The card looks bent or tilted. Flatten it on the table and try again."
            else -> exception.hint.ifBlank { exception.message ?: "Card calibration failed." }
        }

    private fun measurementErrorReason(exception: FastenerAnalyzer.MeasurementException): String =
        when (exception.code) {
            "NO_CONTOUR" ->
                "Could not detect clear edges on the fastener. Try using a plain background with less glare."
            "BAD_PROFILE" ->
                "Could not isolate the shaft. Lay the fastener flat and keep it fully inside the right box."
            "BAD_SCALE" ->
                "The perspective correction failed. Hold the phone flat and parallel to the table."
            else -> exception.hint.ifBlank { exception.message ?: "Fastener measurement failed." }
        }
}
