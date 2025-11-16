package org.mlm.mages.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReRegisterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            PushManager.registerSilently(context) // single acc.
        }
    }
}