package com.realmeasure.app.data

import android.content.Context
import android.graphics.Bitmap
import com.realmeasure.app.model.MeasurementQuality
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.Pt
import com.realmeasure.app.model.ReferenceMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * File-backed "My Scans" storage.
 *
 * - metadata: `filesDir/scans.json`
 * - images:   `filesDir/scans/<uuid>.jpg`
 *
 * No Room, no schema migrations, no cloud. All methods are synchronized and
 * intended to be called from an IO dispatcher.
 */
class ScanRepository(context: Context) {

    private val appContext = context.applicationContext
    private val scansDir = File(appContext.filesDir, "scans").apply { mkdirs() }
    private val jsonFile = File(appContext.filesDir, "scans.json")
    private val lock = Any()

    fun loadAll(): List<ScanRecord> = synchronized(lock) {
        readInternal().sortedByDescending { it.timestamp }
    }

    /**
     * Persist [object] with the annotated flattened image produced by
     * [com.realmeasure.app.cv.AnalysisRenderer].
     */
    fun save(
        mode: ReferenceMode,
        objectGeometry: ObjectGeometry,
        annotatedBitmap: Bitmap,
    ): ScanRecord = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        val imageFile = File(scansDir, "$id.jpg")
        imageFile.outputStream().use { stream ->
            annotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        }

        val record = ScanRecord(
            id = id,
            timestamp = System.currentTimeMillis(),
            mode = mode,
            imagePath = imageFile.absolutePath,
            lengthMm = objectGeometry.lengthMm,
            widthMm = objectGeometry.widthMm,
            lengthErrorMm = objectGeometry.lengthErrorMm,
            widthErrorMm = objectGeometry.widthErrorMm,
            areaCm2 = objectGeometry.areaNetCm2,
            areaErrorCm2 = objectGeometry.areaErrorCm2,
            perimeterMm = objectGeometry.perimeterMm,
            holeCount = objectGeometry.holes.size,
            quality = objectGeometry.quality,
            svgOuter = objectGeometry.outerContour,
            svgHoles = objectGeometry.holes.map { it.contour },
        )

        val updated = readInternal().filterNot { it.id == id } + record
        writeInternal(updated.sortedByDescending { it.timestamp })
        record
    }

    fun delete(record: ScanRecord) = synchronized(lock) {
        runCatching { File(record.imagePath).delete() }
        writeInternal(readInternal().filterNot { it.id == record.id })
        Unit
    }

    // ------------------------------------------------------------------
    // JSON internals
    // ------------------------------------------------------------------

    private fun readInternal(): List<ScanRecord> {
        if (!jsonFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonFile.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { parseRecord(it) }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeInternal(records: List<ScanRecord>) {
        val array = JSONArray()
        records.forEach { array.put(toJson(it)) }
        val temp = File(jsonFile.parentFile, "${jsonFile.name}.tmp")
        temp.writeText(array.toString(2))
        if (jsonFile.exists()) jsonFile.delete()
        if (!temp.renameTo(jsonFile)) {
            jsonFile.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun toJson(record: ScanRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("timestamp", record.timestamp)
        put("mode", record.mode.name)
        put("imagePath", record.imagePath)
        put("lengthMm", record.lengthMm)
        put("widthMm", record.widthMm)
        put("lengthErrorMm", record.lengthErrorMm)
        put("widthErrorMm", record.widthErrorMm)
        put("areaCm2", record.areaCm2)
        put("areaErrorCm2", record.areaErrorCm2)
        put("perimeterMm", record.perimeterMm)
        put("holeCount", record.holeCount)
        put("quality", record.quality.name)
        put("svgOuter", pointsToJson(record.svgOuter))
        put(
            "svgHoles",
            JSONArray().apply {
                record.svgHoles.forEach { put(pointsToJson(it)) }
            },
        )
    }

    private fun parseRecord(json: JSONObject): ScanRecord? = runCatching {
        ScanRecord(
            id = json.getString("id"),
            timestamp = json.optLong("timestamp", 0L),
            mode = runCatching { ReferenceMode.valueOf(json.optString("mode")) }
                .getOrDefault(ReferenceMode.CARD),
            imagePath = json.optString("imagePath"),
            lengthMm = json.optDouble("lengthMm", 0.0),
            widthMm = json.optDouble("widthMm", 0.0),
            lengthErrorMm = json.optDouble("lengthErrorMm", 0.0),
            widthErrorMm = json.optDouble("widthErrorMm", 0.0),
            areaCm2 = json.optDouble("areaCm2", 0.0),
            areaErrorCm2 = json.optDouble("areaErrorCm2", 0.0),
            perimeterMm = json.optDouble("perimeterMm", 0.0),
            holeCount = json.optInt("holeCount", 0),
            quality = runCatching { MeasurementQuality.valueOf(json.optString("quality")) }
                .getOrDefault(MeasurementQuality.MODERATE),
            svgOuter = pointsFromJson(json.optJSONArray("svgOuter")),
            svgHoles = buildList {
                val holes = json.optJSONArray("svgHoles") ?: return@buildList
                for (index in 0 until holes.length()) {
                    add(pointsFromJson(holes.optJSONArray(index)))
                }
            },
        )
    }.getOrNull()

    private fun pointsToJson(points: List<Pt>): JSONArray = JSONArray().apply {
        points.forEach { point ->
            put(
                JSONArray().apply {
                    put(round(point.x))
                    put(round(point.y))
                },
            )
        }
    }

    private fun pointsFromJson(array: JSONArray?): List<Pt> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val pair = array.optJSONArray(index) ?: continue
                if (pair.length() >= 2) {
                    add(Pt(pair.optDouble(0), pair.optDouble(1)))
                }
            }
        }
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 1000.0) / 1000.0
}
