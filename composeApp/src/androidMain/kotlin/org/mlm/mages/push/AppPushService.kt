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
private const val TAG = "UP-Mages"

/**
 * UnifiedPush entrypoint
 */
class AppPushService : PushService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val url = endpoint.url
        Log.i(TAG, "onNewEndpoint: instance=$instance url=$url")
        scope.launch {
            saveEndpoint(applicationContext, url, instance)
            registerPusher(applicationContext, url, instance, token = null)
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val raw = try {
            message.content.toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode message content", e)
            ""
        }

        val pairs = extractMatrixPushPayload(raw)
        Log.i(TAG, "Extracted ${pairs.size} events: $pairs")

        if (pairs.isEmpty()) {
            Log.w(TAG, "No events extracted from push")
            return
        }

        // Show message is unstable with the current method (bg network issues), for now this is fine
        for ((roomId, eventId) in pairs.take(3)) {
            AndroidNotificationHelper.showSingleEvent(
                this,
                AndroidNotificationHelper.NotificationText("New message", "You have a new message"),
                roomId, eventId
            )
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
        token: String?,
    ) {
        Log.d("PusherDebug", "=== registerPusher called ===")
        Log.d("PusherDebug", "endpoint: $endpoint")
        Log.d("PusherDebug", "instance: $instance")

        val gatewayUrl = GatewayResolver.resolveGateway(endpoint)
        Log.d("PusherDebug", "resolved gateway: $gatewayUrl")

        val pushKey = token ?: endpoint

        val service = MatrixProvider.get(context)
        val loggedIn = service.isLoggedIn()
        Log.d("PusherDebug", "isLoggedIn: $loggedIn")

        if (!loggedIn) {
            Log.w("PusherDebug", "NOT LOGGED IN - skipping pusher registration")
            return
        }

        try {
            val result = service.port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = pushKey,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang = java.util.Locale.getDefault().toLanguageTag(),
                profileTag = instance,
            )
            Log.d("PusherDebug", "registerUnifiedPush result: $result")
        } catch (e: Exception) {
            Log.e("PusherDebug", "registerUnifiedPush FAILED", e)
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