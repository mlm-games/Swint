package org.mlm.frair.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

private const val DIR_NAME = ".frair"
private const val FILE_NAME = "frair.preferences_pb"

fun provideAppDataStore(): DataStore<Preferences> {
    val home = System.getProperty("user.home")
    val dir: Path = "$home/$DIR_NAME".toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    val path = (dir / FILE_NAME)
    return createDataStore { path.toString() }
}