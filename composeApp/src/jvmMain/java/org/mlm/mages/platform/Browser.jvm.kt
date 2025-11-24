package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.net.URI

@Composable
actual fun rememberOpenBrowser(): (String) -> Boolean {
    return { url ->
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
            else {
                val isMac = System.getProperty("os.name").lowercase().contains("mac")
                ProcessBuilder(if (isMac) "open" else "xdg-open", url).start()
            }
        }.isSuccess
    }
}