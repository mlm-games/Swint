package org.mlm.mages

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.storage.provideAppDataStore

fun main() = application {
    val dataStore = provideAppDataStore()
    Window(onCloseRequest = ::exitApplication, title = "Mages") {
        App(dataStore)
    }
    MagesPaths.init()
}