package org.mlm.frair

import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

object Sync {
    const val ACTION_SYNC_TICK = "org.mlm.frair.SYNC_TICK"

    fun scheduleBackgroundSync(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<FrairSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "frair_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    // Called by the worker (or could be called by a push receiver)
    fun onSyncTick(ctx: Context) {
        // If UI is alive, refresh rooms; otherwise no-op.
//        AppSingleton.store?.let { store ->
//            store.dispatch(Intent.RefreshRooms)
//        } ?: run {
//            // not running; minimal refresh via a headless port later
//        }
    }
}
