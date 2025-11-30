package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom

data class DiscoverUi(
    val query: String = "",
    val users: List<DirectoryUser> = emptyList(),
    val rooms: List<PublicRoom> = emptyList(),
    val nextBatch: String? = null,
    val isBusy: Boolean = false,
    val error: String? = null
)

class DiscoverViewModel(
    private val service: MatrixService
) : BaseViewModel<DiscoverUi>(DiscoverUi()) {

    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null

    fun setQuery(q: String) {
        updateState { copy(query = q) }
        searchJob?.cancel()
        searchJob = launch {
            delay(300)
            val term = q.trim()
            if (term.isBlank()) {
                updateState { copy(users = emptyList(), rooms = emptyList(), nextBatch = null, error = null, isBusy = false) }
                return@launch
            }
            updateState { copy(isBusy = true, error = null) }
            val users = runSafe { service.port.searchUsers(term, 20) } ?: emptyList()
            val page = runSafe { service.port.publicRooms(null, term, 50, null) }
            updateState {
                copy(
                    users = users,
                    rooms = page?.rooms ?: emptyList(),
                    nextBatch = page?.nextBatch,
                    isBusy = false
                )
            }
        }
    }

    fun openUser(u: DirectoryUser) {
        launch {
            val rid = runSafe { service.port.ensureDm(u.userId) }
            if (rid != null) {
                _events.send(Event.OpenRoom(rid, u.displayName ?: u.userId))
            } else {
                _events.send(Event.ShowError("Failed to start conversation"))
            }
        }
    }

    fun openRoom(room: PublicRoom) {
        launch {
            val rid = joinOrOpen(room.alias ?: room.roomId)
            if (rid != null) {
                _events.send(
                    Event.OpenRoom(
                        rid,
                        room.name ?: room.alias ?: room.roomId
                    )
                )
            } else {
                _events.send(Event.ShowError("Failed to join room"))
            }
        }
    }

    private suspend fun joinOrOpen(idOrAlias: String): String? {
        // Try resolve first
        val id = runSafe { service.port.resolveRoomId(idOrAlias) }
        if (id != null && id.startsWith("!")) return id
        // Join
        val ok = runSafe { service.port.joinByIdOrAlias(idOrAlias) } ?: false
        if (!ok) return null
        return runSafe { service.port.resolveRoomId(idOrAlias) } ?: idOrAlias
    }
}