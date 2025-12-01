package org.mlm.mages.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberShareHandler(): (ShareContent) -> Unit {
    val context = LocalContext.current

    return remember {
        { content ->
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }

                    if (content.filePath == null && content.text != null) {
                        // Text-only share
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content.text)
                    } else if (content.filePath != null) {
                        val file = File(content.filePath)
                        val uri: Uri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            file
                        )
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        type = content.mimeType ?: "*/*"
                        content.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    } else {
                        // Nothing to share
                        return@remember
                    }
                }

                context.startActivity(
                    Intent.createChooser(intent, null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                // Best-effort
            }
        }
    }
}