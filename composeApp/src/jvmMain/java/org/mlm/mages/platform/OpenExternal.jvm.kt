package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.io.File

@Composable
actual fun rememberFileOpener(): (String, String?) -> Boolean {
    return { path, _mime ->
        val file = File(path)
        val opened = runCatching {
            Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
            Desktop.getDesktop().open(file)
            true
        }.getOrElse { false }

        if (opened) true else {
            // Fallback: macOS "open" or Linux "xdg-open"
            val os = System.getProperty("os.name").lowercase()
            val cmd = if (os.contains("mac")) arrayOf("open", file.absolutePath)
            else arrayOf("xdg-open", file.absolutePath)
            runCatching { ProcessBuilder(*cmd).start() }.isSuccess
        }
    }
}
