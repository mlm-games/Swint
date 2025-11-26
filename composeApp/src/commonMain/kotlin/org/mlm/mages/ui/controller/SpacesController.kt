package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.SpacesUiState

class SpacesController(
    private val service: MatrixService,
    private val onOpenRoom: (roomId: String, name: String) -> Unit,
    private val onOpenSpace: (SpaceInfo) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(SpacesUiState())
    val state: StateFlow<SpacesUiState> = _state

    init {
        loadSpaces()
    }

    fun loadSpaces() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val spaces = service.mySpaces()
            _state.update { it.copy(spaces = spaces, isLoading = false) }
        }
    }


    fun openSpace(space: SpaceInfo) {
        onOpenSpace(space)
    }

    fun setSelectedSpace(space: SpaceInfo) {
        _state.update { it.copy(selectedSpace = space, hierarchy = emptyList(), hierarchyNextBatch = null) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedSpace = null, hierarchy = emptyList(), hierarchyNextBatch = null) }
    }

    fun loadHierarchy(spaceId: String, from: String? = null) {
        scope.launch {
            _state.update { it.copy(isLoadingHierarchy = true, error = null) }

            // If we don't have the space info yet, load it
            if (_state.value.selectedSpace == null || _state.value.selectedSpace?.roomId != spaceId) {
                val spaces = service.mySpaces()
                val space = spaces.find { it.roomId == spaceId }
                if (space != null) {
                    _state.update { it.copy(selectedSpace = space) }
                }
            }

            val page = service.spaceHierarchy(spaceId, from, limit = 50, maxDepth = 2, suggestedOnly = false)
            if (page != null) {
                _state.update { st ->
                    val newChildren = if (from == null) page.children else st.hierarchy + page.children
                    st.copy(
                        hierarchy = newChildren.distinctBy { it.roomId },
                        hierarchyNextBatch = page.nextBatch,
                        isLoadingHierarchy = false
                    )
                }
            } else {
                _state.update { it.copy(isLoadingHierarchy = false, error = "Failed to load space hierarchy") }
            }
        }
    }

    fun loadMoreHierarchy() {
        val space = _state.value.selectedSpace ?: return
        val nextBatch = _state.value.hierarchyNextBatch ?: return
        if (_state.value.isLoadingHierarchy) return
        loadHierarchy(space.roomId, nextBatch)
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun openChild(child: SpaceChildInfo) {
        if (child.isSpace) {
            // Find or create SpaceInfo for the subspace
            val spaceInfo = SpaceInfo(
                roomId = child.roomId,
                name = child.name ?: child.roomId,
                topic = child.topic,
                memberCount = child.memberCount,
                isEncrypted = false, // TODO: add to hierarchy
                isPublic = child.worldReadable
            )
            onOpenSpace(spaceInfo)
        } else {
            onOpenRoom(child.roomId, child.name ?: child.alias ?: child.roomId)
        }
    }

    fun refresh() {
        loadSpaces()
        _state.value.selectedSpace?.let { loadHierarchy(it.roomId) }
    }
}