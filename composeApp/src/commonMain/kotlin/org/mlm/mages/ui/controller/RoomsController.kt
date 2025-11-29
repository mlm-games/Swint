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
                        unread = items.associate { e -> e.roomId to e.notifications.toInt() },
                        favourites = items.filter { e -> e.isFavourite }.map { e -> e.roomId }.toSet(),
                        lowPriority = items.filter { e -> e.isLowPriority }.map { e -> e.roomId }.toSet(),
                        isLoading = false
                    )
                }
                initialized = true

                items.forEach { entry ->
                    startNotificationObserver(entry.roomId, entry.name)
                }
            }

            override fun onUpdate(item: MatrixPort.RoomListEntry) {
                // Better for Maintainability, Rust always sends full list via onReset
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

    fun refreshUnreadCounts() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }

            val currentRooms = _state.value.rooms
            if (currentRooms.isEmpty()) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }

            val unreadMap = coroutineScope {
                currentRooms.map { room ->
                    async {
                        val cnt = runCatching {
                            service.port.roomUnreadStats(room.id)?.messages?.toInt() ?: 0
                        }.getOrDefault(0)
                        room.id to cnt
                    }
                }.associate { it.await() }
            }

            _state.update { st ->
                st.copy(unread = unreadMap, isLoading = false)
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

                        refreshUnreadCounts()
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