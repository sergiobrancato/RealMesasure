package com.realmeasure.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Shared numeric formatting for the scanner result and history cards. */
object Formats {

    fun mm(value: Double, digits: Int = 2): String =
        String.format(Locale.US, "%.${digits}f mm", value)

    fun mm(value: Float, digits: Int = 2): String =
        String.format(Locale.US, "%.${digits}f mm", value)

    /** `12.34 ± 0.21 mm` */
    fun mmWithError(value: Double, error: Double, digits: Int = 2): String =
        String.format(
            Locale.US,
            "%.${digits}f ± %.${digits}f mm",
            value,
            abs(error),
        )

    /** `12.34 ± 0.15 cm²` */
    fun cm2WithError(value: Double, error: Double, digits: Int = 2): String =
        String.format(
            Locale.US,
            "%.${digits}f ± %.${digits}f cm²",
            value,
            abs(error),
        )

    /** `1234.5 ± 2.0 mm²` */
    fun mm2WithError(value: Double, error: Double, digits: Int = 1): String =
        String.format(
            Locale.US,
            "%.${digits}f ± %.${digits}f mm²",
            value,
            abs(error),
        )

    /** `120.40 ± 0.42 × 80.10 ± 0.31 mm` */
    fun dimensionsWithError(
        lengthMm: Double,
        lengthErrorMm: Double,
        widthMm: Double,
        widthErrorMm: Double,
    ): String = String.format(
        Locale.US,
        "%.2f ± %.2f × %.2f ± %.2f mm",
        lengthMm,
        abs(lengthErrorMm),
        widthMm,
        abs(widthErrorMm),
    )

    fun degrees(value: Double): String =
        String.format(Locale.US, "%.1f°", value)

    fun timestamp(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun count(value: Int, singular: String, plural: String): String =
        "$value ${if (value == 1) singular else plural}"
}
