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
import org.mlm.mages.R

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
            .setSmallIcon(R.drawable.icon_tray)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(notifId, nobj)
    }
}