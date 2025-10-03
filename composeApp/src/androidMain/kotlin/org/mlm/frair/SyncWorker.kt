package org.mlm.frair

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FrairSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Sync.onSyncTick(applicationContext)
        return Result.success()
    }
}