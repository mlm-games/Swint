package org.mlm.mages.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.mlm.mages.*
import org.mlm.mages.matrix.*
import org.mlm.mages.platform.Notifier
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.RoomUiState
import org.mlm.mages.ui.components.AttachmentData

class RoomViewModel(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>,
    private val savedStateHandle: SavedStateHandle
) : BaseViewModel<RoomUiState>(
    RoomUiState(
        roomId = savedStateHandle.get<String>("roomId") ?: "",
        roomName = savedStateHandle.get<String>("roomName") ?: ""
    )
) {
    // Constructor for Koin with parameters
    constructor(
        service: MatrixService,
        dataStore: DataStore<Preferences>,
        roomId: String,
        roomName: String
    ) : this(
        service = service,
        dataStore = dataStore,
        savedStateHandle = SavedStateHandle(mapOf("roomId" to roomId, "roomName" to roomName))
    )

    // One-time events
    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data class NavigateToThread(val roomId: String, val eventId: String, val roomName: String) : Event()
        data class NavigateToRoom(val roomId: String, val name: String) : Event()
        data object NavigateBack : Event()

        data class ShareMessage(
            val text: String?,
            val filePath: String?,
            val mimeType: String?
        ) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var typingToken: ULong? = null
    private var receiptsToken: ULong? = null
    private var ownReceiptToken: ULong? = null
    private var dmPeer: String? = null
    private var uploadJob: Job? = null
    private var typingJob: Job? = null
    private var hasTimelineSnapshot = false

    init {
        initialize()
    }

    private fun initialize() {
        updateState { copy(myUserId = service.port.whoami()) }
        observeTimeline()
        observeTyping()
        observeOwnReceipt()
        observeLiveLocation()
        observeReceipts()
        loadNotificationMode()
        loadUpgradeInfo()

        launch {
            dmPeer = runSafe { service.port.dmPeerUserId(currentState.roomId) }
            val profile = runSafe { service.port.roomProfile(currentState.roomId) }
            if (profile != null) {
                updateState { copy(isDm = profile.isDm) }
            }
        }
    }

    //  UI Sheet Toggles 

    fun showAttachmentPicker() = updateState { copy(showAttachmentPicker = true) }
    fun hideAttachmentPicker() = updateState { copy(showAttachmentPicker = false) }

    fun showPollCreator() = updateState { copy(showPollCreator = true, showAttachmentPicker = false) }
    fun hidePollCreator() = updateState { copy(showPollCreator = false) }

    fun showLiveLocation() = updateState { copy(showLiveLocation = true, showAttachmentPicker = false) }
    fun hideLiveLocation() = updateState { copy(showLiveLocation = false) }

    fun showNotificationSettings() = updateState { copy(showNotificationSettings = true) }
    fun hideNotificationSettings() = updateState { copy(showNotificationSettings = false) }

    fun showMembers() {
        updateState { copy(showMembers = true, isLoadingMembers = true) }
        loadMembers()
    }
    fun hideMembers() = updateState { copy(showMembers = false, selectedMemberForAction = null) }

    fun selectMemberForAction(member: MemberSummary) = updateState { copy(selectedMemberForAction = member) }
    fun clearSelectedMember() = updateState { copy(selectedMemberForAction = null) }

    fun showInviteDialog() = updateState { copy(showInviteDialog = true) }
    fun hideInviteDialog() = updateState { copy(showInviteDialog = false) }

    //  Message Input 

    fun setInput(value: String) {
        updateState { copy(input = value) }
        typingJob?.cancel()
        typingJob = launch {
            if (value.isBlank()) {
                runSafe { service.port.setTyping(currentState.roomId, false) }
            } else {
                runSafe { service.port.setTyping(currentState.roomId, true) }
                delay(4000)
                runSafe { service.port.setTyping(currentState.roomId, false) }
            }
        }
    }

    fun send() {
        val s = currentState
        if (s.input.isBlank()) return

        launch {
            val text = s.input.trim()
            val replyTo = s.replyingTo

            val ok = if (replyTo != null) {
                service.reply(s.roomId, replyTo.eventId, text)
            } else {
                service.sendMessage(s.roomId, text)
            }

            if (ok) {
                updateState { copy(input = "", replyingTo = null) }
            } else {
                _events.send(Event.ShowError(if (replyTo != null) "Reply failed" else "Send failed"))
            }
        }
    }

    //  Reply/Edit 

    fun startReply(event: MessageEvent) = updateState { copy(replyingTo = event) }
    fun cancelReply() = updateState { copy(replyingTo = null) }

    fun startEdit(event: MessageEvent) = updateState { copy(editing = event, input = event.body) }
    fun cancelEdit() = updateState { copy(editing = null, input = "") }

    fun confirmEdit() {
        val s = currentState
        val target = s.editing ?: return

        launch {
            val ok = service.edit(s.roomId, target.eventId, s.input.trim())
            if (ok) {
                updateState { copy(editing = null, input = "") }
            } else {
                _events.send(Event.ShowError("Edit failed"))
            }
        }
    }

    //  Reactions 

    fun react(event: MessageEvent, emoji: String) {
        if (event.eventId.isBlank()) return
        launch {
            runSafe { service.port.react(currentState.roomId, event.eventId, emoji) }
            refreshReactionsFor(event.eventId)
        }
    }

    //  Delete/Retry 

    fun delete(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        launch {
            val ok = service.redact(currentState.roomId, event.eventId, null)
            if (!ok) {
                _events.send(Event.ShowError("Delete failed"))
            }
        }
    }

    fun retry(event: MessageEvent) {
        if (event.body.isBlank()) return
        launch {
            val triedPrecise = event.txnId?.let { txn ->
                service.retryByTxn(currentState.roomId, txn)
            } ?: false

            val ok = if (triedPrecise) true else service.sendMessage(currentState.roomId, event.body.trim())
            if (!ok) {
                _events.send(Event.ShowError("Retry failed"))
            }
        }
    }

    //  Pagination 

    fun paginateBack() {
        val s = currentState
        if (s.isPaginatingBack || s.hitStart) return

        launch {
            updateState { copy(isPaginatingBack = true) }
            try {
                val hitStart = runSafe { service.paginateBack(s.roomId, 50) } ?: false
                updateState { copy(hitStart = hitStart || this.hitStart) }

                withTimeoutOrNull(1500) {
                    state.first { it.events.size > s.events.size || it.hitStart }
                }
            } finally {
                updateState { copy(isPaginatingBack = false) }
            }
        }
    }

    //  Read Receipts 

    fun markReadHere(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        launch { service.markReadAt(event.roomId, event.eventId) }
    }

    //  Attachments 

    fun sendAttachment(data: AttachmentData) {
        if (currentState.isUploadingAttachment) return

        updateState {
            copy(
                currentAttachment = data,
                isUploadingAttachment = true,
                attachmentProgress = 0f,
                showAttachmentPicker = false
            )
        }

        uploadJob = launch {
            val ok = service.sendAttachmentFromPath(
                roomId = currentState.roomId,
                path = data.path,
                mime = data.mimeType,
                filename = data.fileName
            ) { sent, total ->
                val denom = (total ?: data.sizeBytes).coerceAtLeast(1L).toFloat()
                val p = (sent.toFloat() / denom).coerceIn(0f, 1f)
                updateState { copy(attachmentProgress = p) }
            }

            updateState {
                copy(
                    isUploadingAttachment = false,
                    attachmentProgress = 0f,
                    currentAttachment = null
                )
            }

            if (!ok) {
                _events.send(Event.ShowError("Attachment upload failed"))
            }
        }
    }

    fun cancelAttachmentUpload() {
        uploadJob?.cancel()
        uploadJob = null
        updateState {
            copy(
                isUploadingAttachment = false,
                attachmentProgress = 0f,
                currentAttachment = null
            )
        }
    }

    fun openAttachment(event: MessageEvent, onOpen: (String, String?) -> Unit) {
        val a = event.attachment ?: return
        launch {
            val nameHint = run {
                val ext = a.mime?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                val base = event.eventId.ifBlank { "file" }
                if (ext != null) "$base.$ext" else base
            }

            service.port.downloadAttachmentToCache(a, nameHint)
                .onSuccess { path ->
                    val f = java.io.File(path)
                    if (!f.exists() || f.length() == 0L) {
                        _events.send(
                            Event.ShowError("Downloaded file is missing or empty: $path")
                        )
                        return@onSuccess
                    }
                    onOpen(path, a.mime)
                }
                .onFailure { t ->
                    _events.send(
                        Event.ShowError(t.message ?: "Download failed")
                    )
                }
        }
    }

    fun shareMessage(event: MessageEvent) {
        launch {
            val text = event.body.takeIf { it.isNotBlank() }
            val attachment = event.attachment

            if (attachment == null) {
                _events.send(
                    Event.ShareMessage(
                        text = text,
                        filePath = null,
                        mimeType = null
                    )
                )
            } else {
                val nameHint = run {
                    val ext = attachment.mime?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    val base = event.eventId.ifBlank { "file" }
                    if (ext != null) "$base.$ext" else base
                }

                service.port.downloadAttachmentToCache(attachment, nameHint)
                    .onSuccess { path ->
                        _events.send(
                            Event.ShareMessage(
                                text = text,
                                filePath = path,
                                mimeType = attachment.mime
                            )
                        )
                    }
                    .onFailure { t ->
                        _events.send(
                            Event.ShowError(t.message ?: "Failed to prepare share")
                        )
                    }
            }
        }
    }

    //  Live Location 

    fun startLiveLocation(durationMinutes: Int) {
        launch {
            val durationMs = durationMinutes * 60 * 1000L
            val ok = service.port.startLiveLocationShare(currentState.roomId, durationMs)
            if (ok) {
                updateState { copy(showLiveLocation = false) }
                _events.send(Event.ShowSuccess("Location sharing started"))
            } else {
                _events.send(Event.ShowError("Failed to start location sharing"))
            }
        }
    }

    fun stopLiveLocation() {
        launch {
            val ok = service.port.stopLiveLocationShare(currentState.roomId)
            if (ok) {
                updateState { copy(showLiveLocation = false) }
                _events.send(Event.ShowSuccess("Location sharing stopped"))
            } else {
                _events.send(Event.ShowError("Failed to stop location sharing"))
            }
        }
    }

    val isCurrentlyShareingLocation: Boolean
        get() = currentState.liveLocationShares[currentState.myUserId]?.isLive == true

    //  Polls 

    fun sendPoll(question: String, answers: List<String>) {
        val q = question.trim()
        val opts = answers.map { it.trim() }.filter { it.isNotBlank() }
        if (q.isBlank() || opts.size < 2) return

        launch {
            val ok = service.port.sendPoll(currentState.roomId, q, opts)
            if (ok) {
                updateState { copy(showPollCreator = false) }
            } else {
                _events.send(Event.ShowError("Failed to create poll"))
            }
        }
    }

    //  Notification Settings 

    fun setNotificationMode(mode: RoomNotificationMode) {
        launch {
            val ok = service.port.setRoomNotificationMode(currentState.roomId, mode)
            if (ok) {
                updateState { copy(notificationMode = mode, showNotificationSettings = false) }
                _events.send(Event.ShowSuccess("Notification settings updated"))
            } else {
                _events.send(Event.ShowError("Failed to update notifications"))
            }
        }
    }

    private fun loadNotificationMode() {
        launch {
            updateState { copy(isLoadingNotificationMode = true) }
            val mode = runSafe { service.port.roomNotificationMode(currentState.roomId) }
            updateState {
                copy(
                    notificationMode = mode ?: RoomNotificationMode.AllMessages,
                    isLoadingNotificationMode = false
                )
            }
        }
    }

    //  Room Upgrade 

    private fun loadUpgradeInfo() {
        launch {
            val successor = runSafe { service.port.roomSuccessor(currentState.roomId) }
            val predecessor = runSafe { service.port.roomPredecessor(currentState.roomId) }
            updateState { copy(successor = successor, predecessor = predecessor) }
        }
    }

    fun navigateToUpgradedRoom() {
        val successor = currentState.successor ?: return
        launch {
            _events.send(Event.NavigateToRoom(successor.roomId, "Upgraded Room"))
        }
    }

    fun navigateToPredecessorRoom() {
        val predecessor = currentState.predecessor ?: return
        launch {
            _events.send(Event.NavigateToRoom(predecessor.roomId, "Previous Room"))
        }
    }

    //  Members & Moderation 

    private fun loadMembers() {
        launch {
            val members = runSafe { service.port.listMembers(currentState.roomId) } ?: emptyList()
            val sorted = members.sortedWith(
                compareByDescending<MemberSummary> { it.isMe }
                    .thenBy { it.displayName ?: it.userId }
            )
            updateState { copy(members = sorted, isLoadingMembers = false) }
        }
    }

    fun kickUser(userId: String, reason: String? = null) {
        launch {
            val ok = service.port.kickUser(currentState.roomId, userId, reason)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                loadMembers()
                _events.send(Event.ShowSuccess("User removed from room"))
            } else {
                _events.send(Event.ShowError("Failed to remove user"))
            }
        }
    }

    fun banUser(userId: String, reason: String? = null) {
        launch {
            val ok = service.port.banUser(currentState.roomId, userId, reason)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                loadMembers()
                _events.send(Event.ShowSuccess("User banned"))
            } else {
                _events.send(Event.ShowError("Failed to ban user"))
            }
        }
    }

    fun unbanUser(userId: String, reason: String? = null) {
        launch {
            val ok = service.port.unbanUser(currentState.roomId, userId, reason)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                loadMembers()
                _events.send(Event.ShowSuccess("User unbanned"))
            } else {
                _events.send(Event.ShowError("Failed to unban user"))
            }
        }
    }

    fun inviteUser(userId: String) {
        launch {
            val ok = service.port.inviteUser(currentState.roomId, userId)
            if (ok) {
                updateState { copy(showInviteDialog = false) }
                loadMembers()
                _events.send(Event.ShowSuccess("Invitation sent"))
            } else {
                _events.send(Event.ShowError("Failed to invite user"))
            }
        }
    }

    fun ignoreUser(userId: String) {
        launch {
            val ok = service.port.ignoreUser(userId)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                _events.send(Event.ShowSuccess("User ignored"))
            } else {
                _events.send(Event.ShowError("Failed to ignore user"))
            }
        }
    }

    fun startDmWith(userId: String) {
        launch {
            val dmId = runSafe { service.port.ensureDm(userId) }
            if (dmId != null) {
                updateState { copy(selectedMemberForAction = null, showMembers = false) }
                _events.send(Event.NavigateToRoom(dmId, userId))
            } else {
                _events.send(Event.ShowError("Failed to start conversation"))
            }
        }
    }

    fun startForward(event: MessageEvent) {
        updateState {
            copy(
                forwardingEvent = event,
                showForwardPicker = true,
                isLoadingForwardRooms = true,
                forwardSearchQuery = ""
            )
        }
        loadForwardableRooms()
    }

    fun cancelForward() {
        updateState {
            copy(
                forwardingEvent = null,
                showForwardPicker = false,
                forwardableRooms = emptyList(),
                forwardSearchQuery = ""
            )
        }
    }

    fun setForwardSearch(query: String) {
        updateState { copy(forwardSearchQuery = query) }
    }

    val filteredForwardRooms: List<ForwardableRoom>
        get() {
            val query = currentState.forwardSearchQuery.lowercase()
            return if (query.isBlank()) {
                currentState.forwardableRooms
            } else {
                currentState.forwardableRooms.filter { it.name.lowercase().contains(query) }
            }
        }

    fun forwardTo(targetRoomId: String) {
        val event = currentState.forwardingEvent ?: return

        launch {
            updateState { copy(showForwardPicker = false) }

            val success = forwardMessage(event, targetRoomId)

            if (success) {
                _events.send(Event.ShowSuccess("Message forwarded"))
                // Optionally navigate to the target room
                val targetName = currentState.forwardableRooms
                    .find { it.roomId == targetRoomId }?.name ?: "Room"
                _events.send(Event.NavigateToRoom(targetRoomId, targetName))
            } else {
                _events.send(Event.ShowError("Failed to forward message"))
            }

            updateState { copy(forwardingEvent = null, forwardableRooms = emptyList()) }
        }
    }

    private suspend fun forwardMessage(event: MessageEvent, targetRoomId: String): Boolean {
        return try {
            val attachment = event.attachment

            if (attachment != null) {
                // no download/re-upload
                service.port.sendExistingAttachment(
                    roomId = targetRoomId,
                    attachment = attachment,
                    body = event.body.takeIf { it.isNotBlank() && it != attachment.mxcUri }
                )
            } else {
                service.sendMessage(targetRoomId, event.body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadForwardableRooms() {
        launch {
            val rooms = runSafe {
                service.port.listRooms()
            }?.filter {
                it.id != currentState.roomId // Exclude current room
            }?.map { room ->
                ForwardableRoom(
                    roomId = room.id,
                    name = room.name,
                    avatarUrl = "", //TODO 3 addns
                    isDm = true,
                    lastActivity = 0L
                )
            }?.sortedByDescending { it.lastActivity } ?: emptyList()

            updateState {
                copy(forwardableRooms = rooms, isLoadingForwardRooms = false)
            }
        }
    }

    //  Thread Navigation 

    fun openThread(event: MessageEvent) {
        launch {
            _events.send(Event.NavigateToThread(currentState.roomId, event.eventId, currentState.roomName))
        }
    }

    //  Private Helpers 

    private fun filterOutThreadReplies(events: List<MessageEvent>): List<MessageEvent> {
        return events.filter { it.threadRootEventId == null }
    }

    private fun observeTimeline() {
        Notifier.setCurrentRoom(currentState.roomId)

        viewModelScope.launch(Dispatchers.Default) {
            service.timelineDiffs(currentState.roomId)
                .buffer(capacity = Channel.BUFFERED)
                .collect { diff -> processDiff(diff) }
        }
    }

    private fun postProcessNewEvents(newEvents: List<MessageEvent>) {
        val visible = newEvents.filter { it.threadRootEventId == null }

        visible.takeLast(10).forEach { ev ->
            if (ev.eventId.isNotBlank()) {
                launch { refreshReactionsFor(ev.eventId) }
                launch { refreshThreadSummaryFor(ev.eventId) }
            }
        }

        prefetchThumbnailsForEvents(visible.takeLast(8))

        // send notifications only for visible events
        visible.forEach { notifyIfNeeded(it) }
    }

    private fun processDiff(diff: TimelineDiff<MessageEvent>) {
        when (diff) {
            is TimelineDiff.Reset -> {
                hasTimelineSnapshot = true

                val all = diff.items
                updateState {
                    copy(
                        allEvents = all,
                        events = all.withoutThreadReplies().dedupByItemId(),
                    )
                }
                postProcessNewEvents(diff.items)
            }

            is TimelineDiff.Clear -> {
                updateState {
                    copy(
                        allEvents = emptyList(),
                        events = emptyList()
                    )
                }
            }

            is TimelineDiff.Append -> {
                updateState {
                    val newAll = allEvents + diff.items
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
                postProcessNewEvents(diff.items)
            }

            is TimelineDiff.InsertAt -> {
                updateState {
                    val idx = diff.index.coerceIn(0, allEvents.size)
                    val mutable = allEvents.toMutableList()
                    mutable.add(idx, diff.item)
                    val newAll = mutable.toList()
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
                postProcessNewEvents(listOf(diff.item))
            }

            is TimelineDiff.UpdateAt -> {
                updateState {
                    if (diff.index !in allEvents.indices) return@updateState this
                    val mutable = allEvents.toMutableList()
                    mutable[diff.index] = diff.item
                    val newAll = mutable.toList()
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
                postProcessNewEvents(listOf(diff.item))
            }

            is TimelineDiff.RemoveAt -> {
                updateState {
                    if (diff.index !in allEvents.indices) return@updateState this
                    val mutable = allEvents.toMutableList()
                    mutable.removeAt(diff.index)
                    val newAll = mutable.toList()
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
            }

            is TimelineDiff.Truncate -> {
                updateState {
                    val len = diff.length.coerceAtMost(allEvents.size)
                    val newAll = allEvents.take(len)
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
            }

            TimelineDiff.PopFront -> {
                updateState {
                    if (allEvents.isEmpty()) return@updateState this
                    val newAll = allEvents.drop(1)
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
            }

            TimelineDiff.PopBack -> {
                updateState {
                    if (allEvents.isEmpty()) return@updateState this
                    val newAll = allEvents.dropLast(1)
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                    )
                }
            }
        }

        recomputeDerived()
    }

    private fun observeTyping() {
        typingToken?.let { service.stopTypingObserver(it) }
        typingToken = service.observeTyping(currentState.roomId) { names ->
            updateState { copy(typingNames = names) }
        }
    }

    private fun observeReceipts() {
        receiptsToken?.let { service.port.stopReceiptsObserver(it) }
        receiptsToken = service.port.observeReceipts(currentState.roomId, object : ReceiptsObserver {
            override fun onChanged() {
                recomputeReadStatuses()
            }
        })
    }

    private fun observeOwnReceipt() {
        launch {
            runSafe { service.port.ownLastRead(currentState.roomId) }
                ?.let { (_, ts) -> updateState { copy(lastReadTs = ts) } }
        }

        ownReceiptToken?.let { service.port.stopReceiptsObserver(it) }
        ownReceiptToken = service.port.observeOwnReceipt(currentState.roomId, object : ReceiptsObserver {
            override fun onChanged() {
                launch {
                    runSafe { service.port.ownLastRead(currentState.roomId) }
                        ?.let { (_, ts) -> updateState { copy(lastReadTs = ts) } }
                }
            }
        })
    }

    private fun observeLiveLocation() {
        val token = service.port.observeLiveLocation(currentState.roomId) { shares ->
            updateState {
                copy(liveLocationShares = shares.associateBy { it.userId })
            }
        }
        updateState { copy(liveLocationSubToken = token) }
    }

    private fun notifyIfNeeded(event: MessageEvent) {
        val myId = currentState.myUserId ?: return
        val senderIsMe = event.sender == myId

        if (Notifier.shouldNotify(currentState.roomId, senderIsMe)) {
            val senderName = event.sender.removePrefix("@").substringBefore(":")
            Notifier.notifyRoom(
                title = "$senderName in ${currentState.roomName}",
                body = event.body.take(100)
            )
        }
    }

    private fun recomputeDerived() {
        val s = currentState
        val me = s.myUserId ?: return

        if (s.events.isEmpty()) {
            updateState { copy(lastIncomingFromOthersTs = null, lastOutgoingRead = false) }
            return
        }

        val lastIncoming = s.events.asSequence().filter { it.sender != me }.maxOfOrNull { it.timestamp }
        updateState { copy(lastIncomingFromOthersTs = lastIncoming) }

        if (s.isDm) recomputeReadStatuses()
    }

    private fun recomputeReadStatuses() {
        val s = currentState
        if (!s.isDm) return

        val me = s.myUserId ?: return
        val lastOutgoing = s.events.lastOrNull { it.sender == me } ?: run {
            updateState { copy(lastOutgoingRead = false) }
            return
        }

        val peer = dmPeer ?: return
        launch {
            val read = runSafe {
                service.port.isEventReadBy(s.roomId, lastOutgoing.eventId, peer)
            } ?: false
            updateState { copy(lastOutgoingRead = read) }
        }
    }

    private fun prefetchThumbnailsForEvents(events: List<MessageEvent>) {
        events.forEach { ev ->
            val a = ev.attachment ?: return@forEach
            if (a.kind != AttachmentKind.Image && a.kind != AttachmentKind.Video && a.thumbnailMxcUri == null) return@forEach
            if (currentState.thumbByEvent.containsKey(ev.eventId)) return@forEach
            if (ev.eventId.isBlank()) return@forEach

            launch {
                service.thumbnailToCache(a, 320, 320, true).onSuccess { path ->
                    updateState {
                        copy(thumbByEvent = thumbByEvent + (ev.eventId to path))
                    }
                }
            }
        }
    }

    private suspend fun refreshReactionsFor(eventId: String) {
        if (eventId.isBlank()) return
        val chips = runSafe { service.port.reactions(currentState.roomId, eventId) } ?: emptyList()
        updateState {
           copy(reactionChips =reactionChips.toMutableMap().apply {
                if (chips.isNotEmpty()) put(eventId, chips) else remove(eventId)
            })
        }
    }

    private suspend fun refreshThreadSummaryFor(eventId: String) {
        if (eventId.isBlank()) return
        val s = runSafe { service.port.threadSummary(currentState.roomId, eventId, perPage = 50, maxPages = 3) }
        val cnt = s?.count?.toInt() ?: 0
        if (cnt > 0) {
            updateState {
               copy(threadCount =threadCount.toMutableMap().apply { put(eventId, cnt) })
            }
        }
    }

    private fun List<MessageEvent>.withoutThreadReplies(): List<MessageEvent> =
        this.filter { it.threadRootEventId == null }

    private fun List<MessageEvent>.dedupByItemId(): List<MessageEvent> =
        distinctBy { it.itemId }

    override fun onCleared() {
        super.onCleared()
        Notifier.setCurrentRoom(null)
        typingToken?.let { service.stopTypingObserver(it) }
        receiptsToken?.let { service.port.stopReceiptsObserver(it) }
        ownReceiptToken?.let { service.port.stopReceiptsObserver(it) }
        currentState.liveLocationSubToken?.let { service.port.stopObserveLiveLocation(it) }
    }
}