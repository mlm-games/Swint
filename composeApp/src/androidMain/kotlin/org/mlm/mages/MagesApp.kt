package org.mlm.mages

import android.app.Application
import android.util.Log
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.platform.AppCtx
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.push.WakeSyncScheduler

class MagesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MagesPaths.init(this)
        AppCtx.init(this)
        val svc = MatrixProvider.get(this)
        if (svc.isLoggedIn()) Log.println(Log.INFO, "Mages", "Sync started with status: ${MatrixProvider.ensureSyncStarted()}")
        WakeSyncScheduler.ensurePeriodic(this)
    }
}