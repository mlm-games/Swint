package org.mlm.mages.ui.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReceiptsObserver
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.platform.Notifier
import org.mlm.mages.ui.RoomUiState
import org.mlm.mages.ui.components.AttachmentData

class RoomController(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>,
    roomId: String,
    roomName: String
) {
    // Most work here touches IO (SDK, storage). Use IO dispatcher to avoid starving Default/Main.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    /**
     * Keep all non-message events (membership, state changes, etc.)
     */
    private fun filterOutThreadReplies(events: List<MessageEvent>): List<MessageEvent> {
        return events.filter { it.threadRootEventId == null }
    }

    /**
     * Events are included if they are not thread replies.
     */
    private fun shouldIncludeInMainTimeline(event: MessageEvent): Boolean {
        return event.threadRootEventId == null
    }

    private fun loadInitial() {
        scope.launch {
            // Keep the initial fetch small — show something fast, then catch up in background
            val recent = runCatching { service.loadRecent(_state.value.roomId, 40) }
                .getOrDefault(emptyList())
            val filtered = filterOutThreadReplies(recent)
            _state.update { it.copy(events = filtered.sortedBy { e -> e.timestamp }) }

            // Prefetch thumbnails just for visible, recent items
            prefetchThumbnailsForEvents(filtered.takeLast(8))

            // Don't block the first render — fetch deeper history on a background coroutine
            if (filtered.size < 40) {
                launch {
                    val hitStart = service.paginateBack(_state.value.roomId, 50)
                    _state.update { it.copy(hitStart = it.hitStart || hitStart) }
                }
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

        Notifier.setCurrentRoom(_state.value.roomId)

        scope.launch {
            // Buffer diffs so downstream work (reactions, thumbnails, thread counts) never blocks the source
            service.timelineDiffs(_state.value.roomId)
                .buffer(capacity = Channel.BUFFERED)
                .collect { diff ->
                    when (diff) {
                        is TimelineDiff.Reset -> {
                            val filtered = filterOutThreadReplies(diff.items)
                            _state.update {
                                it.copy(
                                    events = filtered.distinctBy { e -> e.itemId }.sortedBy { e -> e.timestamp }
                                )
                            }
                            // Limit secondary work on reset to most recent items
                            filtered.takeLast(10).forEach { ev -> launch { refreshReactionsFor(ev.eventId) } }
                            prefetchThumbnailsForEvents(filtered.takeLast(8))
                        }
                        is TimelineDiff.Clear -> _state.update { it.copy(events = emptyList()) }
                        is TimelineDiff.Insert -> {
                            if (shouldIncludeInMainTimeline(diff.item)) {
                                _state.update { s ->
                                    val next = (s.events + diff.item).distinctBy { it.itemId }.sortedBy { e -> e.timestamp }
                                    s.copy(events = next)
                                }
                                // Secondary updates off the hot path
                                if (diff.item.eventId.isNotBlank()) {
                                    launch { refreshReactionsFor(diff.item.eventId) }
                                }
                                notifyIfNeeded(diff.item)
                                prefetchThumbnailsForEvents(listOf(diff.item))
                            }
                        }
                        is TimelineDiff.Update -> {
                            val wasInTimeline = _state.value.events.any { it.itemId == diff.item.itemId }
                            val shouldBeInTimeline = shouldIncludeInMainTimeline(diff.item)
                            
                            when {
                                // Item should stay in timeline - update it
                                wasInTimeline && shouldBeInTimeline -> {
                                    _state.update { s ->
                                        val idx = s.events.indexOfFirst { it.itemId == diff.item.itemId }
                                        val next = if (idx >= 0) {
                                            s.events.toMutableList().apply { set(idx, diff.item) }
                                        } else {
                                            s.events + diff.item
                                        }
                                        s.copy(events = next.sortedBy { e -> e.timestamp })
                                    }
                                    if (diff.item.eventId.isNotBlank()) {
                                        launch { refreshReactionsFor(diff.item.eventId) }
                                    }
                                    prefetchThumbnailsForEvents(listOf(diff.item))
                                }
                                // Item became a thread reply - remove from main timeline
                                wasInTimeline && !shouldBeInTimeline -> {
                                    _state.update { s ->
                                        s.copy(events = s.events.filterNot { it.itemId == diff.item.itemId })
                                    }
                                }
                                // Item wasn't in timeline but should be now (rare case)
                                !wasInTimeline && shouldBeInTimeline -> {
                                    _state.update { s ->
                                        val next = (s.events + diff.item).distinctBy { it.itemId }.sortedBy { e -> e.timestamp }
                                        s.copy(events = next)
                                    }
                                    if (diff.item.eventId.isNotBlank()) {
                                        launch { refreshReactionsFor(diff.item.eventId) }
                                    }
                                    prefetchThumbnailsForEvents(listOf(diff.item))
                                }
                                // Item wasn't and shouldn't be in timeline - nothing to do
                                else -> { /* no-op */ }
                            }
                        }
                        is TimelineDiff.Remove -> _state.update { s ->
                            s.copy(events = s.events.filterNot { it.itemId == diff.itemId })
                        }
                    }

                    // Update a few thread summaries rather than all
                    when (diff) {
                        is TimelineDiff.Insert -> {
                            if (diff.item.eventId.isNotBlank()) {
                                launch { refreshThreadSummaryFor(diff.item.eventId) }
                            }
                        }
                        is TimelineDiff.Update -> {
                            if (diff.item.eventId.isNotBlank()) {
                                launch { refreshThreadSummaryFor(diff.item.eventId) }
                            }
                        }
                        is TimelineDiff.Reset -> _state.value.events
                            .filter { it.eventId.isNotBlank() }
                            .takeLast(5)
                            .forEach { ev -> launch { refreshThreadSummaryFor(ev.eventId) } }
                        else -> {}
                    }

                    recomputeDerived()
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
        receiptsToken = service.port.observeReceipts(_state.value.roomId, object : ReceiptsObserver {
            override fun onChanged() {
                recomputeReadStatuses()
            }
        })
    }

    private fun notifyIfNeeded(event: MessageEvent) {
        val myId = _state.value.myUserId ?: return
        val senderIsMe = event.sender == myId

        if (Notifier.shouldNotify(_state.value.roomId, senderIsMe)) {
            val senderName = event.sender
                .removePrefix("@")
                .substringBefore(":")

            Notifier.notifyRoom(
                title = "$senderName in ${_state.value.roomName}",
                body = event.body.take(100)
            )
        }
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
        if (ev.eventId.isBlank()) return
        scope.launch {
            runCatching { service.port.react(_state.value.roomId, ev.eventId, emoji) }
            refreshReactionsFor(ev.eventId)
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

                // Fallback snapshot if diffs didn't land in time
                if (!arrived) {
                    val snap = service.loadRecent(
                        s.roomId,
                        limit = (_state.value.events.size + 50).coerceAtLeast(50)
                    )
                    val filtered = filterOutThreadReplies(snap)
                    _state.update {
                        it.copy(
                            events = filtered
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
        if (ev.eventId.isBlank()) return
        scope.launch {
            service.markReadAt(ev.roomId, ev.eventId)
        }
    }

    fun delete(ev: MessageEvent) {
        if (ev.eventId.isBlank()) return
        scope.launch { service.redact(_state.value.roomId, ev.eventId, null) }
    }

    private fun prefetchThumbnailsForEvents(events: List<MessageEvent>) {
        events.forEach { ev ->
            val a = ev.attachment ?: return@forEach
            if (a.kind != AttachmentKind.Image && a.kind != AttachmentKind.Video && a.thumbnailMxcUri == null) {
                return@forEach
            }
            // Skip if already cached
            if (_state.value.thumbByEvent.containsKey(ev.eventId)) return@forEach
            if (ev.eventId.isBlank()) return@forEach

            val mxc = a.thumbnailMxcUri ?: a.mxcUri
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

    private fun refreshReactionsFor(eventId: String) {
        if (eventId.isBlank()) return
        val rid = _state.value.roomId
        scope.launch {
            val chips = runCatching { service.port.reactions(rid, eventId) }.getOrDefault(emptyList())
            if (chips.isNotEmpty()) {
                _state.update { st ->
                    st.copy(reactionChips = st.reactionChips.toMutableMap().apply { put(eventId, chips) })
                }
            } else {
                _state.update { st ->
                    st.copy(reactionChips = st.reactionChips.toMutableMap().apply { remove(eventId) })
                }
            }
        }
    }

    private fun refreshThreadSummaryFor(eventId: String) {
        if (eventId.isBlank()) return
        val rid = _state.value.roomId
        scope.launch {
            // Slightly lighter than before to avoid hammering
            val s = runCatching { service.port.threadSummary(rid, eventId, perPage = 50, maxPages = 3) }.getOrNull()
            val cnt = s?.count?.toInt() ?: 0
            if (cnt > 0) {
                _state.update { st ->
                    st.copy(threadCount = st.threadCount.toMutableMap().apply { put(eventId, cnt) })
                }
            }
        }
    }

    fun onCleared() {
        Notifier.setCurrentRoom(null)
        typingToken?.let { service.stopTypingObserver(it) }
        receiptsToken?.let { service.port.stopReceiptsObserver(it) }
        ownReceiptToken?.let { service.port.stopReceiptsObserver(it) }
    }
}
