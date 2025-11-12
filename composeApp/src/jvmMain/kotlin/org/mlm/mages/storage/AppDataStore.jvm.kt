package org.mlm.mages.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

private const val DIR_NAME = ".mages"
private const val FILE_NAME = "mages.preferences_pb"

private object AppDataStoreHolderJvm {
    @Volatile var instance: DataStore<Preferences>? = null
}

fun provideAppDataStore(): DataStore<Preferences> {
    AppDataStoreHolderJvm.instance?.let { return it }

    synchronized(AppDataStoreHolderJvm) {
        AppDataStoreHolderJvm.instance?.let { return it }

        val home = System.getProperty("user.home")
        val dir: Path = "$home/$DIR_NAME".toPath()
        FileSystem.SYSTEM.createDirectories(dir)
        val path = (dir / FILE_NAME)

        val ds = createDataStore { path.toString() }
        AppDataStoreHolderJvm.instance = ds
        return ds
    }
}