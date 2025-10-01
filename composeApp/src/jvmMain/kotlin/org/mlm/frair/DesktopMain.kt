package org.mlm.frair

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.mlm.frair.storage.provideAppDataStore

fun main() = application {
    val dataStore = provideAppDataStore()
    Window(onCloseRequest = ::exitApplication, title = "Frair") {
        App(dataStore)
    }
}