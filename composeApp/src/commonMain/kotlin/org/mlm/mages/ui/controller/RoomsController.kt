package org.mlm.mages.ui.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.platform.Notifier
import org.mlm.mages.storage.loadLong
import org.mlm.mages.storage.saveLong
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

    private var lastHeartbeatRefreshMs = 0L
    private fun actKey(roomId: String) = "room_last_activity_ts:$roomId"

    private var roomListToken: ULong? = null

    init {
        observeConnection()
        observeRoomList()
    }

    fun observeRoomList() {
        roomListToken?.let { service.port.unobserveRoomList(it) }
        roomListToken = service.port.observeRoomList(object : MatrixPort.RoomListObserver {
            override fun onReset(items: List<MatrixPort.RoomListEntry>) {
                _state.update {
                    it.copy(
                        rooms = items.map { e -> RoomSummary(e.roomId, e.name) },
                        unread = items.associate { e -> e.roomId to e.unread.toInt() },
                        lastActivity = items.associate { e -> e.roomId to e.lastTs }
                    )
                }
            }
            override fun onUpdate(item: MatrixPort.RoomListEntry) {
                _state.update { st ->
                    val updatedRooms = st.rooms.toMutableList().apply {
                        val i = indexOfFirst { it.id == item.roomId }
                        val rs = RoomSummary(item.roomId, item.name)
                        if (i >= 0) set(i, rs) else add(rs)
                    }
                    st.copy(
                        rooms = updatedRooms,
                        unread = st.unread.toMutableMap().apply { put(item.roomId, item.unread.toInt()) },
                        lastActivity = st.lastActivity.toMutableMap().apply { put(item.roomId, item.lastTs) }
                    )
                }
            }
        })
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


    private fun refreshActivityLight() {
        val rooms = _state.value.rooms
        if (rooms.isEmpty()) return
        // (refreshes first 20 for now)
        val top = rooms.take(20)
        fillLastActivityBackground(top)
    }

    private fun key(roomId: String) = "room_read_ts:$roomId"

    fun refreshRooms() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            val rooms = runCatching { service.listRooms() }.getOrElse {
                _state.update { s -> s.copy(isBusy = false, error = "Failed to load rooms: ${it.message}") }
                return@launch
            }

            val cachedActivity = withContext(Dispatchers.Default) {
                buildMap {
                    for (r in rooms) {
                        val ts = runCatching { loadLong(dataStore, actKey(r.id)) }.getOrNull()
                        if (ts != null) put(r.id, ts)
                    }
                }
            }

            // rooms + cached activity
            _state.update {
                it.copy(
                    rooms = rooms,
                    lastActivity = cachedActivity,
                    isBusy = false
                )
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

            runCatching {
                val me = service.port.whoami()
                for (room in rooms) {
                    val prevTs = cachedActivity[room.id] ?: 0L
                    val newTs = lastActivityMap[room.id] ?: 0L
                    val unread = unreadMap[room.id] ?: 0
                    if (newTs > prevTs && unread > 0) {
                        // Build a short preview from recent messages newer than lastRead
                        val recent = runCatching { service.loadRecent(room.id, 20) }.getOrDefault(emptyList())
                        val lastRead = runCatching { loadLong(dataStore, key(room.id)) }.getOrNull() ?: 0L
                        val previewLines = recent
                            .filter { it.timestamp > lastRead && it.sender != me }
                            .takeLast(3)
                            .joinToString("\n") { e ->
                                val who = e.sender.substringBefore(':', e.sender)
                                "$who: ${e.body.take(120)}"
                            }
                        if (previewLines.isNotBlank()) {
                            Notifier.notifyRoom(room.name, previewLines)
                        }
                    }
                }
            }.onFailure {
            // silence.
            }

            val missing = rooms.filter { it.id !in cachedActivity }
            if (missing.isNotEmpty()) {
                fillLastActivityBackground(missing)
            }
        }
    }

    private fun fillLastActivityBackground(rooms: List<RoomSummary>) {
        scope.launch(Dispatchers.Default) {
            val sem = kotlinx.coroutines.sync.Semaphore(permits = 8)
            val updates = rooms.map { room ->
                async {
                    sem.acquire()
                    try {
                        val recent = runCatching { service.loadRecent(room.id, 1) }.getOrElse { emptyList() }
                        val ts = recent.firstOrNull()?.timestamp ?: 0L
                        if (ts > 0) {
                            runCatching { saveLong(dataStore, actKey(room.id), ts) }
                        }
                        room.id to ts
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll().toMap()

            if (updates.isNotEmpty()) {
                _state.update { st ->
                    val merged = st.lastActivity.toMutableMap()
                    for ((rid, ts) in updates) if (ts > 0) merged[rid] = ts
                    st.copy(lastActivity = merged)
                }
            }
        }
    }

    fun setSearchQuery(q: String) {
        _state.update { it.copy(roomSearchQuery = q) }
    }

    fun open(room: RoomSummary) = onOpenRoom(room)
}