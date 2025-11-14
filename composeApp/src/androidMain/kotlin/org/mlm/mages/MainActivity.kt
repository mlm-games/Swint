package org.mlm.mages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.storage.provideAppDataStore

class MainActivity : ComponentActivity() {
    private val deepLinkRoomIds = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val deepLinks = deepLinkRoomIds.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val dataStore = provideAppDataStore(this)
        ensureCallNotificationChannel()

        handleIntent(intent)
        MagesPaths.init(this)
        setContent {
            App(dataStore = dataStore, deepLinks = deepLinks)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.data?.let { uri ->
            if (uri.scheme == "mages" && uri.host == "room") {
                val roomId = uri.getQueryParameter("id")
                if (!roomId.isNullOrBlank()) {
                    deepLinkRoomIds.tryEmit(roomId)
                }
            }
        }
    }

    private fun ensureCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                "calls",
                "Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming calls"
                setSound(null, null) // TODO: sound configurable later
                enableVibration(true)
            }
            mgr.createNotificationChannel(channel)
        }
    }
}
