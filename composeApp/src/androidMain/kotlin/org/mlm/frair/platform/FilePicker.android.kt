package org.mlm.frair.platform

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import okio.buffer
import okio.sink
import okio.source
import org.mlm.frair.ui.components.AttachmentData
import java.io.File

@Composable
actual fun rememberFilePicker(onPicked: (AttachmentData?) -> Unit): FilePickerController {
    val ctx = LocalContext.current
    var currentMime by remember { mutableStateOf("*/*") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) { onPicked(null); return@rememberLauncherForActivityResult }
        val cr = ctx.contentResolver
        val name = queryDisplayName(cr, uri) ?: "file"
        val mime = cr.getType(uri) ?: guessMime(name)
        val dir = File(ctx.cacheDir, "frair_uploads").apply { mkdirs() }
        val outFile = File(dir, "${System.currentTimeMillis()}_$name")

        try {
            cr.openInputStream(uri)?.use { ins ->
                outFile.sink().buffer().use { sink -> sink.writeAll(ins.source()) }
            }
            onPicked(AttachmentData(
                path = outFile.absolutePath,
                mimeType = mime,
                fileName = name,
                sizeBytes = outFile.length()
            ))
        } catch (t: Throwable) {
            onPicked(null)
        }
    }

    return object : FilePickerController {
        override fun pick(mimeFilter: String) {
            currentMime = mimeFilter
            launcher.launch(mimeFilter)
        }
    }
}

private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
    val c: Cursor? = cr.query(uri, null, null, null, null)
    c?.use {
        val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
    }
    return null
}

private fun guessMime(name: String): String =
    when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }