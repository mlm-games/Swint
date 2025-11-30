package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import org.mlm.mages.MatrixService

expect object Notifier {
    fun notifyRoom(title: String, body: String)
    fun setCurrentRoom(roomId: String?)
    fun setWindowFocused(focused: Boolean)
    fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean
}

@Composable
expect fun BindLifecycle(service: MatrixService)

@Composable
expect fun rememberQuitApp(): () -> Unit