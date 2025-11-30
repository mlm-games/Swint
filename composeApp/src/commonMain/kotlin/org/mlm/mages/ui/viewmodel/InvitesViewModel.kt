package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomProfile

data class InvitesUi(
    val invites: List<RoomProfile> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null
)

class InvitesViewModel(
    private val service: MatrixService
) : BaseViewModel<InvitesUi>(InvitesUi()) {

    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        launch(onError = {
            updateState { copy(busy = false, error = it.message ?: "Failed to load invites") }
        }) {
            updateState { copy(busy = true, error = null) }
            val list = runSafe { service.port.listInvited() } ?: emptyList()
            updateState { copy(invites = list, busy = false) }
        }
    }

    fun accept(roomId: String, name: String) {
        launch {
            val ok = runSafe { service.port.acceptInvite(roomId) } ?: false
            if (ok) {
                refresh()
                _events.send(Event.OpenRoom(roomId, name))
            } else {
                _events.send(Event.ShowError("Failed to accept invite"))
            }
        }
    }

    fun decline(roomId: String) {
        launch {
            runSafe { service.port.leaveRoom(roomId) }
            refresh()
        }
    }
}