package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.delay
import org.mlm.mages.MatrixService
import org.mlm.mages.NotifierImpl
import org.mlm.mages.notifications.getRoomNotifMode
import org.mlm.mages.notifications.shouldNotify
import org.mlm.mages.storage.loadLong
import org.mlm.mages.storage.saveLong
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
actual fun BindNotifications(service: MatrixService, dataStore: DataStore<Preferences>) {
    LaunchedEffect(service) {
        // Baseline: avoid flooding on first run
        var baseline = loadLong(dataStore, "desktop:notif_baseline_ms")
        if (baseline == null) {
            baseline = System.currentTimeMillis()
            saveLong(dataStore, "desktop:notif_baseline_ms", baseline)
        }

        val me = service.port.whoami()

        while (true) {
            delay(15_000L) // poll every ... secs

            if (!service.isLoggedIn()) continue

            val items = runCatching {
                service.port.fetchNotificationsSince(
                    sinceMs = baseline!!,
                    maxRooms = 50,
                    maxEvents = 20
                )
            }.getOrElse { emptyList() }

            if (items.isEmpty()) continue

            var maxTs = baseline

            for (n in items) {
                // optional room-level mode reuse (same as Android)
                val mode = runCatching {
                    getRoomNotifMode(dataStore, n.roomId)
                }.getOrDefault(org.mlm.mages.notifications.RoomNotifMode.Default)

                if (!shouldNotify(mode, n.hasMention)) continue

                val senderIsMe = me != null && me == n.senderUserId
                if (!Notifier.shouldNotify(n.roomId, senderIsMe)) {
                    continue
                }

                Notifier.notifyRoom(
                    title = n.roomName,
                    body = "${n.sender}: ${n.body}"
                )

                if (n.tsMs > maxTs!!) {
                    maxTs = n.tsMs
                }
            }

            if (maxTs != null) {
                if (maxTs > baseline!!) {
                    baseline = maxTs
                    saveLong(dataStore, "desktop:notif_baseline_ms", baseline)
                }
            }
        }
    }
}

@Composable
actual fun rememberQuitApp(): () -> Unit = {
    exitProcess(0)
}