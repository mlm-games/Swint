package org.mlm.mages.push

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mlm.mages.matrix.MatrixProvider

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val ds = org.mlm.mages.storage.provideAppDataStore(applicationContext)
        val baseline = org.mlm.mages.storage.loadLong(ds, "notif:baseline_ms")
        if (baseline == null) {
            org.mlm.mages.storage.saveLong(ds, "notif:baseline_ms", System.currentTimeMillis())
            return@withContext Result.success()
        }
        val service = MatrixProvider.get(applicationContext)
        if (!service.isLoggedIn()) return@withContext Result.success()

        runCatching { service.port.wakeSyncOnce(6000) }
        runCatching { service.port.encryptionCatchupOnce() }
        runCatching { Notifier.showNewEventNotifications(applicationContext, service) }

        Result.success()
    }

    companion object {
        fun oneTime(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }

        fun periodic(intervalMinutes: Long = 15): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // .setRequiresBatteryNotLow(true) // Skips on low battery
                .build()
            return PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("mages-poll")
                .build()
        }
    }
}