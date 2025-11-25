package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomProfile

data class InvitesUi(
    val invites: List<RoomProfile> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null
)

class InvitesController(private val port: MatrixPort) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(InvitesUi())
    val state: StateFlow<InvitesUi> = _state

    init { refresh() }

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            val list = runCatching { port.listInvited() }.getOrElse {
                _state.value = _state.value.copy(busy = false, error = it.message ?: "Failed to load invites")
                return@launch
            }
            _state.value = InvitesUi(invites = list, busy = false)
        }
    }

    suspend fun accept(roomId: String): Boolean {
        val ok = runCatching { port.acceptInvite(roomId) }.getOrDefault(false)
        if (ok) refresh()
        return ok
    }

    fun decline(roomId: String) {
        scope.launch {
            runCatching { port.leaveRoom(roomId) } // decline = leave
            refresh()
        }
    }
}