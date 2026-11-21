package com.realmeasure.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.realmeasure.app.model.Pt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a 1:1 SVG into `filesDir/exports/` and shares it through a
 * [FileProvider] so CAD apps and laser cutters receive a readable URI.
 */
object SvgExporter {

    const val MIME_TYPE = "image/svg+xml"

    private fun authority(context: Context): String =
        "${context.packageName}.fileprovider"

    /** Write the SVG and return its content URI. */
    fun export(
        context: Context,
        outerMm: List<Pt>,
        holesMm: List<List<Pt>>,
        title: String,
    ): Uri {
        val document = SvgBuilder.build(outerMm, holesMm, title)
        val directory = File(context.filesDir, "exports").apply { mkdirs() }
        val safeName = title
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "scan" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "${safeName}_$timestamp.svg")
        file.writeText(document.text, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** Export and open the Android share sheet. */
    fun exportAndShare(
        context: Context,
        outerMm: List<Pt>,
        holesMm: List<List<Pt>>,
        title: String,
    ) {
        val uri = export(context, outerMm, holesMm, title)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share 1:1 SVG").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
