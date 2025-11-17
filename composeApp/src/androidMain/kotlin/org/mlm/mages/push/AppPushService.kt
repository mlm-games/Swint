package org.mlm.mages.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.mlm.mages.matrix.MatrixProvider

const val PUSH_PREFS = "unifiedpush_prefs"
const val PREF_ENDPOINT = "endpoint"
const val PREF_INSTANCE = "default"

@Serializable
private data class MatrixPushPayload(
    val eventId: String? = null,
    val roomId: String? = null,
    val prio: String? = null,
)

class PushReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        Log.d("PushReceiver", "Received action: $action")

        when (action) {
            "org.unifiedpush.android.connector.NEW_ENDPOINT" -> {
                val endpoint = intent.getStringExtra("endpoint") ?: return
                val instance = intent.getStringExtra("instance") ?: return
                val token = intent.getStringExtra("token")

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        saveEndpoint(context, endpoint, instance)
                        registerPusher(context, endpoint, instance, token)
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }

            "org.unifiedpush.android.connector.MESSAGE" -> {
                val instance = intent.getStringExtra("instance") ?: return
                val svc = MatrixProvider.get(context)

                val pairs = extractMatrixPushPayload(intent) // [(roomId, eventId)]
                if (svc.isLoggedIn() && pairs.isNotEmpty()) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Ensure keys updated if needed
                            runCatching { svc.port.encryptionCatchupOnce() }
                            var shown = 0
                            for ((roomId, eventId) in pairs) {
                                // Try exact fetch/decrypt of the pushed event
                                val notif = runCatching { svc.port.fetchNotification(roomId, eventId) }.getOrNull()
                                if (notif != null) {
                                    AndroidNotificationHelper.showSingleEvent(context, AndroidNotificationHelper.NotificationText(notif.sender, notif.body), roomId, eventId)
                                    shown++
                                }
                                if (shown >= 3) break // avoid spamming on bundled pushes
                            }
                            if (shown == 0) {
                                // Nothing fetched (e.g., E2EE keys missing) -> fall back to wake/sync path
                                WakeSyncService.start(context)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    // No parsable push payload or not logged in -> fall back
                    WakeSyncService.start(context)
                }
            }

            "org.unifiedpush.android.connector.UNREGISTERED" -> {
                val instance = intent.getStringExtra("instance") ?: return
                Log.d("PushReceiver", "Unregistered from instance: $instance")
                removeEndpoint(context, instance)
            }
        }
    }

    private suspend fun registerPusher(
        context: Context,
        endpoint: String,
        instance: String,
        token: String?,
    ) {
        val uri = endpoint.toUri()
        val pushKey = token ?: uri.getQueryParameter("token") ?: uri.lastPathSegment ?: instance
        val gatewayUrl =
            when {
                endpoint.contains("/_matrix/push/") -> {
                    endpoint.substringBefore("?").let { base ->
                        if (base.endsWith("/notify")) {
                            base
                        } else {
                            base.substringBefore("/_matrix") + "/_matrix/push/v1/notify"
                        }
                    }
                }

                else -> {
                    "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"
                }
            }
        val service = MatrixProvider.get(context)
        if (service.isLoggedIn()) {
            service.port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = pushKey,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang =
                    java.util.Locale
                        .getDefault()
                        .toLanguageTag(),
                profileTag = instance,
            )
        }
    }
}

// Helpers
fun saveEndpoint(
    context: Context,
    endpoint: String,
    instance: String,
) {
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE).edit {
        putString(PREF_ENDPOINT + "_$instance", endpoint)
    }
}

fun getEndpoint(
    context: Context,
    instance: String,
): String? =
    context
        .getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_ENDPOINT + "_$instance", null)

fun removeEndpoint(
    context: Context,
    instance: String,
) {
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE).edit {
        remove(PREF_ENDPOINT + "_$instance")
    }
}

private fun extractMatrixPushPayload(intent: Intent): List<Pair<String, String>> {
    // Returns list of (roomId, eventId)
    val raw = intent.getStringExtra("message")
        ?: intent.getByteArrayExtra("bytes")?.toString(Charsets.UTF_8)
        ?: return emptyList()

    // Very tolerant parsing: try top-level event/room, else array field
    return try {
        val obj = org.json.JSONObject(raw)
        val pairs = mutableListOf<Pair<String, String>>()
        // Case A: top-level { "event_id": "...", "room_id": "..." }
        if (obj.has("event_id") && obj.has("room_id")) {
            val eid = obj.optString("event_id")
            val rid = obj.optString("room_id")
            if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
        }
        // Case B: array (e.g., "events" | "notifications"): [{event_id, room_id}, ...]
        val keys = arrayOf("events", "notifications", "notification")
        for (k in keys) {
            if (obj.has(k)) {
                val arr = obj.optJSONArray(k)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        val eid = it.optString("event_id")
                        val rid = it.optString("room_id")
                        if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
                    }
                } else {
                    // Some gateways use an object "notification": { event_id, room_id }
                    val it = obj.optJSONObject(k)
                    if (it != null) {
                        val eid = it.optString("event_id")
                        val rid = it.optString("room_id")
                        if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
                    }
                }
            }
        }
        pairs.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}