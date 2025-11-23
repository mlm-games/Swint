package org.mlm.mages.ui.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReceiptsObserver
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.ui.RoomUiState
import org.mlm.mages.ui.components.AttachmentData

class RoomController(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>,
    roomId: String,
    roomName: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(RoomUiState(roomId = roomId, roomName = roomName))
    val state: StateFlow<RoomUiState> = _state

    private var typingToken: ULong? = null
    private var receiptsToken: ULong? = null
    private var ownReceiptToken: ULong? = null
    private var dmPeer: String? = null
    private var uploadJob: Job? = null
    private var typingJob: Job? = null


    init {
        loadInitial()
        observeTimeline()
        observeTyping()
        observeOwnReceipt()
        observeReceipts()
        updateMyUserId()
        scope.launch {
            dmPeer = runCatching { service.port.dmPeerUserId(_state.value.roomId) }.getOrNull()
        }
    }

    private fun updateMyUserId() {
        _state.update { it.copy(myUserId = service.port.whoami()) }
        recomputeDerived()
    }

    private fun loadInitial() {
        scope.launch {
            val recent = runCatching { service.loadRecent(_state.value.roomId, 60) }
                .getOrDefault(emptyList())
            _state.update { it.copy(events = recent.sortedBy { e -> e.timestamp }) }

            if (recent.size < 40) {
                val hitStart = service.paginateBack(_state.value.roomId, 50)
                _state.update { it.copy(hitStart = hitStart) }
            }
        }
    }

    private fun observeOwnReceipt() {
        // Set initial divider from SDK
        scope.launch {
            runCatching { service.port.ownLastRead(_state.value.roomId) }
                .onSuccess { (_, ts) -> _state.update { it.copy(lastReadTs = ts) } }
        }
        // Subscribe to changes in our own read receipt
        ownReceiptToken?.let { service.port.stopReceiptsObserver(it) }
        ownReceiptToken = service.port.observeOwnReceipt(_state.value.roomId, object : ReceiptsObserver {
            override fun onChanged() {
                scope.launch {
                    runCatching { service.port.ownLastRead(_state.value.roomId) }
                        .onSuccess { (_, ts) -> _state.update { it.copy(lastReadTs = ts) } }
                }
            }
        })
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
                recomputeDerived()
                prefetchThumbnails(diff)
            }
        }
    }

    private fun observeTyping() {
        typingToken?.let { service.stopTypingObserver(it) }
        typingToken = service.observeTyping(_state.value.roomId) { names ->
            _state.update { it.copy(typingNames = names) }
        }
    }

    private fun observeReceipts() {
        receiptsToken?.let { service.port.stopReceiptsObserver(it) }
        receiptsToken = service.port.observeReceipts(_state.value.roomId, object :
            ReceiptsObserver {
            override fun onChanged() {
                recomputeReadStatuses()
            }
        })
    }

    private fun recomputeDerived() {
        val s = _state.value
        val me = s.myUserId
        if (me == null || s.events.isEmpty()) {
            _state.update { it.copy(isDm = false, lastIncomingFromOthersTs = null, lastOutgoingRead = false) }
            return
        }
        val otherSenders = s.events.asSequence().map { it.sender }.filter { it != me }.toSet()
        val isDm = otherSenders.size == 1
        val lastIncoming = s.events.asSequence().filter { it.sender != me }.maxOfOrNull { it.timestamp }
        _state.update { it.copy(isDm = isDm, lastIncomingFromOthersTs = lastIncoming) }
        if (isDm) recomputeReadStatuses()
    }

    private fun recomputeReadStatuses() {
        val s = _state.value
        if (!s.isDm) return
        val me = s.myUserId ?: return
        val lastOutgoing = s.events.lastOrNull { it.sender == me } ?: run {
            _state.update { it.copy(lastOutgoingRead = false) }; return
        }
        val peer = dmPeer ?: return
        scope.launch {
            val read = runCatching {
                service.port.isEventReadBy(s.roomId, lastOutgoing.eventId, peer)
            }.getOrDefault(false)
            _state.update { it.copy(lastOutgoingRead = read) }
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
                val ok = service.sendMessage(s.roomId, text)
                if (ok) _state.update { it.copy(input = "") }
                else _state.update { it.copy(error = "Send failed") }
            }
        }
        recomputeReadStatuses()
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
        scope.launch {
            runCatching { service.react(_state.value.roomId, ev.eventId, emoji) }

            _state.update { st ->
                val current = st.reactions[ev.eventId].orEmpty().toMutableSet()
                if (emoji in current) current.remove(emoji) else current.add(emoji)
                st.copy(reactions = st.reactions.toMutableMap().apply { put(ev.eventId, current) })
            }
        }
    }

    fun retry(ev: MessageEvent) {
        if (ev.body.isBlank()) return
        scope.launch {
            val rid = _state.value.roomId
            val triedPrecise = ev.txnId?.let { txn ->
                service.retryByTxn(rid, txn)
            } ?: false

            val ok = if (triedPrecise) true else service.sendMessage(rid, ev.body.trim())
            if (!ok) _state.update { it.copy(error = "Retry failed") }
        }

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
        scope.launch {
            service.markReadAt(ev.roomId, ev.eventId)
        }
    }

    fun delete(ev: MessageEvent) {
        scope.launch { service.redact(_state.value.roomId, ev.eventId, null) }
    }

    private fun prefetchThumbnails(diff: TimelineDiff<MessageEvent>) {
        val roomId = _state.value.roomId
        fun want(ev: MessageEvent): Boolean {
            val a = ev.attachment ?: return false
            return when (a.kind) {
                AttachmentKind.Image, AttachmentKind.Video -> true
                AttachmentKind.File -> a.thumbnailMxcUri != null // only if server provides
            }
        }
        val events = when (diff) {
            is TimelineDiff.Reset -> diff.items
            is TimelineDiff.Insert -> listOf(diff.item)
            is TimelineDiff.Update -> listOf(diff.item)
            else -> emptyList()
        }.filter(::want)

        if (events.isEmpty()) return
        events.forEach { ev ->
            val a = ev.attachment ?: return@forEach
            // pick the best MXC for thumb
            val mxc = a.thumbnailMxcUri ?: a.mxcUri
            // Skip if we already have one
            if (_state.value.thumbByEvent.containsKey(ev.eventId)) return@forEach
            scope.launch {
                val res = service.thumbnailToCache(mxc, 320, 320, true)
                res.onSuccess { path ->
                    _state.update { st ->
                        st.copy(thumbByEvent = st.thumbByEvent + (ev.eventId to path))
                    }
                }
            }
        }
    }

    fun openAttachment(ev: MessageEvent, onOpen: (String, String?) -> Unit) {
        val a = ev.attachment ?: return
        scope.launch {
            val nameHint = run {
                val ext = a.mime?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                val base = ev.eventId.ifBlank { "file" }
                if (ext != null) "$base.$ext" else base
            }
            val res = service.downloadToCacheFile(a.mxcUri, nameHint)
            res.onSuccess { path -> onOpen(path, a.mime) }
                .onFailure { t ->
                    _state.update { it.copy(error = t.message ?: "Download failed") }
                }
        }
    }
}