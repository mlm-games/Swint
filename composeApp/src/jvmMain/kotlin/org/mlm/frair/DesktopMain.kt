package org.mlm.frair

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.mlm.frair.storage.provideAppDataStore

fun main() = application {
    val deepLinkBus = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val deepLinks = deepLinkBus.asSharedFlow()

    runCatching {
        val cmd = System.getProperty("sun.java.command") ?: ""
        val parts = cmd.split(" ")
        parts.firstOrNull { it.contains("://") }?.let { url ->
            parseMatrixDeepLink(url)?.let { deepLinkBus.tryEmit(it) }
        }
    }

    val dataStore = provideAppDataStore()
    Window(onCloseRequest = ::exitApplication, title = "Frair") {
        App(dataStore, deepLinks)
    }
}

fun parseMatrixDeepLink(input: String): String? {
    val s = input.trim()

    return runCatching {
        val q = s.substringAfter("frair://room?", "")
        q.split('&').mapNotNull {
            val kv = it.split('=')
            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()["id"]
    }.getOrNull()
}