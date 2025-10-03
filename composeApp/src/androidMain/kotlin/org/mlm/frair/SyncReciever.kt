package org.mlm.frair

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Sync.ACTION_SYNC_TICK) {
            Sync.onSyncTick(context)
        }
    }
}