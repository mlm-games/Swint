package org.mlm.mages.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.ui.RoomsUiState

class RoomsViewModel(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>
) : BaseViewModel<RoomsUiState>(RoomsUiState(isLoading = true)) {

    // One-time events
    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var connToken: ULong? = null
    private var roomListToken: ULong? = null
    private var initialized = false

    init {
        observeConnection()
        observeRoomList()
    }

    //  Public Actions 

    fun setSearchQuery(query: String) {
        updateState { copy(roomSearchQuery = query) }
        recomputeGroupedRooms()
    }

    fun toggleUnreadOnly() {
        val next = !currentState.unreadOnly
        updateState { copy(unreadOnly = next) }

        roomListToken?.let { token ->
            service.port.roomListSetUnreadOnly(token, next)
        }

        recomputeGroupedRooms()
    }

    fun openRoom(room: RoomSummary) {
        launch {
            _events.send(Event.OpenRoom(room.id, room.name))
        }
    }

    fun refresh() {
        // Room list is reactive, but we can force a re-observe
        roomListToken?.let { service.port.unobserveRoomList(it) }
        observeRoomList()
    }

    //  Private Methods 

    private fun observeRoomList() {
        roomListToken?.let { service.port.unobserveRoomList(it) }

        roomListToken = service.port.observeRoomList(object : MatrixPort.RoomListObserver {
            override fun onReset(items: List<MatrixPort.RoomListEntry>) {
                updateState {
                    copy(
                        rooms = items.map { e -> RoomSummary(e.roomId, e.name) },
                        unread = items.associate { e -> e.roomId to e.notifications.toInt() },
                        favourites = items.filter { e -> e.isFavourite }.map { e -> e.roomId }.toSet(),
                        lowPriority = items.filter { e -> e.isLowPriority }.map { e -> e.roomId }.toSet(),
                        isLoading = false
                    )
                }
                recomputeGroupedRooms()
                initialized = true
            }

            override fun onUpdate(item: MatrixPort.RoomListEntry) {
                // SDK sends full list via onReset, individual updates are rare
                // But handle them for completeness
                updateState {
                    val updatedRooms =rooms.map { room ->
                        if (room.id == item.roomId) RoomSummary(item.roomId, item.name)
                        else room
                    }
                    val updatedUnread =unread.toMutableMap().apply {
                        put(item.roomId, item.notifications.toInt())
                    }
                    val updatedFavourites = if (item.isFavourite) {
                       favourites + item.roomId
                    } else {
                       favourites - item.roomId
                    }
                    val updatedLowPriority = if (item.isLowPriority) {
                       lowPriority + item.roomId
                    } else {
                       lowPriority - item.roomId
                    }

                   copy(
                        rooms = updatedRooms,
                        unread = updatedUnread,
                        favourites = updatedFavourites,
                        lowPriority = updatedLowPriority
                    )
                }
                recomputeGroupedRooms()
            }
        })

        // Timeout fallback
        viewModelScope.launch {
            delay(5000)
            if (!initialized) {
                updateState { copy(isLoading = false) }
            }
        }
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
                updateState { copy(offlineBanner = banner) }
            }
        })
    }

    private fun recomputeGroupedRooms() {
        val s = currentState
        var list = s.rooms

        // Apply search filter
        val query = s.roomSearchQuery.trim()
        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.id.contains(query, ignoreCase = true)
            }
        }

        // Apply unread filter
        if (s.unreadOnly) {
            list = list.filter { (s.unread[it.id] ?: 0) > 0 }
        }

        // Group by category
        val favourites = list.filter { s.favourites.contains(it.id) }
        val lowPriority = list.filter { s.lowPriority.contains(it.id) }
        val normal = list.filter {
            !s.favourites.contains(it.id) && !s.lowPriority.contains(it.id)
        }

        updateState {
            copy(
                favouriteRooms = favourites,
                normalRooms = normal,
                lowPriorityRooms = lowPriority
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        roomListToken?.let { service.port.unobserveRoomList(it) }
        connToken?.let { service.stopConnectionObserver(it) }
    }
}