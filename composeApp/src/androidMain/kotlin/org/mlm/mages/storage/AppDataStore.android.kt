package org.mlm.mages.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.FileSystem
import okio.Path.Companion.toPath

private const val FILE_NAME = "mages.preferences_pb"

fun provideAppDataStore(context: Context): DataStore<Preferences> {
    // Use filesDir, app-private
    val dir = context.filesDir.absolutePath
    val path = "$dir/$FILE_NAME".toPath()
    // Make sure the directory exists
    FileSystem.SYSTEM.createDirectories(path.parent!!)
    return createDataStore { path.toString() }
}