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
                    content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }

                    when {
                        content.filePath == null && content.text != null -> {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, content.text)
                        }
                        content.filePath != null -> {
                            val file = File(content.filePath)

                            if (!file.exists() || !file.canRead()) {
                                return@remember
                            }

                            val uri: Uri = FileProvider.getUriForFile(
                                context,
                                context.packageName + ".provider",
                                file
                            )

                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            type = content.mimeType ?: "*/*"
                            content.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                        }
                        else -> return@remember
                    }
                }

                val chooser = Intent.createChooser(intent, null)
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}