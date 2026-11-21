package com.realmeasure.app.data

import android.content.Context
import android.graphics.Bitmap
import com.realmeasure.app.cv.FastenerSpecResult
import com.realmeasure.app.model.ScanMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Lightweight, file-backed scan history.
 *
 * - JPEG crops live in `filesDir/scans/<uuid>.jpg`.
 * - Metadata lives in `filesDir/history.json` as a pretty-printed JSON array.
 *
 * All methods are synchronized and intended to be called from an IO
 * dispatcher; the repository performs no coroutine switching itself.
 */
class ScanHistoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val scansDir = File(appContext.filesDir, "scans").apply { mkdirs() }
    private val historyFile = File(appContext.filesDir, "history.json")
    private val lock = Any()

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    fun loadAll(): List<ScanHistoryItem> = synchronized(lock) {
        readInternal().sortedByDescending { it.timestamp }
    }

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    /**
     * Persist [result] plus a JPEG crop of [bitmap].
     * Returns the new entry (newest first in the file).
     */
    fun save(
        result: FastenerSpecResult,
        mode: ScanMode,
        bitmap: Bitmap,
    ): ScanHistoryItem = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        val imageFile = File(scansDir, "$id.jpg")
        imageFile.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        }

        val item = ScanHistoryItem(
            id = id,
            primaryLabel = result.primaryLabel,
            secondaryAlternative = result.secondaryAlternative,
            pitchInfo = result.pitchInfo,
            scanMode = mode,
            rawDiameterMm = result.rawDiameterMm,
            rawLengthMm = result.rawLengthMm,
            timestamp = System.currentTimeMillis(),
            imagePath = imageFile.absolutePath,
        )

        val updated = readInternal().filterNot { it.id == item.id } + item
        writeInternal(updated.sortedByDescending { it.timestamp })
        item
    }

    /** Remove a record and its JPEG file. */
    fun delete(item: ScanHistoryItem) = synchronized(lock) {
        runCatching { File(item.imagePath).delete() }
        writeInternal(readInternal().filterNot { it.id == item.id })
        Unit
    }

    // ------------------------------------------------------------------
    // JSON internals
    // ------------------------------------------------------------------

    private fun readInternal(): List<ScanHistoryItem> {
        if (!historyFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(historyFile.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toItem()
            }
        }.getOrElse { emptyList() }
    }

    private fun writeInternal(items: List<ScanHistoryItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(item.toJson()) }
        historyFile.writeText(array.toString(2))
    }

    private fun JSONObject.toItem(): ScanHistoryItem = ScanHistoryItem(
        id = optString("id"),
        primaryLabel = optString("primaryLabel"),
        secondaryAlternative = if (isNull("secondaryAlternative")) null else optString("secondaryAlternative"),
        pitchInfo = if (isNull("pitchInfo")) null else optString("pitchInfo"),
        scanMode = runCatching { ScanMode.valueOf(optString("scanMode")) }
            .getOrDefault(ScanMode.SCREW_BOLT),
        rawDiameterMm = optDouble("rawDiameterMm", 0.0).toFloat(),
        rawLengthMm = if (isNull("rawLengthMm")) null else optDouble("rawLengthMm").toFloat(),
        timestamp = optLong("timestamp", 0L),
        imagePath = optString("imagePath"),
    )

    private fun ScanHistoryItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("primaryLabel", primaryLabel)
        put("secondaryAlternative", secondaryAlternative ?: JSONObject.NULL)
        put("pitchInfo", pitchInfo ?: JSONObject.NULL)
        put("scanMode", scanMode.name)
        put("rawDiameterMm", rawDiameterMm.toDouble())
        put("rawLengthMm", rawLengthMm?.toDouble() ?: JSONObject.NULL)
        put("timestamp", timestamp)
        put("imagePath", imagePath)
    }
}
