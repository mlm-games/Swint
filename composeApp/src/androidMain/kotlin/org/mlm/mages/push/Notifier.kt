package org.mlm.mages.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import org.mlm.mages.MainActivity
import org.mlm.mages.MatrixService
import org.mlm.mages.notifications.RoomNotifMode
import org.mlm.mages.notifications.getRoomNotifMode
import org.mlm.mages.storage.provideAppDataStore

object Notifier {
    /// fetches last 20 events per room, shows only those newer than stored lastReadTs
    suspend fun showNewEventNotifications(ctx: Context, service: MatrixService) {
        val ds = provideAppDataStore(ctx)
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel("messages") == null) {
                val ch =
                    NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH)
                mgr.createNotificationChannel(ch)
            }
        }

        // Avoids backlog flood on first login
        val baseline = org.mlm.mages.storage.loadLong(ds, "notif:baseline_ms")
            ?: run {
                org.mlm.mages.storage.saveLong(ds, "notif:baseline_ms", System.currentTimeMillis())
                return
            }

        val rooms = service.listRooms()
        val me = service.port.whoami()

        val maxRoomsToScan = 30
        val maxNotificationsThisPass = 5
        var posted = 0

        for (room in rooms.take(maxRoomsToScan)) {
            if (posted >= maxNotificationsThisPass) break

            val mode = runCatching { getRoomNotifMode(provideAppDataStore(ctx), room.id) }
                .getOrDefault(RoomNotifMode.Default)
            if (mode == RoomNotifMode.Mute) continue
            if (mode == RoomNotifMode.MentionsOnly) {
                // avoid noise
                continue
            }

            // Per-room watermark (last time we notified something in this room)
            val lastNotifiedTs = org.mlm.mages.storage.loadLong(ds, "notif:last_ts:${room.id}") ?: baseline
            val lastReadTs     = org.mlm.mages.storage.loadLong(ds, "room_read_ts:${room.id}") ?: 0L
            val gateTs = maxOf(lastNotifiedTs, lastReadTs)

            val recent = runCatching { service.loadRecent(room.id, 20) }.getOrDefault(emptyList())
            val news = recent
                .filter { it.timestamp > gateTs && it.sender != me }
                .takeLast(3)

            if (news.isEmpty()) continue

            val title = room.name
            val lines = news.joinToString("\n") { "${it.sender.substringBefore(':')}: ${it.body.take(120)}" }
            val notifId = title.hashCode()

            val open = Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = "mages://room?id=${room.id}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(ctx, notifId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val n = NotificationCompat.Builder(ctx, "messages")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(lines)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            mgr.notify(notifId, n)
            posted += 1

            val maxTs = news.maxOf { it.timestamp }
            org.mlm.mages.storage.saveLong(ds, "notif:last_ts:${room.id}", maxTs)
        }
    }
}


object AndroidNotificationHelper {
    data class NotificationText(val title: String, val body: String) // mirror FFI return

    fun showSingleEvent(ctx: Context, n: NotificationText, roomId: String, eventId: String) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel("messages") == null) {
                val ch = NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH)
                mgr.createNotificationChannel(ch)
            }
        }
        val open = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "mages://room?id=$roomId".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val notifId = (roomId + eventId).hashCode()
        val pi = PendingIntent.getActivity(ctx, notifId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nobj = NotificationCompat.Builder(ctx, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(notifId, nobj)
    }
}