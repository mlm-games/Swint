package org.mlm.mages.ui.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.storage.loadLong
import org.mlm.mages.ui.RoomsUiState

class RoomsController(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>,
    private val onOpenRoom: (RoomSummary) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state

    private var connToken: ULong? = null
    private var syncStarted = false

    init {
        observeConnection()
        refreshRooms()
        startSync()
    }

    private fun observeConnection() {
        connToken?.let { service.stopConnectionObserver(it) }
        connToken = service.observeConnection(object : MatrixPort.ConnectionObserver {
            override fun onConnectionChange(state: MatrixPort.ConnectionState) {
                val banner = when (state) {
                    MatrixPort.ConnectionState.Disconnected -> "No connection"
                    MatrixPort.ConnectionState.Reconnecting -> "Reconnecting..."
                    MatrixPort.ConnectionState.Connecting -> "Connecting..."
                    else -> null
                }
                _state.update { it.copy(offlineBanner = banner) }
            }
        })
    }

    private fun startSync() {
        if (syncStarted) return
        syncStarted = true
        service.startSupervisedSync(object : MatrixPort.SyncObserver {
            override fun onState(status: MatrixPort.SyncStatus) {
                val banner = when (status.phase) {
                    MatrixPort.SyncPhase.BackingOff -> status.message ?: "Reconnecting…"
                    MatrixPort.SyncPhase.Error -> status.message ?: "Sync error"
                    else -> null
                }
                _state.update { it.copy(syncBanner = banner) }
            }
        })
    }

    private fun key(roomId: String) = "room_read_ts:$roomId"

    fun refreshRooms() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            val rooms = runCatching { service.listRooms() }.getOrElse {
                _state.update { s -> s.copy(isBusy = false, error = "Failed to load rooms: ${it.message}") }
                return@launch
            }
            _state.update { it.copy(rooms = rooms, isBusy = false) }

            // unread counts (concurrent, cap to 50 recent)
            val counts = withContext(Dispatchers.Default) {
                coroutineScope {
                    rooms.map { room ->
                        async {
                            val last = runCatching { loadLong(dataStore, key(room.id)) }.getOrNull() ?: 0L
                            val recent = runCatching { service.loadRecent(room.id, 50) }.getOrDefault(emptyList())
                            val unread = recent.count { it.timestamp > last }
                            room.id to unread
                        }
                    }.awaitAll().toMap()
                }
            }
            _state.update { it.copy(unread = counts) }
        }
    }


    fun setSearchQuery(q: String) {
        _state.update { it.copy(roomSearchQuery = q) }
    }

    fun open(room: RoomSummary) = onOpenRoom(room)
}