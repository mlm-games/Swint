package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class DiscoverController(private val service: MatrixService) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(DiscoverUi())
    val state: StateFlow<DiscoverUi> = _state
    private var job: Job? = null

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        job?.cancel()
        job = scope.launch {
            delay(300)
            val term = q.trim()
            if (term.isBlank()) {
                _state.value = _state.value.copy(users = emptyList(), rooms = emptyList(), nextBatch = null, error = null)
                return@launch
            }
            _state.value = _state.value.copy(isBusy = true, error = null)
            val users = runCatching { service.port.searchUsers(term, 20) }.getOrElse { emptyList() }
            val page = runCatching { service.port.publicRooms(null, term, 50, null) }.getOrNull()
            _state.value = _state.value.copy(
                users = users,
                rooms = page?.rooms ?: emptyList(),
                nextBatch = page?.nextBatch,
                isBusy = false
            )
        }
    }

    suspend fun joinOrOpen(roomIdOrAlias: String): String? {
        val id = runCatching { service.port.resolveRoomId(roomIdOrAlias) }.getOrNull()
        if (id != null && id.startsWith("!")) return id
        val ok = runCatching { service.port.joinByIdOrAlias(roomIdOrAlias) }.getOrDefault(false)
        if (!ok) return null
        return runCatching { service.port.resolveRoomId(roomIdOrAlias) }.getOrNull() ?: roomIdOrAlias
    }

    suspend fun ensureDm(mxid: String): String? =
        runCatching { service.port.ensureDm(mxid) }.getOrNull()
}