package org.mlm.mages.platform

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import org.mlm.mages.MatrixService
import org.mlm.mages.R

actual object Notifier {
    private const val CHANNEL_ID = "messages"
    private var currentRoomId: String? = null

    actual fun notifyRoom(title: String, body: String) {
        val ctx = AppCtx.get() ?: return
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
    }

    actual fun setWindowFocused(focused: Boolean) {
        // Not needed on Android
    }

    actual fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (currentRoomId == roomId) return false
        return true
    }
}

@Composable
actual fun BindLifecycle(service: MatrixService)  {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                service.port.enterForeground()
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                service.port.enterBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
}

@Composable
actual fun rememberQuitApp(): () -> Unit {
    val context = LocalContext.current
    return {
        (context as? Activity)?.finishAffinity()
    }
}