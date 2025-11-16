package org.mlm.mages.push

import android.content.Context
import androidx.work.*

object WakeSyncScheduler {
    private const val ONE_TIME = "wake-sync-once"
    private const val PERIODIC = "wake-sync-periodic"

    fun enqueueNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, SyncWorker.oneTime())
    }

    fun ensurePeriodic(context: Context, intervalMinutes: Long = 15) {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                SyncWorker.periodic(intervalMinutes)
            )
    }

    fun reschedulePeriodic(context: Context, intervalMinutes: Long = 15) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        ensurePeriodic(context, intervalMinutes)
    }
}