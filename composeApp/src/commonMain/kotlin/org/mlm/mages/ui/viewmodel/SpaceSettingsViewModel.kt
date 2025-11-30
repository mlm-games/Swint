package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.ui.SpaceSettingsUiState

class SpaceSettingsViewModel(
    private val service: MatrixService,
    spaceId: String
) : BaseViewModel<SpaceSettingsUiState>(
    SpaceSettingsUiState(spaceId = spaceId, isLoading = true)
) {

    // One-time events
    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
    }

    //  Public Actions 

    fun refresh() {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
    }

    // Add room dialog
    fun showAddRoomDialog() {
        updateState { copy(showAddRoom = true) }
    }

    fun hideAddRoomDialog() {
        updateState { copy(showAddRoom = false) }
    }

    fun addChild(roomId: String, suggested: Boolean = false) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch {_events.send(Event.ShowError(t.message ?: "Failed to add room"))}
            }
        ) {
            updateState { copy(isSaving = true) }

            val ok = service.spaceAddChild(
                spaceId = currentState.spaceId,
                childRoomId = roomId,
                order = null,
                suggested = suggested
            )

            if (ok) {
                updateState { copy(isSaving = false, showAddRoom = false) }
                loadChildren()
                loadAvailableRooms()
                _events.send(Event.ShowSuccess("Room added to space"))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError("Failed to add room"))
            }
        }
    }

    fun removeChild(childRoomId: String) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: "Failed to remove room")) }
            }
        ) {
            updateState { copy(isSaving = true) }

            val ok = service.spaceRemoveChild(currentState.spaceId, childRoomId)

            if (ok) {
                updateState { copy(isSaving = false) }
                loadChildren()
                loadAvailableRooms()
                _events.send(Event.ShowSuccess("Room removed from space"))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError("Failed to remove room"))
            }
        }
    }

    // Invite user dialog
    fun showInviteDialog() {
        updateState { copy(showInviteUser = true, inviteUserId = "") }
    }

    fun hideInviteDialog() {
        updateState { copy(showInviteUser = false, inviteUserId = "") }
    }

    fun setInviteUserId(userId: String) {
        updateState { copy(inviteUserId = userId) }
    }

    fun inviteUser() {
        val userId = currentState.inviteUserId.trim()
        if (userId.isBlank() || !userId.startsWith("@") || ":" !in userId) {
            launch { _events.send(Event.ShowError("Invalid user ID")) }
            return
        }

        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: "Failed to invite user")) }
            }
        ) {
            updateState { copy(isSaving = true) }

            val ok = service.spaceInviteUser(currentState.spaceId, userId)

            if (ok) {
                updateState { copy(isSaving = false, showInviteUser = false, inviteUserId = "") }
                _events.send(Event.ShowSuccess("Invitation sent"))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError("Failed to invite user"))
            }
        }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }

    //  Private Methods 

    private fun loadSpaceInfo() {
        launch {
            val spaces = runSafe { service.mySpaces() } ?: emptyList()
            val space = spaces.find { it.roomId == currentState.spaceId }
            updateState { copy(space = space) }
        }
    }

    private fun loadChildren() {
        launch(
            onError = { t ->
                updateState { copy(isLoading = false, error = t.message ?: "Failed to load children") }
            }
        ) {
            updateState { copy(isLoading = true, error = null) }

            val page = service.spaceHierarchy(
                spaceId = currentState.spaceId,
                from = null,
                limit = 100,
                maxDepth = 1,
                suggestedOnly = false
            )

            if (page != null) {
                // Filter out the space itself
                val children = page.children.filter { it.roomId != currentState.spaceId }
                updateState { copy(children = children, isLoading = false) }
            } else {
                updateState { copy(isLoading = false, error = "Failed to load children") }
            }
        }
    }

    private fun loadAvailableRooms() {
        launch {
            val rooms = runSafe { service.listRooms() } ?: emptyList()
            // Filter out rooms that are already children and the space itself
            val childIds = currentState.children.map { it.roomId }.toSet() + currentState.spaceId
            val available = rooms.filter { it.id !in childIds }
            updateState { copy(availableRooms = available) }
        }
    }
}