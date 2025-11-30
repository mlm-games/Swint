package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.SpacesUiState

class SpacesViewModel(
    private val service: MatrixService
) : BaseViewModel<SpacesUiState>(SpacesUiState(isLoading = true)) {

    // One-time events
    sealed class Event {
        data class OpenSpace(val spaceId: String, val name: String) : Event()
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSpaces()
    }

    //  Public Actions 

    fun loadSpaces() {
        launch(
            onError = { t ->
                updateState { copy(isLoading = false, error = t.message ?: "Failed to load spaces") }
            }
        ) {
            updateState { copy(isLoading = true, error = null) }
            val spaces = service.mySpaces()
            updateState {
                copy(
                    spaces = spaces,
                    isLoading = false
                )
            }
            recomputeFilteredSpaces()
        }
    }

    fun setSearchQuery(query: String) {
        updateState { copy(searchQuery = query) }
        recomputeFilteredSpaces()
    }

    fun openSpace(space: SpaceInfo) {
        launch {
            _events.send(Event.OpenSpace(space.roomId, space.name))
        }
    }

    fun refresh() {
        loadSpaces()
    }

    //  Create Space 

    fun showCreateSpace() {
        updateState {
            copy(
                showCreateSpace = true,
                createName = "",
                createTopic = "",
                createIsPublic = false,
                createInvitees = emptyList()
            )
        }
    }

    fun hideCreateSpace() {
        updateState { copy(showCreateSpace = false) }
    }

    fun setCreateName(name: String) {
        updateState { copy(createName = name) }
    }

    fun setCreateTopic(topic: String) {
        updateState { copy(createTopic = topic) }
    }

    fun setCreateIsPublic(isPublic: Boolean) {
        updateState { copy(createIsPublic = isPublic) }
    }

    fun addCreateInvitee(mxid: String) {
        val trimmed = mxid.trim()
        if (isValidMxid(trimmed) && trimmed !in currentState.createInvitees) {
            updateState { copy(createInvitees = createInvitees + trimmed) }
        }
    }

    fun removeCreateInvitee(mxid: String) {
        updateState { copy(createInvitees = createInvitees - mxid) }
    }

    fun createSpace() {
        val s = currentState
        if (s.createName.isBlank()) {
            launch { _events.send(Event.ShowError("Space name is required")) }
            return
        }
        if (s.isCreating) return

        launch(
            onError = { t ->
                updateState { copy(isCreating = false) }
                launch { _events.send(Event.ShowError(t.message ?: "Failed to create space")) }
            }
        ) {
             updateState { copy(isCreating = true) }

            val spaceId = service.createSpace(
                name = s.createName.trim(),
                topic = s.createTopic.ifBlank { null },
                isPublic = s.createIsPublic,
                invitees = s.createInvitees
            )

            if (spaceId != null) {
                updateState { copy(isCreating = false, showCreateSpace = false) }
                loadSpaces()
                _events.send(Event.ShowSuccess("Space created"))
                _events.send(Event.OpenSpace(spaceId, s.createName.trim()))
            } else {
                updateState { copy(isCreating = false) }
                _events.send(Event.ShowError("Failed to create space"))
            }
        }
    }

    //  Private Methods 

    private fun recomputeFilteredSpaces() {
        val s = currentState
        val query = s.searchQuery.trim()

        val filtered = if (query.isBlank()) {
            s.spaces
        } else {
            s.spaces.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.topic?.contains(query, ignoreCase = true) == true ||
                        it.roomId.contains(query, ignoreCase = true)
            }
        }

        updateState { copy(filteredSpaces = filtered) }
    }

    private fun isValidMxid(s: String) = s.startsWith("@") && ":" in s && s.length > 3
}