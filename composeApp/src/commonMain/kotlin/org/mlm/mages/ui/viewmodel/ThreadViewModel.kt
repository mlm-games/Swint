package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.ui.ThreadUi

class ThreadViewModel(
    private val service: MatrixService,
    private val roomId: String,
    private val rootEventId: String
) : BaseViewModel<ThreadUi>(ThreadUi(roomId, rootEventId)) {

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val myUserId: String? = service.port.whoami()

    init {
        refresh()
    }

    fun refresh() {
        launch(onError = {
            updateState { copy(isLoading = false, error = it.message ?: "Failed to load thread") }
        }) {
            updateState { copy(isLoading = true, error = null) }
            val page = service.port.threadReplies(roomId, rootEventId, from = null, limit = 60, forward = false)
            updateState {
                copy(
                    messages = page.messages,
                    nextBatch = page.nextBatch,
                    isLoading = false
                )
            }
            refreshReactionsForAll()
        }
    }

    fun loadMore() {
        val token = currentState.nextBatch ?: return
        if (currentState.isLoading) return

        launch(onError = {
            updateState { copy(isLoading = false) }
        }) {
            updateState { copy(isLoading = true) }
            val page = service.port.threadReplies(roomId, rootEventId, from = token, limit = 60, forward = false)
            val merged = (page.messages + currentState.messages)
                .distinctBy { it.itemId }
                .sortedBy { it.timestamp }
            updateState {
                copy(
                    messages = merged,
                    nextBatch = page.nextBatch,
                    isLoading = false
                )
            }
            refreshReactionsForAll()
        }
    }

    fun react(ev: MessageEvent, emoji: String) {
        launch {
            runSafe { service.port.react(roomId, ev.eventId, emoji) }
            delay(400)
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
        } else {
            _events.send(Event.ShowError("Failed to edit message"))
        }
        return ok
    }

    suspend fun delete(event: MessageEvent): Boolean {
        val ok = runSafe { service.redact(roomId, event.eventId, null) } ?: false
        if (ok) {
            refresh()
        } else {
            _events.send(Event.ShowError("Failed to delete message"))
        }
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

        val ok = runSafe {
            service.port.sendThreadText(roomId, rootEventId, event.body.trim(), null)
        } ?: false

        if (ok) {
            refresh()
        } else {
            _events.send(Event.ShowError("Failed to retry message"))
        }
        return ok
    }

    suspend fun sendMessage(text: String, replyToId: String?): Boolean {
        val body = text.trim()
        if (body.isBlank()) return false

        val ok = runSafe {
            service.port.sendThreadText(roomId, rootEventId, body, replyToId)
        } ?: false

        if (ok) {
            refresh()
        } else {
            _events.send(Event.ShowError("Failed to send message"))
        }
        return ok
    }

    fun getReactions(eventId: String): List<ReactionChip> {
        return currentState.reactionChips[eventId] ?: emptyList()
    }

    private fun refreshReactionsForAll() {
        currentState.messages.forEach { ev ->
            launch { refreshReactionsFor(ev.eventId) }
        }
    }

    private suspend fun refreshReactionsFor(eventId: String) {
        if (eventId.isBlank()) return
        val chips = runSafe { service.port.reactions(roomId, eventId) } ?: emptyList()
        updateState {
            copy(
                reactionChips = reactionChips.toMutableMap().apply {
                    if (chips.isNotEmpty()) put(eventId, chips) else remove(eventId)
                }
            )
        }
    }
}