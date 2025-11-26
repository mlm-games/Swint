package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.ui.base.BaseController

data class ThreadUi(
    val roomId: String,
    val rootEventId: String,
    val messages: List<MessageEvent> = emptyList(),
    val nextBatch: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val reactionChips: Map<String, List<ReactionChip>> = emptyMap(),

    val editingEvent: MessageEvent? = null,
    val editInput: String = ""
)

class ThreadController(
    private val service: MatrixService,
    private val roomId: String,
    private val rootEventId: String
) : BaseController<ThreadUi>(ThreadUi(roomId, rootEventId)) {

    init { refresh() }

    fun refresh() {
        launch(onError = { updateState { copy(isLoading = false, error = it.message ?: "Failed to load thread") } }) {
            updateState { copy(isLoading = true, error = null) }
            val page = service.port.threadReplies(roomId, rootEventId, from = null, limit = 60, forward = false)
            updateState {
                copy(
                    messages = page.messages,
                    nextBatch = page.nextBatch,
                    isLoading = false
                )
            }
        }
        refreshReactionsForAll()
    }

    fun loadMore() {
        val token = currentState.nextBatch ?: return
        if (currentState.isLoading) return

        launch {
            updateState { copy(isLoading = true) }
            val page = runSafe {
                service.port.threadReplies(roomId, rootEventId, from = token, limit = 60, forward = false)
            }
            if (page != null) {
                val merged = (page.messages + currentState.messages)
                    .distinctBy { it.itemId }
                    .sortedBy { it.timestamp }
                updateState { copy(messages = merged, nextBatch = page.nextBatch, isLoading = false) }
                refreshReactionsForAll()
            } else {
                updateState { copy(isLoading = false) }
            }
        }
    }

    fun react(ev: MessageEvent, emoji: String) {
        launch {
            runSafe { service.port.react(currentState.roomId, ev.eventId, emoji) }
            // Refresh to show updated reactions
            delay(500)
            refresh()
        }
    }

    fun startEdit(event: MessageEvent) {
        updateState { copy(editingEvent = event, editInput = event.body) }
    }

    fun cancelEdit() {
        updateState { copy(editingEvent = null, editInput = "") }
    }

    fun setEditInput(input: String) {
        updateState { copy(editInput = input) }
    }

    suspend fun confirmEdit(): Boolean {
        val editing = currentState.editingEvent ?: return false
        val newBody = currentState.editInput.trim()
        if (newBody.isBlank()) return false

        val ok = runSafe { service.edit(roomId, editing.eventId, newBody) } ?: false
        if (ok) {
            updateState { copy(editingEvent = null, editInput = "") }
            refresh()
        }
        return ok
    }

    suspend fun delete(event: MessageEvent): Boolean {
        val ok = runSafe { service.redact(roomId, event.eventId, null) } ?: false
        if (ok) refresh()
        return ok
    }

    suspend fun retry(event: MessageEvent): Boolean {
        if (event.body.isBlank()) return false

        val triedPrecise = event.txnId?.let { txn ->
            runSafe { service.retryByTxn(roomId, txn) } ?: false
        } ?: false

        if (triedPrecise) {
            refresh()
            return true
        }

        // Fallback: resend the message
        val ok = runSafe {
            service.port.sendThreadText(roomId, rootEventId, event.body.trim(), null)
        } ?: false

        if (ok) refresh()
        return ok
    }


    fun getReactions(eventId: String): List<ReactionChip> {
        return _state.value.reactionChips[eventId] ?: emptyList()
    }

    private fun refreshReactionsForAll() {
        _state.value.messages.forEach { ev ->
            scope.launch {
                refreshReactionsFor(ev.eventId)
            }
        }
    }

    private suspend fun refreshReactionsFor(eventId: String) {
        val chips = runCatching { service.port.reactions(roomId, eventId) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(
            reactionChips = _state.value.reactionChips.toMutableMap().apply {
                if (chips.isNotEmpty()) put(eventId, chips) else remove(eventId)
            }
        )
    }
}