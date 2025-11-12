package org.mlm.mages.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import okio.Path.Companion.toPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// The file must end with .preferences_pb per DataStore requirements.
fun createDataStore(producePath: () -> String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { producePath().toPath() }
    )

// Simple helpers for string save/load.
suspend fun saveString(ds: DataStore<Preferences>, key: String, value: String) {
    val k = stringPreferencesKey(key)
    ds.edit { it[k] = value }
}

suspend fun loadString(ds: DataStore<Preferences>, key: String): String? {
    val k = stringPreferencesKey(key)
    return ds.data.map { it[k] }.first()
}

suspend fun saveLong(ds: DataStore<Preferences>, key: String, value: Long) {
    val k = longPreferencesKey(key)
    ds.edit { it[k] = value }
}

suspend fun loadLong(ds: DataStore<Preferences>, key: String): Long? {
    val k = longPreferencesKey(key)
    return ds.data.map { it[k] }.first()
}