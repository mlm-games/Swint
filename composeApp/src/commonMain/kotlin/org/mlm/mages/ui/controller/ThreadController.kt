package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent

data class ThreadUi(
    val roomId: String,
    val rootEventId: String,
    val messages: List<MessageEvent> = emptyList(),
    val nextBatch: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class ThreadController(
    private val service: MatrixService,
    private val roomId: String,
    private val rootEventId: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(ThreadUi(roomId, rootEventId))
    val state: StateFlow<ThreadUi> = _state

    init { refresh() }

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val page = runCatching { service.port.threadReplies(roomId, rootEventId, from = null, limit = 60, forward = false) }.getOrElse {
                _state.value = _state.value.copy(isLoading = false, error = it.message ?: "Failed to load thread")
                return@launch
            }
            // Messages are chronological per Rust; just set
            _state.value = _state.value.copy(
                messages = page.messages,
                nextBatch = page.nextBatch,
                isLoading = false
            )
        }
    }

    fun loadMore() {
        val token = _state.value.nextBatch ?: return
        if (_state.value.isLoading) return
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val page = runCatching {
                service.port.threadReplies(roomId, rootEventId, from = token, limit = 60, forward = false)
            }.getOrNull()
            if (page != null) {
                // Prepend older messages (still chronological ordering overall)
                val merged = (page.messages + _state.value.messages).distinctBy { it.itemId }.sortedBy { it.timestamp }
                _state.value = _state.value.copy(messages = merged, nextBatch = page.nextBatch, isLoading = false)
            } else {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun react(ev: MessageEvent, emoji: String) {
        scope.launch {
            runCatching { service.port.react(_state.value.roomId, ev.eventId, emoji) }
            // HACK: Wait for auto refresh to show up?
        }
    }
}