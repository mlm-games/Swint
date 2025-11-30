package org.mlm.mages.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.mlm.mages.matrix.MatrixProvider
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import kotlinx.coroutines.cancel
import org.mlm.mages.notifications.getRoomNotifMode
import org.mlm.mages.notifications.shouldNotify
import org.mlm.mages.storage.provideAppDataStore

const val PUSH_PREFS = "unifiedpush_prefs"
const val PREF_ENDPOINT = "endpoint"
const val PREF_INSTANCE = "default"

@Serializable
private data class MatrixPushPayload(
    val eventId: String? = null,
    val roomId: String? = null,
    val prio: String? = null,
)

/**
 * UnifiedPush entrypoint
 */
class AppPushService : PushService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val url = endpoint.url
        scope.launch {
            saveEndpoint(applicationContext, url, instance)
            // Register with Matrix push gateway (same logic as before)
            registerPusher(applicationContext, url, instance, token = null)
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val raw = try {
            message.content.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
        val svc = MatrixProvider.get(this)
        val pairs = extractMatrixPushPayload(raw) // [(roomId, eventId)]

        if (svc.isLoggedIn() && pairs.isNotEmpty()) {
            scope.launch {
                runCatching { svc.port.encryptionCatchupOnce() }
                var shown = 0
                for ((roomId, eventId) in pairs) {
                    val notif =
                        runCatching { svc.port.fetchNotification(roomId, eventId) }.getOrNull()
                    if (notif != null) {
                        val ds = provideAppDataStore(this@AppPushService)
                        val mode = runCatching {
                            getRoomNotifMode(
                                ds,
                                roomId
                            )
                        }.getOrDefault(org.mlm.mages.notifications.RoomNotifMode.Default)
                        if (shouldNotify(mode, notif.hasMention)) {
                            AndroidNotificationHelper.showSingleEvent(
                                this@AppPushService,
                                AndroidNotificationHelper.NotificationText(
                                    notif.sender,
                                    notif.body
                                ),
                                roomId, eventId
                            )
                            shown++
                        }
                    }
                    if (shown >= 3) break
                }
                if (shown == 0) {
                }
            }
        } else {
        }
    }

    override fun onUnregistered(instance: String) {
        Log.i("AppPushService", "Unregistered: $instance")
        removeEndpoint(applicationContext, instance)
    }

    override fun onRegistrationFailed(
        reason: org.unifiedpush.android.connector.FailedReason,
        instance: String
    ) {
        Log.w("AppPushService", "Registration failed for $instance: $reason")
    }

    override fun onTempUnavailable(instance: String) {
        Log.i("AppPushService", "Temp unavailable for $instance")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun registerPusher(
        context: Context,
        endpoint: String,
        instance: String,
        token: String?,  // for embedded FCM distributor
    ) {
        val gatewayUrl = GatewayResolver.resolveGateway(endpoint)

        val pushKey = token ?: endpoint

        val service = MatrixProvider.get(context)
        if (service.isLoggedIn()) {
            service.port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = pushKey,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang = java.util.Locale.getDefault().toLanguageTag(),
                profileTag = instance,
            )
        }
    }
}

// Helpers
fun saveEndpoint(context: Context, endpoint: String, instance: String) {
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE).edit {
        putString(PREF_ENDPOINT + "_$instance", endpoint)
    }
}

fun getEndpoint(context: Context, instance: String): String? =
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_ENDPOINT + "_$instance", null)

fun removeEndpoint(context: Context, instance: String) {
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE).edit {
        remove(PREF_ENDPOINT + "_$instance")
    }
}

private fun extractMatrixPushPayload(raw: String): List<Pair<String, String>> {
    if (raw.isBlank()) return emptyList()
    return try {
        val obj = org.json.JSONObject(raw)
        val pairs = mutableListOf<Pair<String, String>>()

        // Try Element/spec format {"notification": {e..}}
        val notification = obj.optJSONObject("notification")
        if (notification != null) {
            val eid = notification.optString("event_id", "")
            val rid = notification.optString("room_id", "")
            if (eid.isNotBlank() && rid.isNotBlank()) {
                pairs += rid to eid
                return pairs
            }
        }

        // fallback
        if (obj.has("event_id") && obj.has("room_id")) {
            val eid = obj.optString("event_id")
            val rid = obj.optString("room_id")
            if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
        }

        // Other fallbacks (array)
        val keys = arrayOf("events", "notifications")
        for (k in keys) {
            val arr = obj.optJSONArray(k) ?: continue
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val eid = it.optString("event_id")
                val rid = it.optString("room_id")
                if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
            }
        }

        pairs.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}