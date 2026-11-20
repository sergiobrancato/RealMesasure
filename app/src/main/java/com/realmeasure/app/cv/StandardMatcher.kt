package com.realmeasure.app.cv

import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.data.IMPERIAL_FASTENERS
import com.realmeasure.app.data.METRIC_FASTENERS
import com.realmeasure.app.data.METRIC_HEX_KEYS
import com.realmeasure.app.data.ImperialFastener
import com.realmeasure.app.data.ImperialHexKey
import com.realmeasure.app.data.MetricFastener
import com.realmeasure.app.data.MetricHexKey
import com.realmeasure.app.data.SAE_HEX_KEYS
import com.realmeasure.app.model.HardwareFamily
import com.realmeasure.app.model.HardwareKind
import com.realmeasure.app.model.MatchConfidence
import com.realmeasure.app.model.StandardMatch
import com.realmeasure.app.model.StandardMatchResult
import com.realmeasure.app.model.ThreadKind
import com.realmeasure.app.model.ThreadOption
import kotlin.math.abs

/**
 * Pure catalogue matcher: given a measured diameter (and optional length) it
 * identifies the closest ISO metric and UNC/UNF imperial sizes with deviation
 * deltas, thread options and standard-length guidance.
 *
 * The scanner, the interactive caliper and the JVM unit tests all call exactly
 * the same function.
 */
object StandardMatcher {

    /** Identify the closest Metric and Imperial sizes for a measurement. */
    fun match(kind: HardwareKind, diameterMm: Double, lengthMm: Double?): StandardMatchResult {
        if (diameterMm.isNaN() || diameterMm <= 0.0) {
            return StandardMatchResult(null, null, emptyList(), withinTolerance = false)
        }

        val metricRanked = when (kind) {
            HardwareKind.FASTENER -> METRIC_FASTENERS.map { it.toRank(kind, diameterMm, lengthMm) }
            HardwareKind.HEX_KEY -> METRIC_HEX_KEYS.map { it.toRank(kind, diameterMm, lengthMm) }
        }.sortedWith(compareBy({ it.absDelta }, { -it.match.score }))

        val imperialRanked = when (kind) {
            HardwareKind.FASTENER -> IMPERIAL_FASTENERS.map { it.toRank(kind, diameterMm, lengthMm) }
            HardwareKind.HEX_KEY -> SAE_HEX_KEYS.map { it.toRank(kind, diameterMm, lengthMm) }
        }.sortedWith(compareBy({ it.absDelta }, { -it.match.score }))

        val metric = metricRanked.firstOrNull()?.match
        val imperial = imperialRanked.firstOrNull()?.match

        val primaryIds = buildSet {
            metric?.let { add(it.specId) }
            imperial?.let { add(it.specId) }
        }
        val alternatives = (metricRanked.drop(1).asSequence() + imperialRanked.drop(1).asSequence())
            .filter { it.match.specId !in primaryIds }
            .sortedByDescending { it.match.score }
            .take(AppConfig.Matching.MAX_ALTERNATIVES)
            .map { it.match }
            .toList()

        return StandardMatchResult(
            metric = metric,
            imperial = imperial,
            alternatives = alternatives,
            withinTolerance = (metric?.inTolerance == true) || (imperial?.inTolerance == true),
        )
    }

    // -----------------------------------------------------------------
    // Ranking
    // -----------------------------------------------------------------

    private data class Ranked(val match: StandardMatch, val absDelta: Double)

    private fun MetricFastener.toRank(kind: HardwareKind, measured: Double, length: Double?): Ranked =
        Ranked(build(this, kind, measured, length), abs(measured - majorDiameterMm))

    private fun MetricHexKey.toRank(kind: HardwareKind, measured: Double, length: Double?): Ranked =
        Ranked(build(this, kind, measured, length), abs(measured - sizeMm))

    private fun ImperialFastener.toRank(kind: HardwareKind, measured: Double, length: Double?): Ranked =
        Ranked(build(this, kind, measured, length), abs(measured - majorDiameterMm))

    private fun ImperialHexKey.toRank(kind: HardwareKind, measured: Double, length: Double?): Ranked =
        Ranked(build(this, kind, measured, length), abs(measured - sizeIn * AppConfig.MM_PER_INCH))

    // -----------------------------------------------------------------
    // Match building
    // -----------------------------------------------------------------

    private fun toleranceMm(kind: HardwareKind, nominalMm: Double): Double {
        val matching = AppConfig.Matching
        return if (kind == HardwareKind.HEX_KEY) {
            matching.HEX_ABSOLUTE_TOLERANCE_MM + nominalMm * matching.HEX_RELATIVE_TOLERANCE
        } else {
            matching.ABSOLUTE_TOLERANCE_MM + nominalMm * matching.RELATIVE_TOLERANCE
        }
    }

    private fun confidenceFor(absDelta: Double, tolerance: Double): MatchConfidence {
        val ratio = absDelta / maxOf(tolerance, 1e-6)
        return when {
            ratio <= 0.4 -> MatchConfidence.HIGH
            ratio <= 0.8 -> MatchConfidence.MEDIUM
            else -> MatchConfidence.LOW
        }
    }

    /** Nearest value in a list to [target] (lists in the catalogue are ascending). */
    private fun nearest(values: List<Double>, target: Double?): Double? {
        if (target == null || values.isEmpty() || target.isNaN()) return null
        return values.minByOrNull { abs(it - target) }
    }

    private fun metricThreads(spec: MetricFastener): List<ThreadOption> {
        val options = mutableListOf(
            ThreadOption(ThreadKind.COARSE, "Coarse ${spec.coarsePitchMm} mm", spec.coarsePitchMm),
        )
        spec.finePitchMm?.let {
            options.add(ThreadOption(ThreadKind.FINE, "Fine $it mm", it))
        }
        return options
    }

    private fun imperialThreads(spec: ImperialFastener): List<ThreadOption> {
        val tpiToIn = 1.0 / spec.uncThreadsPerInch
        val options = mutableListOf(
            ThreadOption(
                ThreadKind.UNC,
                "${spec.uncThreadsPerInch} TPI UNC",
                round4(AppConfig.MM_PER_INCH / spec.uncThreadsPerInch),
            ),
        )
        spec.unfThreadsPerInch?.let { tpi ->
            options.add(
                ThreadOption(ThreadKind.UNF, "$tpi TPI UNF", round4(AppConfig.MM_PER_INCH / tpi)),
            )
        }
        return options
    }

    private fun build(spec: MetricFastener, kind: HardwareKind, measured: Double, length: Double?): StandardMatch {
        val nominal = spec.majorDiameterMm
        val delta = measured - nominal
        val tolerance = toleranceMm(kind, nominal)
        val absDelta = abs(delta)
        val recommendedLength = nearest(spec.standardLengthsMm, length)
        return StandardMatch(
            specId = spec.id,
            designation = spec.designation,
            family = HardwareFamily.METRIC,
            kind = kind,
            nominalMm = nominal,
            nominalIn = round4(nominal / AppConfig.MM_PER_INCH),
            measuredMm = measured,
            deltaMm = delta,
            inTolerance = absDelta <= tolerance,
            confidence = confidenceFor(absDelta, tolerance),
            threadOptions = metricThreads(spec),
            recommendedLengthMm = recommendedLength,
            armType = null,
            standard = "ISO 4762",
            headType = "Socket head cap",
            socketSizeMm = spec.socketSizeMm,
            socketSizeIn = null,
            score = (1.0 - absDelta / maxOf(tolerance, 1e-6)).coerceIn(0.0, 1.0),
        )
    }

    private fun build(spec: MetricHexKey, kind: HardwareKind, measured: Double, length: Double?): StandardMatch {
        val nominal = spec.sizeMm
        val delta = measured - nominal
        val tolerance = toleranceMm(kind, nominal)
        val absDelta = abs(delta)
        var armType: String? = null
        var recommendedLength: Double? = null
        if (length != null && !length.isNaN()) {
            armType = if (abs(spec.shortArmMm - length) <= abs(spec.longArmMm - length)) "short" else "long"
            recommendedLength = if (armType == "short") spec.shortArmMm else spec.longArmMm
        }
        return StandardMatch(
            specId = spec.id,
            designation = spec.designation,
            family = HardwareFamily.METRIC,
            kind = kind,
            nominalMm = nominal,
            nominalIn = round4(nominal / AppConfig.MM_PER_INCH),
            measuredMm = measured,
            deltaMm = delta,
            inTolerance = absDelta <= tolerance,
            confidence = confidenceFor(absDelta, tolerance),
            threadOptions = emptyList(),
            recommendedLengthMm = recommendedLength,
            recommendedLengthIn = null,
            armType = armType,
            standard = "ISO 2936",
            headType = null,
            socketSizeMm = null,
            socketSizeIn = null,
            score = (1.0 - absDelta / maxOf(tolerance, 1e-6)).coerceIn(0.0, 1.0),
        )
    }

    private fun build(spec: ImperialFastener, kind: HardwareKind, measured: Double, length: Double?): StandardMatch {
        val nominal = spec.majorDiameterMm
        val delta = measured - nominal
        val tolerance = toleranceMm(kind, nominal)
        val absDelta = abs(delta)
        val nearestIn = nearest(spec.standardLengthsIn, length?.let { it / AppConfig.MM_PER_INCH })
        val recommendedLengthMm = nearestIn?.let { it * AppConfig.MM_PER_INCH }
        return StandardMatch(
            specId = spec.id,
            designation = spec.designation,
            family = HardwareFamily.IMPERIAL,
            kind = kind,
            nominalMm = nominal,
            nominalIn = spec.majorDiameterIn,
            measuredMm = measured,
            deltaMm = delta,
            inTolerance = absDelta <= tolerance,
            confidence = confidenceFor(absDelta, tolerance),
            threadOptions = imperialThreads(spec),
            recommendedLengthMm = recommendedLengthMm,
            recommendedLengthIn = nearestIn,
            armType = null,
            standard = "ASTM A574",
            headType = "Socket head cap",
            socketSizeMm = null,
            socketSizeIn = spec.socketSizeIn,
            score = (1.0 - absDelta / maxOf(tolerance, 1e-6)).coerceIn(0.0, 1.0),
        )
    }

    private fun build(spec: ImperialHexKey, kind: HardwareKind, measured: Double, length: Double?): StandardMatch {
        val nominal = spec.sizeIn * AppConfig.MM_PER_INCH
        val delta = measured - nominal
        val tolerance = toleranceMm(kind, nominal)
        val absDelta = abs(delta)
        var armType: String? = null
        var recommendedLengthIn: Double? = null
        if (length != null && !length.isNaN()) {
            val lengthIn = length / AppConfig.MM_PER_INCH
            armType = if (abs(spec.shortArmIn - lengthIn) <= abs(spec.longArmIn - lengthIn)) "short" else "long"
            recommendedLengthIn = if (armType == "short") spec.shortArmIn else spec.longArmIn
        }
        return StandardMatch(
            specId = spec.id,
            designation = spec.designation,
            family = HardwareFamily.IMPERIAL,
            kind = kind,
            nominalMm = nominal,
            nominalIn = spec.sizeIn,
            measuredMm = measured,
            deltaMm = delta,
            inTolerance = absDelta <= tolerance,
            confidence = confidenceFor(absDelta, tolerance),
            threadOptions = emptyList(),
            recommendedLengthMm = recommendedLengthIn?.let { it * AppConfig.MM_PER_INCH },
            recommendedLengthIn = recommendedLengthIn,
            armType = armType,
            standard = "ASME B18.3",
            headType = null,
            socketSizeMm = null,
            socketSizeIn = null,
            score = (1.0 - absDelta / maxOf(tolerance, 1e-6)).coerceIn(0.0, 1.0),
        )
    }

    private fun round4(value: Double): Double {
        val factor = 10_000.0
        return Math.round(value * factor) / factor
    }
}
