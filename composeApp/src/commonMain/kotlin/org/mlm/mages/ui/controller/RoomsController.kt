package org.mlm.mages.ui.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.platform.Notifier
import org.mlm.mages.storage.loadLong
import org.mlm.mages.storage.saveLong
import org.mlm.mages.ui.RoomsUiState

class RoomsController(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>,
    private val onOpenRoom: (RoomSummary) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(RoomsUiState(isLoading = true))
    val state: StateFlow<RoomsUiState> = _state

    private var connToken: ULong? = null
    private var roomListToken: ULong? = null
    private var initialized = false

    private val notificationJobs = mutableMapOf<String, Job>()

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
                        lastActivity = items.associate { e -> e.roomId to e.lastTs },
                        isLoading = false
                    )
                }
                initialized = true

                scope.launch {
                    val zeros = items.filter { it.unread.toInt() == 0 }.take(20)
                    if (zeros.isEmpty()) return@launch
                    val pairs = coroutineScope {
                        zeros.map { e ->
                            async {
                                val cnt = runCatching { service.port.roomUnreadStats(e.roomId)?.messages?.toInt() ?: 0 }
                                    .getOrDefault(0)
                                e.roomId to cnt
                            }
                        }.toList().awaitAll()
                    }
                    _state.update { st ->
                        val m = st.unread.toMutableMap()
                        pairs.forEach { (rid, cnt) -> if ((m[rid] ?: 0) == 0 && cnt > 0) m[rid] = cnt }
                        st.copy(unread = m)
                    }
                }

                items.forEach { entry ->
                    startNotificationObserver(entry.roomId, entry.name)
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
                        lastActivity = st.lastActivity.toMutableMap().apply { put(item.roomId, item.lastTs) },
                        isLoading = false
                    )
                }

                startNotificationObserver(item.roomId, item.name)
            }
        })

        // Timeout fallback
        scope.launch {
            delay(5000)
            if (!initialized) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleUnreadOnly() {
        val tok = roomListToken ?: return
        val next = !_state.value.unreadOnly
        _state.update { it.copy(unreadOnly = next) }
        service.port.roomListSetUnreadOnly(tok, next)
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

    fun refreshRooms() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val rooms = runCatching { service.listRooms() }.getOrElse {
                _state.update { s -> s.copy(isLoading = false, error = "Failed to load rooms: ${it.message}") }
                return@launch
            }

            val cachedActivity = withContext(Dispatchers.IO) {
                buildMap {
                    for (r in rooms) {
                        val ts = runCatching { loadLong(dataStore, actKey(r.id)) }.getOrNull()
                        if (ts != null) put(r.id, ts)
                    }
                }
            }

            _state.update {
                it.copy(
                    rooms = rooms,
                    lastActivity = cachedActivity,
                    isLoading = false
                )
            }

            val unreadMap = withContext(Dispatchers.IO) {
                coroutineScope {
                    rooms.map { room ->
                        async {
                            val cnt = runCatching {
                                service.port.roomUnreadStats(room.id)?.messages?.toInt() ?: 0
                            }.getOrDefault(0)
                            room.id to cnt
                        }
                    }.associate { it.await() }
                }
            }

            _state.update {
                it.copy(
                    rooms = rooms,
                    unread = unreadMap
                )
            }

            val missing = rooms.filter { it.id !in cachedActivity }
            if (missing.isNotEmpty()) {
                fillLastActivityBackground(missing)
            }
        }
    }

    private fun actKey(roomId: String) = "room_last_activity_ts:$roomId"

    private fun fillLastActivityBackground(rooms: List<RoomSummary>) {
        scope.launch {
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
            }.associate { it.await() }

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

    fun setUnreadOnly(unreadOnly: Boolean) {
        val tok = roomListToken ?: return
        service.port.roomListSetUnreadOnly(tok, unreadOnly)
    }

    fun open(room: RoomSummary) = onOpenRoom(room)

    private fun startNotificationObserver(roomId: String, roomName: String) {
        if (notificationJobs.containsKey(roomId)) return

        val job = scope.launch {
            try {
                service.timelineDiffs(roomId).collect { diff ->
                    if (diff is TimelineDiff.Insert) {
                        val event = diff.item
                        val myId = service.port.whoami()
                        val senderIsMe = event.sender == myId

                        if (Notifier.shouldNotify(roomId, senderIsMe)) {
                            val senderName = event.sender
                                .removePrefix("@")
                                .substringBefore(":")

                            Notifier.notifyRoom(
                                title = "$senderName in $roomName",
                                body = event.body.take(100)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                notificationJobs.remove(roomId)
            }
        }
        notificationJobs[roomId] = job
    }

}
