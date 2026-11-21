package com.realmeasure.app.data

import com.realmeasure.app.model.ScanMode

/**
 * One saved hardware scan shown in the "My Hardware" tab.
 *
 * Persisted as a lightweight JSON object (see [ScanHistoryRepository]) - no
 * Room, no annotation processor, no schema migrations.
 */
data class ScanHistoryItem(
    val id: String,
    /** Bold identified size, e.g. "M4 x 16 mm" or "3 mm Hex Key". */
    val primaryLabel: String,
    val secondaryAlternative: String?,
    val pitchInfo: String?,
    val scanMode: ScanMode,
    val rawDiameterMm: Float,
    val rawLengthMm: Float?,
    val timestamp: Long,
    /** Absolute path to the cropped JPEG inside `filesDir/scans/`. */
    val imagePath: String,
)
