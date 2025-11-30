package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import org.mlm.mages.MatrixService
import org.mlm.mages.NotifierImpl
import kotlin.system.exitProcess

actual object Notifier {
    private var currentRoomId: String? = null
    private var windowFocused: Boolean = true

    actual fun notifyRoom(title: String, body: String) {
        NotifierImpl.notify(app = "Mages", title = title, body = body, desktopEntry = "org.mlm.mages")
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
    }

    actual fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
    }

    actual fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (windowFocused && currentRoomId == roomId) return false
        return true
    }
}

@Composable
actual fun BindLifecycle(service: MatrixService) {
}

@Composable
actual fun rememberQuitApp(): () -> Unit = {
    exitProcess(0)
}