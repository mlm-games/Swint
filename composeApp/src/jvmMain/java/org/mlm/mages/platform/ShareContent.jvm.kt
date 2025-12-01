
package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

@Composable
actual fun rememberShareHandler(): (ShareContent) -> Unit {
    return remember {
        { content ->
            try {
                when {
                    content.filePath != null -> {
                        // Open containing folder so user can manage the file
                        val file = File(content.filePath)
                        val parent = file.parentFile
                        if (parent != null && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(parent)
                        }
                    }
                    content.text != null -> {
                        // Copy text to clipboard
                        val selection = StringSelection(content.text)
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(selection, selection)
                    }
                }
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }
}