package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.SpaceSettingsUiState

class SpaceSettingsController(
    private val service: MatrixService,
    private val spaceId: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(SpaceSettingsUiState())
    val state: StateFlow<SpaceSettingsUiState> = _state

    init {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
    }

    private fun loadSpaceInfo() {
        scope.launch {
            val spaces = service.mySpaces()
            val space = spaces.find { it.roomId == spaceId }
            _state.update { it.copy(space = space) }
        }
    }

    private fun loadChildren() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val page = service.spaceHierarchy(spaceId, from = null, limit = 100, maxDepth = 1, suggestedOnly = false)
            if (page != null) {
                // Filter out the space itself from children
                val children = page.children.filter { it.roomId != spaceId }
                _state.update { it.copy(children = children, isLoading = false) }
            } else {
                _state.update { it.copy(isLoading = false, error = "Failed to load children") }
            }
        }
    }

    private fun loadAvailableRooms() {
        scope.launch {
            val rooms = service.listRooms()
            // Filter out rooms that are already children and the space itself
            val childIds = _state.value.children.map { it.roomId }.toSet() + spaceId
            val available = rooms.filter { it.id !in childIds }
            _state.update { it.copy(availableRooms = available) }
        }
    }

    fun addChild(roomId: String, suggested: Boolean = false) {
        scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val ok = service.spaceAddChild(spaceId, roomId, order = null, suggested = suggested)
            if (ok) {
                loadChildren()
                loadAvailableRooms()
            } else {
                _state.update { it.copy(isSaving = false, error = "Failed to add room to space") }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    fun removeChild(childRoomId: String) {
        scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val ok = service.spaceRemoveChild(spaceId, childRoomId)
            if (ok) {
                loadChildren()
                loadAvailableRooms()
            } else {
                _state.update { it.copy(isSaving = false, error = "Failed to remove room from space") }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    fun inviteUser(userId: String) {
        scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val ok = service.spaceInviteUser(spaceId, userId)
            if (!ok) {
                _state.update { it.copy(error = "Failed to invite user") }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    fun refresh() {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}