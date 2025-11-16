package org.mlm.mages.push

import android.content.Context
import android.net.Uri
import android.util.Log
import org.mlm.mages.matrix.MatrixProvider
import androidx.core.net.toUri

object PusherReconciler {
    private const val TAG = "PusherReconciler"

    suspend fun ensureServerPusherRegistered(context: Context, instance: String = PREF_INSTANCE) {
        val endpoint = getEndpoint(context, instance) ?: run {
            Log.i(TAG, "No saved UnifiedPush endpoint; nothing to reconcile")
            return
        }
        val uri = endpoint.toUri()
        val pushKey = uri.getQueryParameter("token") ?: uri.lastPathSegment ?: instance
        val gatewayUrl = when {
            endpoint.contains("/_matrix/push/") ->
                endpoint.substringBefore("?").let { base ->
                    if (base.endsWith("/notify")) base
                    else base.substringBefore("/_matrix") + "/_matrix/push/v1/notify"
                }
            else -> "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"
        }

        val svc = MatrixProvider.get(context)
        if (!svc.isLoggedIn()) {
            Log.w(TAG, "Not logged in; will reconcile later")
            return
        }
        val ok = runCatching {
            svc.port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = pushKey,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang = java.util.Locale.getDefault().toLanguageTag(),
                profileTag = instance
            )
        }.getOrDefault(false)

        Log.i(TAG, "registerUnifiedPush(appId=${context.packageName}, ok=$ok)")
    }
}
