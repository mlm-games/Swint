package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.net.URI

@Composable
actual fun rememberOpenBrowser(): (String) -> Boolean {
    fun openCmd(u: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("win") -> ProcessBuilder("cmd", "/c", "start", "", u).start().let { true }
                os.contains("mac") -> ProcessBuilder("open", u).start().let { true }
                else -> runCatching { ProcessBuilder("xdg-open", u).start() }.isSuccess
                        || runCatching { ProcessBuilder("gio", "open", u).start() }.isSuccess
            }
        } catch (_: Throwable) { false }
    }

    return { raw ->
        val url =
            if (raw.startsWith("http://", true) || raw.startsWith("https://", true) || "://" in raw) raw
            else "https://$raw"

        try {
            if (Desktop.isDesktopSupported()) {
                val d = Desktop.getDesktop()
                if (d.isSupported(Desktop.Action.BROWSE)) {
                    d.browse(URI(url))
                    true
                } else {
                    openCmd(url)
                }
            } else {
                openCmd(url)
            }
        } catch (_: Throwable) {
            openCmd(url)
        }
    }
}