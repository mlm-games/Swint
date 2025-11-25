package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomProfile

data class RoomInfoUiState(
    val profile: RoomProfile? = null,
    val members: List<MemberSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editedName: String = "",
    val editedTopic: String = "",
    val isSaving: Boolean = false
)

class RoomInfoController(
    private val service: MatrixService,
    private val roomId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow(RoomInfoUiState())
    val state: StateFlow<RoomInfoUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val profile = service.port.roomProfile(roomId)
            val members = service.port.listMembers(roomId)
            val sorted = members.sortedWith(
                compareByDescending<MemberSummary> { it.isMe }
                    .thenBy { it.displayName ?: it.userId }
            )
            _state.value = _state.value.copy(
                profile = profile,
                members = sorted,
                editedName = profile?.name ?: "",
                editedTopic = profile?.topic ?: "",
                isLoading = false,
                error = if (profile == null) "Failed to load room info" else null
            )
        }
    }

    fun updateName(name: String) {
        _state.value = _state.value.copy(editedName = name)
    }

    fun updateTopic(topic: String) {
        _state.value = _state.value.copy(editedTopic = topic)
    }

    suspend fun saveName(): Boolean {
        val name = _state.value.editedName.trim()
        if (name.isBlank()) return false

        _state.value = _state.value.copy(isSaving = true)
        val success = service.port.setRoomName(roomId, name)
        _state.value = _state.value.copy(isSaving = false)

        if (success) refresh()
        return success
    }

    suspend fun saveTopic(): Boolean {
        val topic = _state.value.editedTopic.trim()

        _state.value = _state.value.copy(isSaving = true)
        val success = service.port.setRoomTopic(roomId, topic)
        _state.value = _state.value.copy(isSaving = false)

        if (success) refresh()
        return success
    }

    suspend fun leave(): Boolean {
        return service.port.leaveRoom(roomId)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}