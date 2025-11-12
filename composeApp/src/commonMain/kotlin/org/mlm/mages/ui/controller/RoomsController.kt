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
    private var sendsJob: Job? = null

    init {
        observeConnection()
        observeSends()
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

    private fun observeSends() {
        sendsJob?.cancel()
        sendsJob = scope.launch {
            service.observeSends().collect { upd ->
                // Any outgoing event bumps “recently chatted”
                val now = service.nowMs()
                _state.update { st ->
                    val m = st.lastOutgoing.toMutableMap()
                    m[upd.roomId] = now
                    st.copy(lastOutgoing = m)
                }
            }
        }
    }

    fun refreshRooms() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            val rooms = runCatching { service.listRooms() }.getOrElse {
                _state.update { s -> s.copy(isBusy = false, error = "Failed to load rooms: ${it.message}") }
                return@launch
            }
            // unread + last activity
            val results = withContext(Dispatchers.Default) {
                coroutineScope {
                    rooms.map { room ->
                        async {
                            val recent = runCatching { service.loadRecent(room.id, 50) }.getOrDefault(emptyList())
                            val lastTs = recent.maxOfOrNull { it.timestamp } ?: 0L
                            room.id to (recent to lastTs)
                        }
                    }.awaitAll().toMap()
                }
            }

            val unreadMap = buildMap {
                for ((rid, pair) in results) {
                    val (recent, _) = pair
                    val lastRead = loadLong(dataStore, key(rid)) ?: 0L
                    put(rid, recent.count { it.timestamp > lastRead })
                }
            }
            val lastActivityMap = buildMap {
                for ((rid, pair) in results) put(rid, pair.second)
            }

            _state.update {
                it.copy(
                    rooms = rooms,
                    unread = unreadMap,
                    lastActivity = lastActivityMap,
                    isBusy = false
                )
            }
        }
    }



    fun setSearchQuery(q: String) {
        _state.update { it.copy(roomSearchQuery = q) }
    }

    fun open(room: RoomSummary) = onOpenRoom(room)
}