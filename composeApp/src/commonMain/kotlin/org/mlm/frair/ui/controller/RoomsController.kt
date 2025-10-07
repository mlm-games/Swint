package org.mlm.frair.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mlm.frair.MatrixService
import org.mlm.frair.RoomSummary
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.ui.RoomsUiState

class RoomsController(
    private val service: MatrixService,
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

    fun refreshRooms() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            val rooms = runCatching { service.listRooms() }.getOrElse {
                _state.update { s -> s.copy(isBusy = false, error = "Failed to load rooms: ${it.message}") }
                return@launch
            }
            _state.update { it.copy(rooms = rooms, isBusy = false) }
        }
    }

    fun setSearchQuery(q: String) {
        _state.update { it.copy(roomSearchQuery = q) }
    }

    fun open(room: RoomSummary) = onOpenRoom(room)
}