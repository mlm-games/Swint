package org.mlm.mages.matrix

import android.content.Context
import kotlinx.coroutines.runBlocking
import mages.SyncStatus
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.storage.loadString
import org.mlm.mages.storage.provideAppDataStore

object MatrixProvider {
    @Volatile private var service: MatrixService? = null

    fun get(context: Context): MatrixService {
        service?.let { return it }
        synchronized(this) {
            service?.let { return it }
            // Ensure store paths
            MagesPaths.init(context)
            // Use saved homeserver or sensible default
            val ds = provideAppDataStore(context)
            val hs = runBlocking { loadString(ds, "homeserver") } ?: "https://matrix.org"
            val s = MatrixService(createMatrixPort(hs))
            service = s
            return s
        }
    }

    fun reinit(context: Context, homeserver: String): MatrixService {
        synchronized(this) {
            service?.port?.close()
            MagesPaths.init(context)
            val s = MatrixService(createMatrixPort(homeserver))
            service = s
            return s
        }
    }

    @Volatile private var syncStarted = false

    fun ensureSyncStarted(): MatrixPort.SyncStatus {
        var st : MatrixPort.SyncStatus = MatrixPort.SyncStatus(MatrixPort.SyncPhase.Error, "Service cant be init")
        val s = service ?: return st
        if (!syncStarted && s.isLoggedIn()) {
            syncStarted = true
            s.startSupervisedSync(object : MatrixPort.SyncObserver {
                override fun onState(status: MatrixPort.SyncStatus) {
                    st = status
                }
            })
        }
        return st
    }
}
