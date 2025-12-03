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
import org.mlm.mages.matrix.LatestRoomEvent
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomListEntry
import org.mlm.mages.ui.LastMessageType
import org.mlm.mages.ui.RoomListItemUi
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

    private fun mapRoomSummary(entry: RoomListEntry): RoomSummary {
        return RoomSummary(
            id = entry.roomId,
            name = entry.name,
            avatarUrl = entry.avatarUrl,
            isDm = entry.isDm,
            isEncrypted = entry.isEncrypted
        )
    }

    private fun mapRoomEntryToUi(entry: RoomListEntry): RoomListItemUi {
        val lastEvent = entry.latestEvent
        val lastType = determineMessageType(lastEvent)
        val lastBody = formatBodyForPreview(lastEvent, lastType)

        return RoomListItemUi(
            roomId = entry.roomId,
            name = entry.name,
            avatarUrl = entry.avatarUrl,
            isDm = entry.isDm,
            isEncrypted = entry.isEncrypted,
            unreadCount = entry.notifications.toInt(),
            isFavourite = entry.isFavourite,
            isLowPriority = entry.isLowPriority,
            lastMessageBody = lastBody,
            lastMessageSender = lastEvent?.sender,
            lastMessageType = lastType,
            lastMessageTs = lastEvent?.timestamp
        )
    }

    private fun determineMessageType(event: LatestRoomEvent?): LastMessageType {
        if (event == null) return LastMessageType.Unknown
        if (event.isRedacted) return LastMessageType.Redacted

        val msgtype = event.msgtype
        val evType = event.eventType

        return when {
            msgtype == "m.image"    -> LastMessageType.Image
            msgtype == "m.video"    -> LastMessageType.Video
            msgtype == "m.audio"    -> LastMessageType.Audio
            msgtype == "m.file"     -> LastMessageType.File
            msgtype == "m.sticker"  -> LastMessageType.Sticker
            msgtype == "m.location" -> LastMessageType.Location
            evType  == "m.poll.start"   -> LastMessageType.Poll
            evType  == "m.call.invite"  -> LastMessageType.Call
            event.isEncrypted && event.body == null -> LastMessageType.Encrypted
            else -> LastMessageType.Text
        }
    }

    private fun formatBodyForPreview(event: LatestRoomEvent?, type: LastMessageType): String? {
        if (event == null) return null
        val body = event.body

        // Hide raw mxc:// URLs for media
        if (body != null && body.startsWith("mxc://")) {
            return when (type) {
                LastMessageType.Image -> "Photo"
                LastMessageType.Video -> "Video"
                LastMessageType.Audio -> "Audio"
                LastMessageType.File  -> "File"
                else -> null
            }
        }
        return body
    }

    //  Private Methods

    private fun observeRoomList() {
        roomListToken?.let { service.port.unobserveRoomList(it) }

        roomListToken = service.port.observeRoomList(object : MatrixPort.RoomListObserver {
            override fun onReset(items: List<RoomListEntry>) {
                val domainRooms = items.map(::mapRoomSummary)
                val uiItems     = items.map(::mapRoomEntryToUi)

                updateState {
                    copy(
                        rooms = domainRooms,
                        unread = items.associate { e -> e.roomId to e.notifications.toInt() },
                        favourites = items.filter { e -> e.isFavourite }.map { e -> e.roomId }.toSet(),
                        lowPriority = items.filter { e -> e.isLowPriority }.map { e -> e.roomId }.toSet(),
                        allItems = uiItems,
                        isLoading = false
                    )
                }
                recomputeGroupedRooms()
                initialized = true
            }

            override fun onUpdate(item: RoomListEntry) {
                updateState {
                    val updatedRooms = rooms.map { room ->
                        if (room.id == item.roomId) mapRoomSummary(item) else room
                    }

                    val updatedUiItems = allItems.map { existing ->
                        if (existing.roomId == item.roomId) mapRoomEntryToUi(item) else existing
                    }

                    val updatedUnread = unread.toMutableMap().apply {
                        put(item.roomId, item.notifications.toInt())
                    }

                    val updatedFavourites =
                        if (item.isFavourite) favourites + item.roomId else favourites - item.roomId
                    val updatedLowPriority =
                        if (item.isLowPriority) lowPriority + item.roomId else lowPriority - item.roomId

                    copy(
                        rooms = updatedRooms,
                        unread = updatedUnread,
                        favourites = updatedFavourites,
                        lowPriority = updatedLowPriority,
                        allItems = updatedUiItems
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
        val query = s.roomSearchQuery.trim()

        var list = s.allItems

        // Search filter
        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.roomId.contains(query, ignoreCase = true)
            }
        }

        // Unread filter
        if (s.unreadOnly) {
            list = list.filter { it.unreadCount > 0 }
        }

        val favourites  = list.filter { it.isFavourite }
        val lowPriority = list.filter { it.isLowPriority }
        val normal      = list.filter { !it.isFavourite && !it.isLowPriority }

        updateState {
            copy(
                favouriteItems = favourites,
                normalItems = normal,
                lowPriorityItems = lowPriority
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        roomListToken?.let { service.port.unobserveRoomList(it) }
        connToken?.let { service.stopConnectionObserver(it) }
    }
}