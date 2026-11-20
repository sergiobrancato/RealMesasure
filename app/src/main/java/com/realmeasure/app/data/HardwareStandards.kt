package com.realmeasure.app.data

import com.realmeasure.app.config.AppConfig

/**
 * Hardware reference tables.
 *
 * Adding a new standard is a one-line change: append an object to one of the
 * lists below. The matcher, the result cards and the thread chips all read
 * from these lists, so no UI edits are required.
 *
 * Sources: ISO 261/262 (metric threads), ISO 4762 (socket head cap screws),
 * ISO 2936 / DIN 911 (hex keys), ASME B1.1 (UNC/UNF) and ASTM A574
 * (imperial socket head cap screws).
 */

private const val MM_PER_INCH = AppConfig.MM_PER_INCH

private fun round4(value: Double): Double {
    val factor = 10_000.0
    return Math.round(value * factor) / factor
}

/** Render 6.0 as "6" and 2.5 as "2.5" for clean designations. */
private fun fmtMm(value: Double): String =
    if (value == Math.floor(value)) value.toInt().toString() else value.toString()

// ---------------------------------------------------------------------------
// Entry types
// ---------------------------------------------------------------------------

/** ISO metric screw or bolt. */
data class MetricFastener(
    val id: String,
    val designation: String,
    val majorDiameterMm: Double,
    val coarsePitchMm: Double,
    val finePitchMm: Double?,
    val standardLengthsMm: List<Double>,
    val socketSizeMm: Double?,
)

/** UNC/UNF imperial screw or bolt. */
data class ImperialFastener(
    val id: String,
    val designation: String,
    val majorDiameterIn: Double,
    val majorDiameterMm: Double,
    val uncThreadsPerInch: Int,
    val unfThreadsPerInch: Int?,
    val standardLengthsIn: List<Double>,
    val socketSizeIn: Double?,
)

/** ISO 2936 / DIN 911 metric hex key. */
data class MetricHexKey(
    val id: String,
    val designation: String,
    val sizeMm: Double,
    val shortArmMm: Double,
    val longArmMm: Double,
)

/** SAE imperial hex key. */
data class ImperialHexKey(
    val id: String,
    val designation: String,
    val sizeIn: Double,
    val shortArmIn: Double,
    val longArmIn: Double,
)

// ---------------------------------------------------------------------------
// Constructors
// ---------------------------------------------------------------------------

private fun metric(
    diameter: Double,
    coarse: Double,
    fine: Double?,
    lengths: List<Double>,
    socket: Double?,
): MetricFastener = MetricFastener(
    id = "iso4762-m$diameter",
    designation = "M${fmtMm(diameter)}",
    majorDiameterMm = diameter,
    coarsePitchMm = coarse,
    finePitchMm = fine,
    standardLengthsMm = lengths,
    socketSizeMm = socket,
)

private fun imperial(
    designation: String,
    diameterIn: Double,
    uncTpi: Int,
    unfTpi: Int?,
    lengthsIn: List<Double>,
    socketIn: Double?,
): ImperialFastener = ImperialFastener(
    id = "astm-a574-${designation.replace(Regex("[^A-Za-z0-9]+"), "-").trimEnd('-')}",
    designation = designation,
    majorDiameterIn = diameterIn,
    majorDiameterMm = round4(diameterIn * MM_PER_INCH),
    uncThreadsPerInch = uncTpi,
    unfThreadsPerInch = unfTpi,
    standardLengthsIn = lengthsIn,
    socketSizeIn = socketIn,
)

private fun hexKey(sizeMm: Double, shortArmMm: Double, longArmMm: Double): MetricHexKey =
    MetricHexKey("iso2936-$sizeMm", "${fmtMm(sizeMm)} mm", sizeMm, shortArmMm, longArmMm)

private fun saeHexKey(sizeIn: Double, label: String, shortIn: Double, longIn: Double): ImperialHexKey =
    ImperialHexKey("sae-hex-$label", "$label\"", sizeIn, shortIn, longIn)

// ---------------------------------------------------------------------------
// Tables
// ---------------------------------------------------------------------------

/** ISO metric coarse/fine fasteners, M1.6 through M16. */
val METRIC_FASTENERS: List<MetricFastener> = listOf(
    metric(1.6, 0.35, 0.2, listOf(2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0), 1.5),
    metric(2.0, 0.4, 0.25, listOf(2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0), 1.5),
    metric(2.5, 0.45, 0.35, listOf(3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0), 2.0),
    metric(3.0, 0.5, 0.35, listOf(4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 25.0), 2.5),
    metric(4.0, 0.7, 0.5, listOf(5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 25.0, 30.0), 3.0),
    metric(5.0, 0.8, 0.5, listOf(6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 25.0, 30.0, 35.0, 40.0), 4.0),
    metric(6.0, 1.0, 0.75, listOf(8.0, 10.0, 12.0, 16.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), 5.0),
    metric(8.0, 1.25, 1.0, listOf(10.0, 12.0, 16.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), 6.0),
    metric(10.0, 1.5, 1.25, listOf(12.0, 16.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), 8.0),
    metric(12.0, 1.75, 1.5, listOf(16.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0), 10.0),
    metric(16.0, 2.0, 1.5, listOf(20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0, 140.0, 150.0), 14.0),
)

/** Common UNC/UNF imperial socket head cap screws. */
val IMPERIAL_FASTENERS: List<ImperialFastener> = listOf(
    imperial("#0-80", 0.06, 64, 80, listOf(0.125, 0.1875, 0.25, 0.3125, 0.375, 0.5), 0.05),
    imperial("#1-64", 0.073, 64, 72, listOf(0.125, 0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625), 0.0625),
    imperial("#2-56", 0.086, 56, 64, listOf(0.125, 0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625), 0.078125),
    imperial("#3-48", 0.099, 48, 56, listOf(0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625, 0.75), 0.0625),
    imperial("#4-40", 0.112, 40, 48, listOf(0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625, 0.75), 0.09375),
    imperial("#5-40", 0.125, 40, 44, listOf(0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625, 0.75), 0.09375),
    imperial("#6-32", 0.138, 32, 40, listOf(0.25, 0.3125, 0.375, 0.5, 0.625, 0.75, 1.0), 0.109375),
    imperial("#8-32", 0.164, 32, 36, listOf(0.25, 0.3125, 0.375, 0.5, 0.625, 0.75, 1.0), 0.140625),
    imperial("#10-24", 0.19, 24, 32, listOf(0.375, 0.5, 0.625, 0.75, 1.0, 1.25, 1.5, 2.0), 0.15625),
    imperial("#12-24", 0.216, 24, 28, listOf(0.375, 0.5, 0.625, 0.75, 1.0, 1.25, 1.5, 2.0), 0.15625),
    imperial("1/4\"-20", 0.25, 20, 28, listOf(0.375, 0.5, 0.625, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 3.0, 3.125, 3.5, 4.0), 0.1875),
    imperial("5/16\"-18", 0.3125, 18, 24, listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 3.5, 4.0), 0.25),
    imperial("3/8\"-16", 0.375, 16, 24, listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0), 0.3125),
    imperial("7/16\"-14", 0.4375, 14, 20, listOf(0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0), 0.375),
    imperial("1/2\"-13", 0.5, 13, 20, listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0), 0.375),
)

/** ISO 2936 metric hex keys, 1.5-10 mm. */
val METRIC_HEX_KEYS: List<MetricHexKey> = listOf(
    hexKey(1.5, 45.0, 80.0),
    hexKey(2.0, 50.0, 90.0),
    hexKey(2.5, 56.0, 100.0),
    hexKey(3.0, 63.0, 112.0),
    hexKey(4.0, 70.0, 125.0),
    hexKey(5.0, 80.0, 140.0),
    hexKey(5.5, 85.0, 150.0),
    hexKey(6.0, 90.0, 160.0),
    hexKey(7.0, 95.0, 170.0),
    hexKey(8.0, 100.0, 180.0),
    hexKey(9.0, 105.0, 190.0),
    hexKey(10.0, 112.0, 200.0),
)

/** Common SAE hex keys, 1/16" through 3/8". */
val SAE_HEX_KEYS: List<ImperialHexKey> = listOf(
    saeHexKey(0.0625, "1/16", 1.75, 3.25),
    saeHexKey(0.078125, "5/64", 2.0, 3.5),
    saeHexKey(0.09375, "3/32", 2.25, 3.75),
    saeHexKey(0.109375, "7/64", 2.25, 4.0),
    saeHexKey(0.125, "1/8", 2.5, 4.25),
    saeHexKey(0.140625, "9/64", 2.75, 4.5),
    saeHexKey(0.15625, "5/32", 3.0, 5.0),
    saeHexKey(0.1875, "3/16", 3.25, 5.5),
    saeHexKey(0.21875, "7/32", 3.5, 6.0),
    saeHexKey(0.25, "1/4", 4.0, 6.5),
    saeHexKey(0.3125, "5/16", 4.5, 7.0),
    saeHexKey(0.375, "3/8", 5.0, 8.0),
)
