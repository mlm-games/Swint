package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.ui.RoomUiState
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.components.SendIndicator

class RoomController(
    private val service: MatrixService,
    roomId: String,
    roomName: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(RoomUiState(roomId = roomId, roomName = roomName))
    val state: StateFlow<RoomUiState> = _state

    private var typingToken: ULong? = null
    private var uploadJob: Job? = null
    private var typingJob: Job? = null


    init {
        service.startSupervisedSync(object : org.mlm.mages.matrix.MatrixPort.SyncObserver {
            override fun onState(status: org.mlm.mages.matrix.MatrixPort.SyncStatus) { /* no-op */ }
        })
        loadInitial()
        observeTimeline()
        observeTyping()
        observeOutbox()
        updateMyUserId()
    }

    private fun updateMyUserId() {
        _state.update { it.copy(myUserId = service.port.whoami()) }
    }

    private fun loadInitial() {
        scope.launch {
            val recent = runCatching { service.loadRecent(_state.value.roomId, 60) }
                .getOrDefault(emptyList())
            _state.update { it.copy(events = recent.sortedBy { e -> e.timestamp }) }

            // If short, nudge one page, then briefly wait for diffs
            if (_state.value.events.size < 40) {
                val before = _state.value.events.size
                val hitStart = service.paginateBack(_state.value.roomId, 50)
                _state.update { it.copy(hitStart = hitStart || it.hitStart) }
                withTimeoutOrNull(1200) {
                    state.first { it.events.size > before || it.hitStart }
                } ?: run {
                    // fallback snapshot if diffs didn’t arrive in time
                    val snap = service.loadRecent(_state.value.roomId, before + 50)
                    _state.update {
                        it.copy(
                            events = snap
                                .distinctBy { e -> e.itemId }
                                .sortedBy { e -> e.timestamp }
                        )
                    }
                }
            }
        }
    }

    private fun observeTimeline() {
        scope.launch {
            service.timelineDiffs(_state.value.roomId).collect { diff ->
                when (diff) {
                    is TimelineDiff.Reset -> _state.update {
                        it.copy(
                            events = diff.items
                                .distinctBy { e -> e.itemId }
                                .sortedBy { e -> e.timestamp }
                        )
                    }
                    is TimelineDiff.Clear -> _state.update { it.copy(events = emptyList()) }
                    is TimelineDiff.Insert -> _state.update { s ->
                        val next = (s.events + diff.item).distinctBy { it.itemId }.sortedBy { e -> e.timestamp }
                        s.copy(events = next)
                    }
                    is TimelineDiff.Update -> _state.update { s ->
                        val idx = s.events.indexOfFirst { it.itemId == diff.item.itemId }
                        val next = if (idx >= 0) s.events.toMutableList().apply { set(idx, diff.item) } else (s.events + diff.item)
                        s.copy(events = next.sortedBy { e -> e.timestamp })
                    }
                    is TimelineDiff.Remove -> _state.update { s ->
                        s.copy(events = s.events.filterNot { it.itemId == diff.itemId })
                    }
                }
            }
        }
    }

    private fun observeOutbox() {
        scope.launch {
            val rid = _state.value.roomId
            val byTxn = LinkedHashMap<String, SendIndicator>()
            service.observeSends().collectLatest { upd ->
                if (upd.roomId != rid) return@collectLatest
                val indicator = SendIndicator(
                    txnId = upd.txnId,
                    attempts = upd.attempts,
                    state = upd.state,
                    error = upd.error
                )
                byTxn[upd.txnId] = indicator
                // Drop Sent items from the visible chip list to keep it small
                val visible = byTxn.values.filter { it.state != SendState.Sent }
                _state.update {
                    it.copy(
                        outbox = visible,
                        pendingSendCount = visible.size
                    )
                }
            }
        }
    }

    private fun observeTyping() {
        typingToken?.let { service.stopTypingObserver(it) }
        typingToken = service.observeTyping(_state.value.roomId) { names ->
            _state.update { it.copy(typingNames = names) }
        }
    }

    fun setInput(v: String) {
        _state.update { it.copy(input = v) }
        typingJob?.cancel()
        typingJob = scope.launch {
            if (v.isBlank()) {
                service.port.setTyping(_state.value.roomId, false)
            } else {
                service.port.setTyping(_state.value.roomId, true)
                delay(4000)
                service.port.setTyping(_state.value.roomId, false)
            }
        }
    }

    fun send() {
        val s = _state.value
        if (s.input.isBlank()) return
        scope.launch {
            val text = s.input.trim()
            val replyTo = s.replyingTo
            if (replyTo != null) {
                val ok = service.reply(s.roomId, replyTo.eventId, text)
                if (ok) _state.update { it.copy(input = "", replyingTo = null) }
                else _state.update { it.copy(error = "Reply failed") }
            } else {
                runCatching { service.enqueueText(s.roomId, text, null) }
                _state.update { it.copy(input = "") }
            }
        }
    }

    fun sendAttachment(data: AttachmentData) {
        // prevent overlapping uploads
        if (_state.value.isUploadingAttachment) return
        _state.update {
            it.copy(
                currentAttachment = data,
                isUploadingAttachment = true,
                attachmentProgress = 0f
            )
        }
        val roomId = _state.value.roomId
        uploadJob = scope.launch {
            val ok = service.sendAttachmentFromPath(
                roomId = roomId,
                path = data.path,
                mime = data.mimeType,
                filename = data.fileName
            ) { sent, total ->
                val denom = (total ?: data.sizeBytes).coerceAtLeast(1L).toFloat()
                val p = (sent.toFloat() / denom).coerceIn(0f, 1f)
                _state.update { it.copy(attachmentProgress = p) }
            }
            _state.update {
                it.copy(
                    isUploadingAttachment = false,
                    attachmentProgress = 0f,
                    currentAttachment = null,
                    error = if (!ok) "Attachment upload failed" else it.error
                )
            }
        }
    }

    fun cancelAttachmentUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _state.update {
            it.copy(
                isUploadingAttachment = false,
                attachmentProgress = 0f,
                currentAttachment = null
            )
        }
    }

    fun startReply(ev: MessageEvent) { _state.update { it.copy(replyingTo = ev) } }
    fun cancelReply() { _state.update { it.copy(replyingTo = null) } }
    fun startEdit(ev: MessageEvent) { _state.update { it.copy(editing = ev, input = ev.body) } }
    fun cancelEdit() { _state.update { it.copy(editing = null, input = "") } }
    fun confirmEdit() {
        val s = _state.value
        val target = s.editing ?: return
        scope.launch {
            val ok = service.edit(s.roomId, target.eventId, s.input.trim())
            if (ok) _state.update { it.copy(editing = null, input = "") }
            else _state.update { it.copy(error = "Edit failed") }
        }
    }

    fun react(ev: MessageEvent, emoji: String) {
        scope.launch { service.react(_state.value.roomId, ev.eventId, emoji) }
    }

    fun paginateBack() {
        val s = _state.value
        if (s.isPaginatingBack || s.hitStart) return
        scope.launch {
            _state.update { it.copy(isPaginatingBack = true) }
            try {
                val before = _state.value.events.size
                val hitStart = service.paginateBack(s.roomId, 50)
                _state.update { it.copy(hitStart = hitStart || it.hitStart) }

                val arrived = withTimeoutOrNull(1500) {
                    state.first { it.events.size > before || it.hitStart }
                    true
                } ?: false

                // Fallback, take a snapshot if diffs didn’t land in time
                if (!arrived) {
                    val snap = service.loadRecent(
                        s.roomId,
                        limit = (_state.value.events.size + 50).coerceAtLeast(50)
                    )
                    _state.update {
                        it.copy(
                            events = snap
                                .distinctBy { e -> e.itemId }
                                .sortedBy { e -> e.timestamp }
                        )
                    }
                }
            } finally {
                _state.update { it.copy(isPaginatingBack = false) }
            }
        }
    }

    fun markReadHere(ev: MessageEvent) {
        scope.launch { service.markReadAt(ev.roomId, ev.eventId) }
    }

    fun delete(ev: MessageEvent) {
        scope.launch { service.redact(_state.value.roomId, ev.eventId, null) }
    }
}