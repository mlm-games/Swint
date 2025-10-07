package org.mlm.frair.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mlm.frair.MatrixService
import org.mlm.frair.RoomSummary
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.ui.MediaCacheUiState
import org.mlm.frair.ui.RoomsUiState

class MediaCacheController(
    private val service: MatrixService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(MediaCacheUiState())
    val state: StateFlow<MediaCacheUiState> = _state


    fun clearAll() {
        /*TODO*/
    }
    fun clearKeep(d: Long) {
        /*TODO*/
    }

    fun refresh() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
             // TODO
            _state.update { it.copy(isBusy = false) }
        }
    }
}