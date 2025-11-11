package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.mlm.mages.ui.components.AttachmentData
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

@Composable
actual fun rememberFilePicker(onPicked: (AttachmentData?) -> Unit): FilePickerController {
    return remember {
        object : FilePickerController {
            override fun pick(mimeFilter: String) {
                thread(name = "MagesFilePicker") {
                    val dlg = FileDialog(null as Frame?, "Select file", FileDialog.LOAD)
                    dlg.isVisible = true
                    val dir = dlg.directory ?: return@thread onPicked(null)
                    val file = dlg.file ?: return@thread onPicked(null)
                    val f = File(dir, file)
                    val mime = try { Files.probeContentType(f.toPath()) ?: "application/octet-stream" } catch (_: Throwable) { "application/octet-stream" }
                    onPicked(
                        AttachmentData(
                            path = f.absolutePath,
                            mimeType = mime,
                            fileName = f.name,
                            sizeBytes = f.length()
                        )
                    )
                }
            }
        }
    }
}