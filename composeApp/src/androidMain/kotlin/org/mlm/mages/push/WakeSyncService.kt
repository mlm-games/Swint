package org.mlm.mages.push

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.mlm.mages.R
import org.mlm.mages.matrix.MatrixProvider

class WakeSyncService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, "sync")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Syncing")
            .setContentText("Fetching messages…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(2, n)

        scope.launch {
            try {
                val service = MatrixProvider.get(this@WakeSyncService)
                // Short wake sync (2.5s)
                service.port.wakeSyncOnce(2500)
                // Build notifications from latest unseen events
                Notifier.showNewEventNotifications(this@WakeSyncService, service)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel("sync") == null) {
            mgr.createNotificationChannel(NotificationChannel("sync","Background sync",
                NotificationManager.IMPORTANCE_MIN))
        }
        if (mgr.getNotificationChannel("messages") == null) {
            val ch = NotificationChannel("messages","Messages", NotificationManager.IMPORTANCE_HIGH)
            ch.setShowBadge(true)
            mgr.createNotificationChannel(ch)
        }
        if (mgr.getNotificationChannel("calls") == null) {
            val ch = NotificationChannel("calls","Calls", NotificationManager.IMPORTANCE_HIGH)
            mgr.createNotificationChannel(ch)
        }
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, WakeSyncService::class.java)
            ctx.startForegroundService(i)
        }
    }
}