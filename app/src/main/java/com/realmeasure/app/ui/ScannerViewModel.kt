package com.realmeasure.app.ui

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.cv.CardCalibrator
import com.realmeasure.app.cv.FastenerAnalyzer
import com.realmeasure.app.cv.StandardMatcher
import com.realmeasure.app.model.CalibrationResult
import com.realmeasure.app.model.FastenerMeasurement
import com.realmeasure.app.model.HardwareKind
import com.realmeasure.app.model.ScanMode
import com.realmeasure.app.model.StandardMatchResult
import com.realmeasure.app.model.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Navigation stages of the single-activity app. */
enum class Screen { SCANNER, RESULT }

/** Typed, user-facing failure banner content. */
data class ScanError(
    val title: String,
    val message: String,
    val hint: String? = null,
)

/** Everything the result screen needs to render a completed analysis. */
data class AnalysisResult(
    val photo: Bitmap,
    val cardPreview: Bitmap?,
    val calibration: CalibrationResult,
    val measurement: FastenerMeasurement,
    val matches: StandardMatchResult,
    val processingMs: Long,
    val warnings: List<String>,
)

data class ScannerUiState(
    val screen: Screen = Screen.SCANNER,
    val mode: ScanMode = ScanMode.SCREW_BOLT,
    val unit: UnitSystem = UnitSystem.MM,
    val torch: Boolean = false,
    val processing: Boolean = false,
    val processingStep: String = "Preparing camera",
    val error: ScanError? = null,
    val result: AnalysisResult? = null,
)

/**
 * Unidirectional MVVM state holder for the scanner and result screens.
 *
 * The screens never mutate state directly - they call intent methods
 * (setMode, toggleTorch, processCapture, resetToScanner) and observe
 * [ui] as a single immutable StateFlow.
 */
class ScannerViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ScannerUiState())
    val ui: StateFlow<ScannerUiState> = _ui.asStateFlow()

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    fun setMode(mode: ScanMode) = _ui.update { it.copy(mode = mode) }

    fun setUnit(unit: UnitSystem) = _ui.update { it.copy(unit = unit) }

    fun toggleTorch() = _ui.update { it.copy(torch = !it.torch) }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun resetToScanner() = _ui.update {
        it.copy(screen = Screen.SCANNER, result = null, error = null, processing = false)
    }

    fun onCaptureFailed(message: String) = _ui.update {
        it.copy(
            processing = false,
            error = ScanError("Capture failed", message, "Try again; if it persists, restart the app."),
        )
    }

    /** Analyze a freshly captured JPEG: decode -> calibrate -> measure -> match. */
    fun processCapture(file: File) {
        val current = _ui.value
        if (current.processing) return

        _ui.update { it.copy(processing = true, error = null, processingStep = "Decoding photo") }

        viewModelScope.launch {
            val started = System.currentTimeMillis()
            try {
                val photo = withContext(Dispatchers.IO) { decodeAndNormalize(file) }

                _ui.update { it.copy(processingStep = "Calibrating bank card") }
                val outcome = withContext(Dispatchers.Default) {
                    CardCalibrator.calibrate(photo)
                }

                _ui.update { it.copy(processingStep = "Measuring fastener") }
                val measurement = withContext(Dispatchers.Default) {
                    FastenerAnalyzer.analyze(photo, outcome.result, current.mode)
                }

                _ui.update { it.copy(processingStep = "Matching ISO/imperial standards") }
                val kind = if (current.mode == ScanMode.HEX_KEY) HardwareKind.HEX_KEY else HardwareKind.FASTENER
                val matches = StandardMatcher.match(kind, measurement.shaftDiameterMm, measurement.lengthMm)

                val warnings = buildWarnings(outcome.result, measurement, matches)
                val result = AnalysisResult(
                    photo = photo,
                    cardPreview = outcome.cardPreview,
                    calibration = outcome.result,
                    measurement = measurement,
                    matches = matches,
                    processingMs = System.currentTimeMillis() - started,
                    warnings = warnings,
                )
                _ui.update {
                    it.copy(screen = Screen.RESULT, result = result, processing = false)
                }
            } catch (throwable: Throwable) {
                _ui.update { it.copy(processing = false, error = mapError(throwable)) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Captured-photo preparation
    // ------------------------------------------------------------------

    /**
     * Decode the captured JPEG, apply EXIF rotation, centre-crop to the 3:4
     * stage aspect (matching the viewfinder so normalised guides map 1:1),
     * then cap the width for fast, deterministic analysis.
     */
    private fun decodeAndNormalize(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDimension / (sample * 2) >= AppConfig.Capture.PROCESSING_MAX_WIDTH_PX * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalStateException("The captured photo could not be decoded.")

        val rotation = ExifInterface(file.absolutePath).rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val aspect = AppConfig.Capture.STAGE_ASPECT
        val current = bitmap.width.toDouble() / bitmap.height
        if (abs(current - aspect) > 0.005) {
            bitmap = if (current > aspect) {
                val width = (bitmap.height * aspect).roundToInt()
                val x = ((bitmap.width - width) / 2.0).roundToInt()
                Bitmap.createBitmap(bitmap, x, 0, width, bitmap.height)
            } else {
                val height = (bitmap.width / aspect).roundToInt()
                val y = ((bitmap.height - height) / 2.0).roundToInt()
                Bitmap.createBitmap(bitmap, 0, y, bitmap.width, height)
            }
        }

        if (bitmap.width > AppConfig.Capture.PROCESSING_MAX_WIDTH_PX) {
            val width = AppConfig.Capture.PROCESSING_MAX_WIDTH_PX
            val height = (bitmap.height.toDouble() * width / bitmap.width).roundToInt()
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return bitmap
    }

    // ------------------------------------------------------------------
    // Error + warning mapping
    // ------------------------------------------------------------------

    private fun mapError(throwable: Throwable): ScanError = when (throwable) {
        is CardCalibrator.CalibrationException ->
            ScanError("Card not calibrated", throwable.message ?: "", throwable.hint)
        is FastenerAnalyzer.MeasurementException ->
            ScanError("Fastener not measured", throwable.message ?: "", throwable.hint)
        is IllegalStateException ->
            ScanError(
                "Photo could not be read",
                throwable.message ?: "The captured photo could not be decoded.",
                "Try capturing again; very large photos may not be supported.",
            )
        else ->
            ScanError("Something went wrong", throwable.message ?: "Unexpected analysis failure.")
    }

    private fun buildWarnings(
        calibration: CalibrationResult,
        measurement: FastenerMeasurement,
        matches: StandardMatchResult,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (calibration.confidence < 0.55) {
            warnings.add(
                "Card calibration was marginal. Keep the card flat and avoid glare for the next scan.",
            )
        }
        if (calibration.scaleDiscrepancy > 0.12) {
            warnings.add(
                "Horizontal and vertical card scales disagree. The card may be tilted or bent.",
            )
        }
        if (measurement.confidence < 0.45) {
            warnings.add(
                "Fastener confidence is low. Fine-tune the caliper handles or capture again with softer lighting.",
            )
        }
        if (!matches.withinTolerance) {
            warnings.add(
                "No catalogue size is within tolerance. Verify the measurement against a known fastener.",
            )
        }
        return warnings
    }
}
