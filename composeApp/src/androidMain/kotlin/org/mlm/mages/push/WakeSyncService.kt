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

class NotificationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel("sync") == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    "sync",
                    "Background sync",
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        if (mgr.getNotificationChannel("messages") == null) {
            mgr.createNotificationChannel(
                NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val roomId = intent?.getStringExtra(EXTRA_ROOM) ?: return START_NOT_STICKY
        val eventId = intent.getStringExtra(EXTRA_EVENT) ?: return START_NOT_STICKY

        val fg =
            NotificationCompat
                .Builder(this, "sync")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Fetching notification")
                .setContentText("Decrypting…")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        startForeground(3, fg)

        scope.launch {
            try {
                val service = MatrixProvider.get(this@NotificationService)
                if (!service.isLoggedIn()) return@launch

                val rn =
                    runCatching {
                        service.port.renderNotification(roomId, eventId)
                    }.getOrNull()

                if (rn != null) {
                    val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val open =
                        Intent(this@NotificationService, org.mlm.mages.MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = "mages://room?id=$roomId".toUri()
                            START_FLAG_REDELIVERY or START_FLAG_RETRY
                        }
                    val notifId = eventId.hashCode()
                    val n =
                        NotificationCompat
                            .Builder(this@NotificationService, "messages")
                            .setSmallIcon(R.drawable.ic_launcher_monochrome)
                            .setContentTitle(rn.roomName)
                            .setContentText("${rn.sender}: ${rn.body}")
                            .setStyle(NotificationCompat.BigTextStyle().bigText("${rn.sender}: ${rn.body}"))
                            .setAutoCancel(true)
                            .setContentIntent(
                                PendingIntent.getActivity(
                                    this@NotificationService,
                                    notifId,
                                    open,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                                ),
                            ).build()
                    mgr.notify(notifId, n)
                } else {
                    // filtered out / not found -> fallback
                    Notifier.showNewEventNotifications(this@NotificationService, service)
                }
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

    companion object {
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_EVENT = "event"

        fun start(
            ctx: Context,
            roomId: String,
            eventId: String,
        ) {
            val i =
                Intent(ctx, NotificationService::class.java)
                    .putExtra(EXTRA_ROOM, roomId)
                    .putExtra(EXTRA_EVENT, eventId)
            ctx.startForegroundService(i)
        }
    }
}
