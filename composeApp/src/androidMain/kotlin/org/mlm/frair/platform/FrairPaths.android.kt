package org.mlm.frair.platform

import android.content.Context
import java.io.File

object FrairPaths {
    @Volatile private var storeDir: String? = null

    fun init(context: Context) {
        if (storeDir == null) {
            val base = File(context.filesDir, "frair_store")
            if (!base.exists()) base.mkdirs()
            storeDir = base.absolutePath
        }
    }

    fun storeDir(): String {
        return storeDir ?: throw IllegalStateException("FrairPaths not initialized. Call FrairPaths.init(context) in Application/Activity.onCreate.")
    }
}