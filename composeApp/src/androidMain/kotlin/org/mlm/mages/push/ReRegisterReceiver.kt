package org.mlm.mages.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReRegisterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            PushManager.registerSilently(context)
            // Also reconcile server-side Matrix pusher if we already have an endpoint saved
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    runCatching { PusherReconciler.ensureServerPusherRegistered(context) }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}