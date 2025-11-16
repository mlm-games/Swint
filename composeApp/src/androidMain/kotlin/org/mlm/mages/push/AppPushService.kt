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
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val jsonStr: String? = intent.getStringExtra("message")
                        val bytes: ByteArray? = intent.getByteArrayExtra("bytes")
                        val raw = jsonStr ?: bytes?.toString(Charsets.UTF_8)
                        val (roomId, eventId) =
                            runCatching {
                                val payload =
                                    raw?.let {
                                        kotlinx.serialization.json.Json
                                            .decodeFromString<MatrixPushPayload>(it)
                                    }
                                payload?.roomId to payload?.eventId
                            }.getOrNull() ?: (null to null)

                        if (!roomId.isNullOrBlank() && !eventId.isNullOrBlank()) {
                            NotificationService.start(context, roomId, eventId)
                        } else {
                            // Works consistently even though it has a big delay, remove later if unneeded
                            WakeSyncService.start(context)
                        }
                    } finally {
                        pending.finish()
                    }
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
