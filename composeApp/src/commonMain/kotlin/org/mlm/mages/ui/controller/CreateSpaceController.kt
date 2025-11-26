package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.ui.CreateSpaceUiState

class CreateSpaceController(
    private val service: MatrixService,
    private val onCreated: (spaceId: String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(CreateSpaceUiState())
    val state: StateFlow<CreateSpaceUiState> = _state

    fun setName(name: String) {
        _state.update { it.copy(name = name, error = null) }
    }

    fun setTopic(topic: String) {
        _state.update { it.copy(topic = topic) }
    }

    fun setPublic(isPublic: Boolean) {
        _state.update { it.copy(isPublic = isPublic) }
    }

    fun addInvitee(mxid: String) {
        val trimmed = mxid.trim()
        if (isValidMxid(trimmed) && trimmed !in _state.value.invitees) {
            _state.update { it.copy(invitees = it.invitees + trimmed) }
        }
    }

    fun removeInvitee(mxid: String) {
        _state.update { it.copy(invitees = it.invitees - mxid) }
    }

    fun create() {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(error = "Space name is required") }
            return
        }
        if (s.isCreating) return

        scope.launch {
            _state.update { it.copy(isCreating = true, error = null) }
            val spaceId = service.createSpace(
                name = s.name.trim(),
                topic = s.topic.ifBlank { null },
                isPublic = s.isPublic,
                invitees = s.invitees
            )
            if (spaceId != null) {
                _state.update { it.copy(isCreating = false) }
                withContext(Dispatchers.Main) { onCreated(spaceId) }
            } else {
                _state.update { it.copy(isCreating = false, error = "Failed to create space") }
            }
        }
    }

    fun reset() {
        _state.value = CreateSpaceUiState()
    }

    private fun isValidMxid(s: String) = s.startsWith("@") && ":" in s && s.length > 3
}