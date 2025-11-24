package org.mlm.mages.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class RoomNotifMode { Default, MentionsOnly, Mute }

private fun key(roomId: String) = stringPreferencesKey("room:notif:$roomId")

suspend fun getRoomNotifMode(ds: DataStore<Preferences>, roomId: String): RoomNotifMode {
    val raw = ds.data.map { it[key(roomId)] }.first()
    return when (raw) {
        "MentionsOnly" -> RoomNotifMode.MentionsOnly
        "Mute" -> RoomNotifMode.Mute
        else -> RoomNotifMode.Default
    }
}

suspend fun setRoomNotifMode(ds: DataStore<Preferences>, roomId: String, mode: RoomNotifMode) {
    ds.edit { it[key(roomId)] = mode.name }
}

// Convenience filter
fun shouldNotify(mode: RoomNotifMode, hasMention: Boolean): Boolean =
    when (mode) {
        RoomNotifMode.Default -> true
        RoomNotifMode.MentionsOnly -> hasMention
        RoomNotifMode.Mute -> false
    }