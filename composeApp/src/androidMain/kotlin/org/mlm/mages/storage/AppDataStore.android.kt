package org.mlm.mages.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.FileSystem
import okio.Path.Companion.toPath

private const val FILE_NAME = "mages.preferences_pb"

private object AppDataStoreHolder {
    @Volatile var instance: DataStore<Preferences>? = null
}

fun provideAppDataStore(context: Context): DataStore<Preferences> {
    AppDataStoreHolder.instance?.let { return it }

    val appCtx = context.applicationContext

    synchronized(AppDataStoreHolder) {
        AppDataStoreHolder.instance?.let { return it }

        val dir = appCtx.filesDir.absolutePath
        val path = "$dir/$FILE_NAME".toPath()
        FileSystem.SYSTEM.createDirectories(path.parent!!)

        val ds = createDataStore { path.toString() }
        AppDataStoreHolder.instance = ds
        return ds
    }
}