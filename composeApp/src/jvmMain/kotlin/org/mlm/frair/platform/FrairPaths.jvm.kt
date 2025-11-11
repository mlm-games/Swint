package org.mlm.frair.platform

import java.io.File

object FrairPaths {
    @Volatile private var storeDir: String? = null

    fun init() {
        if (storeDir == null) {
            val home = System.getProperty("user.home")
            val base = File(home, ".frair/store")
            if (!base.exists()) base.mkdirs()
            storeDir = base.absolutePath
        }
    }

    fun storeDir(): String {
        return storeDir ?: run {
            init()
            storeDir!!
        }
    }
}