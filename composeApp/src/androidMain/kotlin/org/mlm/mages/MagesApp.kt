package org.mlm.mages

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.platform.AppCtx
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.push.PusherReconciler

class MagesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MagesPaths.init(this)
        AppCtx.init(this)
        val svc = MatrixProvider.get(this)
        Log.println(Log.INFO, "Mages", "Sync started with status: ${MatrixProvider.ensureSyncStarted()}")

        CoroutineScope(Dispatchers.Default).launch {
            runCatching { PusherReconciler.ensureServerPusherRegistered(this@MagesApp) }
        }
    }
}