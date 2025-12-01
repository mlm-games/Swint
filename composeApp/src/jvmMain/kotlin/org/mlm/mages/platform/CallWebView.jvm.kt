package org.mlm.mages.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI

@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit, // currently unused for desktop
    onClosed: () -> Unit
): CallWebViewController {
    val hasOpened = remember { mutableStateOf(false) }

    LaunchedEffect(widgetUrl) {
        if (!hasOpened.value) {
            openInBrowser(widgetUrl)
            hasOpened.value = true
        }
    }

    // Telling the user the call is in the browser
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Call opened in browser",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Your call is running in your default browser. " +
                            "Use this app to hang up when you're done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onClosed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("End call")
                }
            }
        }
    }

    return object : CallWebViewController {
        override fun sendToWidget(message: String) {
            // No-op on desktop for now. The browser manages widget ↔ SDK via homeserver.
        }

        override fun close() {
            onClosed()
        }
    }
}

private fun openInBrowser(url: String): Boolean {
    fun openCmd(u: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("win") ->
                    ProcessBuilder("cmd", "/c", "start", "", u).start().let { true }
                os.contains("mac") ->
                    ProcessBuilder("open", u).start().let { true }
                else ->
                    runCatching { ProcessBuilder("xdg-open", u).start() }.isSuccess ||
                            runCatching { ProcessBuilder("gio", "open", u).start() }.isSuccess
            }
        } catch (_: Throwable) {
            false
        }
    }

    return try {
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