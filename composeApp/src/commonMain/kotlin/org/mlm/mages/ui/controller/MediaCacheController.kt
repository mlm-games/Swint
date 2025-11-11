package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.ui.MediaCacheUiState
import org.mlm.mages.ui.RoomsUiState

class MediaCacheController(
    private val service: MatrixService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(MediaCacheUiState())
    val state: StateFlow<MediaCacheUiState> = _state


    fun refresh() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { service.mediaCacheStats() }
                .onSuccess { (bytes, filesCount) ->
                    _state.update { it.copy(bytes = bytes, files = mapOf("total" to filesCount), isBusy = false) }
                }
                .onFailure { t -> _state.update { it.copy(isBusy = false, error = t.message ?: "Failed to read cache") } }
        }
    }

    fun clearAll() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { service.mediaCacheEvict(0L) }
                .onSuccess { refresh() }
                .onFailure { t -> _state.update { it.copy(isBusy = false, error = t.message ?: "Clear failed") } }
        }
    }

    fun clearKeep(d: Long) {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { service.mediaCacheEvict(d) }
                .onSuccess { refresh() }
                .onFailure { t -> _state.update { it.copy(isBusy = false, error = t.message ?: "Evict failed") } }
        }
    }
}