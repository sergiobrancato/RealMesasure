package com.realmeasure.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared display formatting for the modals and history cards. */
object Formats {

    fun mm(value: Float, digits: Int = 2): String =
        String.format(Locale.US, "%.${digits}f mm", value)

    fun mm(value: Double, digits: Int = 2): String =
        String.format(Locale.US, "%.${digits}f mm", value)

    fun length(value: Float?): String = value?.let { mm(it, 1) } ?: "-"

    fun matchError(valueMm: Float): String = mm(valueMm, 2)

    fun timestamp(timestamp: Long): String =
        SimpleDateFormat("dd MMM yyyy \u00B7 HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun hardwareSummary(diameterMm: Float, lengthMm: Float?): String =
        if (lengthMm != null) {
            "${mm(diameterMm)} across - ${mm(lengthMm, 1)} long"
        } else {
            "${mm(diameterMm)} across flats"
        }
}
