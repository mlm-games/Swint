package org.mlm.mages.push

import android.content.Context
import android.util.Log
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.push.PushManager.getEndpoint

object PusherReconciler {
    private const val TAG = "PusherReconciler"

    suspend fun ensureServerPusherRegistered(context: Context, instance: String = PREF_INSTANCE) {
        val endpoint = getEndpoint(context, instance) ?: run {
            Log.i(TAG, "No saved UnifiedPush endpoint; nothing to reconcile")
            return
        }

        val gatewayUrl = GatewayResolver.resolveGateway(endpoint)

        val svc = MatrixProvider.get(context)
        if (!svc.isLoggedIn()) {
            Log.w(TAG, "Not logged in; will reconcile later")
            return
        }

        val ok = runCatching {
            svc.port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = endpoint,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang = java.util.Locale.getDefault().toLanguageTag(),
                profileTag = instance
            )
        }.getOrDefault(false)

        Log.i(TAG, "registerUnifiedPush(pushKey=$endpoint, gateway=$gatewayUrl, ok=$ok)")
    }
}