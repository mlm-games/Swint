package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import org.mlm.mages.platform.rememberFilePicker
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.ui.components.RoomUpgradeBanner
import org.mlm.mages.ui.components.attachment.AttachmentPicker
import org.mlm.mages.ui.components.attachment.AttachmentProgress
import org.mlm.mages.ui.components.composer.ActionBanner
import org.mlm.mages.ui.components.composer.MessageComposer
import org.mlm.mages.ui.components.core.*
import org.mlm.mages.ui.components.dialogs.InviteUserDialog
import org.mlm.mages.ui.components.message.MessageBubble
import org.mlm.mages.ui.components.message.MessageStatusLine
import org.mlm.mages.ui.components.message.SeenByChip
import org.mlm.mages.ui.components.sheets.LiveLocationBanner
import org.mlm.mages.ui.components.sheets.LiveLocationSheet
import org.mlm.mages.ui.components.sheets.MemberActionsSheet
import org.mlm.mages.ui.components.sheets.MemberListSheet
import org.mlm.mages.ui.components.sheets.MessageActionSheet
import org.mlm.mages.ui.components.sheets.PollCreatorSheet
import org.mlm.mages.ui.components.sheets.RoomNotificationSheet
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDate
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.util.formatTypingText
import org.mlm.mages.ui.viewmodel.RoomViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    viewModel: RoomViewModel,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onNavigateToRoom: (roomId: String, name: String) -> Unit,
    onNavigateToThread: (roomId: String, eventId: String, roomName: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val openExternal = rememberFileOpener()

    // File picker
    val picker = rememberFilePicker { data ->
        if (data != null) viewModel.sendAttachment(data)
        viewModel.hideAttachmentPicker()
    }

    // Selected message for action sheet
    var sheetEvent by remember { mutableStateOf<MessageEvent?>(null) }

    // Collect one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RoomViewModel.Event.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is RoomViewModel.Event.ShowSuccess -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is RoomViewModel.Event.NavigateToThread -> {
                    onNavigateToThread(event.roomId, event.eventId, event.roomName)
                }
                is RoomViewModel.Event.NavigateToRoom -> {
                    onNavigateToRoom(event.roomId, event.name)
                }
                is RoomViewModel.Event.NavigateBack -> {
                    onBack()
                }
            }
        }
    }

    // Computed values
    val events = remember(state.events) { state.events.sortedBy { it.timestamp } }

    val isNearBottom by remember(listState, events) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            events.isNotEmpty() && lastVisible >= events.lastIndex - 3
        }
    }

    val lastOutgoingIndex = remember(events, state.myUserId) {
        if (state.myUserId == null) -1 else events.indexOfLast { it.sender == state.myUserId }
    }

    val seenByNames = remember(events, lastOutgoingIndex, state.myUserId) {
        if (lastOutgoingIndex >= 0 && state.myUserId != null) {
            events.drop(lastOutgoingIndex + 1)
                .filter { it.sender != state.myUserId }
                .map { it.sender }
                .distinct()
                .map { sender -> sender.substringAfter('@').substringBefore(':').ifBlank { sender } }
        } else emptyList()
    }

    // Auto-scroll and mark read
    LaunchedEffect(events.lastOrNull()?.itemId, isNearBottom) {
        val last = events.lastOrNull() ?: return@LaunchedEffect
        if (isNearBottom) viewModel.markReadHere(last)
    }

    LaunchedEffect(events.size) {
        if (isNearBottom && events.isNotEmpty()) {
            listState.animateScrollToItem(events.lastIndex)
        }
    }

    // Active live location shares
    val activeLiveLocationUsers = remember(state.liveLocationShares) {
        state.liveLocationShares.values.filter { it.isLive }.map {
            it.userId.substringAfter('@').substringBefore(':')
        }
    }

    Scaffold(
        topBar = {
            RoomTopBar(
                roomName = state.roomName,
                roomId = state.roomId,
                typingNames = state.typingNames,
                isOffline = state.isOffline,
                onBack = onBack,
                onOpenInfo = onOpenInfo,
                onOpenNotifications = viewModel::showNotificationSettings,
                onOpenMembers = viewModel::showMembers
            )
        },
        bottomBar = {
            RoomBottomBar(
                state = state,
                onSetInput = viewModel::setInput,
                onSend = {
                    if (state.editing != null) viewModel.confirmEdit()
                    else viewModel.send()
                },
                onCancelReply = viewModel::cancelReply,
                onCancelEdit = viewModel::cancelEdit,
                onAttach = viewModel::showAttachmentPicker,
                onCancelUpload = viewModel::cancelAttachmentUpload
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isNearBottom,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(events.lastIndex.coerceAtLeast(0))
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    Spacer(Modifier.width(8.dp))
                    Text("Jump to bottom")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Room upgrade banner
            RoomUpgradeBanner(
                successor = state.successor,
                predecessor = state.predecessor,
                onNavigateToRoom = { roomId -> onNavigateToRoom(roomId, "Room") }
            )

            // Live location banner
            if (activeLiveLocationUsers.isNotEmpty()) {
                LiveLocationBanner(
                    sharingUsers = activeLiveLocationUsers,
                    onViewLocations = { /* TODO: Map view */ }
                )
            }

            // Message list
            Box(modifier = Modifier.weight(1f)) {
                if (events.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.ChatBubbleOutline,
                        title = "No messages yet",
                        subtitle = "Send a message to start the conversation"
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        reverseLayout = false
                    ) {
                        // Load earlier button
                        if (!state.hitStart) {
                            item(key = "load_earlier") {
                                LoadEarlierButton(
                                    isLoading = state.isPaginatingBack,
                                    onClick = viewModel::paginateBack
                                )
                            }
                        } else {
                            item(key = "start_of_conversation") {
                                StartOfConversationChip()
                            }
                        }

                        // Messages
                        itemsIndexed(events, key = { _, e -> e.itemId }) { index, event ->
                            MessageItem(
                                event = event,
                                index = index,
                                events = events,
                                state = state,
                                lastOutgoingIndex = lastOutgoingIndex,
                                seenByNames = seenByNames,
                                onLongPress = { sheetEvent = event },
                                onReact = { emoji -> viewModel.react(event, emoji) },
                                onOpenAttachment = {
                                    viewModel.openAttachment(event) { path, mime ->
                                        openExternal(path, mime)
                                    }
                                },
                                onOpenThread = { viewModel.openThread(event) }
                            )
                        }
                    }
                }
            }
        }
    }

    //  Sheets & Dialogs 

    // Attachment picker
    if (state.showAttachmentPicker) {
        AttachmentPicker(
            onPickImage = { picker.pick("image/*") },
            onPickVideo = { picker.pick("video/*") },
            onPickDocument = { picker.pick("*/*") },
            onDismiss = viewModel::hideAttachmentPicker,
            onCreatePoll = viewModel::showPollCreator,
            onShareLocation = viewModel::showLiveLocation
        )
    }

    // Poll creator
    if (state.showPollCreator) {
        PollCreatorSheet(
            onCreatePoll = viewModel::sendPoll,
            onDismiss = viewModel::hidePollCreator
        )
    }

    // Live location
    if (state.showLiveLocation) {
        LiveLocationSheet(
            isCurrentlySharing = viewModel.isCurrentlyShareingLocation,
            onStartSharing = viewModel::startLiveLocation,
            onStopSharing = viewModel::stopLiveLocation,
            onDismiss = viewModel::hideLiveLocation
        )
    }

    // Notification settings
    if (state.showNotificationSettings) {
        RoomNotificationSheet(
            currentMode = state.notificationMode,
            isLoading = state.isLoadingNotificationMode,
            onModeChange = viewModel::setNotificationMode,
            onDismiss = viewModel::hideNotificationSettings
        )
    }

    // Member list
    if (state.showMembers) {
        MemberListSheet(
            members = state.members,
            isLoading = state.isLoadingMembers,
            myUserId = state.myUserId,
            onDismiss = viewModel::hideMembers,
            onMemberClick = viewModel::selectMemberForAction,
            onInvite = viewModel::showInviteDialog
        )
    }

    // Member actions
    state.selectedMemberForAction?.let { member ->
        MemberActionsSheet(
            member = member,
            onDismiss = viewModel::clearSelectedMember,
            onStartDm = { viewModel.startDmWith(member.userId) },
            onKick = { reason -> viewModel.kickUser(member.userId, reason) },
            onBan = { reason -> viewModel.banUser(member.userId, reason) },
            onUnban = { reason -> viewModel.unbanUser(member.userId, reason) },
            onIgnore = { viewModel.ignoreUser(member.userId) },
            canModerate = true, // TODO: Check actual power levels
            isBanned = member.membership == "ban"
        )
    }

    // Invite dialog
    if (state.showInviteDialog) {
        InviteUserDialog(
            onInvite = viewModel::inviteUser,
            onDismiss = viewModel::hideInviteDialog
        )
    }

    // Message action sheet
    sheetEvent?.let { event ->
        val isMine = event.sender == state.myUserId
        MessageActionSheet(
            event = event,
            isMine = isMine,
            onDismiss = { sheetEvent = null },
            onReply = { viewModel.startReply(event); sheetEvent = null },
            onEdit = { viewModel.startEdit(event); sheetEvent = null },
            onDelete = { viewModel.delete(event); sheetEvent = null },
            onReact = { emoji -> viewModel.react(event, emoji) },
            onMarkReadHere = { viewModel.markReadHere(event); sheetEvent = null },
            onRetry = if (isMine && event.sendState == SendState.Failed) {
                { viewModel.retry(event); sheetEvent = null }
            } else null,
            onReplyInThread = { viewModel.openThread(event); sheetEvent = null }
        )
    }
}

//  Sub-composables 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomTopBar(
    roomName: String,
    roomId: String,
    typingNames: List<String>,
    isOffline: Boolean,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMembers: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    roomName.take(2).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(roomName, style = MaterialTheme.typography.titleMedium)
                            AnimatedContent(
                                targetState = typingNames.isNotEmpty(),
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "typing"
                            ) { hasTyping ->
                                if (hasTyping) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TypingDots()
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            formatTypingText(typingNames),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    Text(
                                        roomId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Default.Notifications, "Notifications")
                    }
                    IconButton(onClick = onOpenMembers) {
                        Icon(Icons.Default.People, "Members")
                    }
                    IconButton(onClick = onOpenInfo) {
                        Icon(Icons.Default.Info, "Room info")
                    }
                }
            )

            AnimatedVisibility(visible = isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Offline - Messages will be queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = Spacing.lg)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomBottomBar(
    state: org.mlm.mages.ui.RoomUiState,
    onSetInput: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onAttach: () -> Unit,
    onCancelUpload: () -> Unit
) {
    Column {
        ActionBanner(
            replyingTo = state.replyingTo,
            editing = state.editing,
            onCancelReply = onCancelReply,
            onCancelEdit = onCancelEdit
        )

        if (state.isUploadingAttachment && state.currentAttachment != null) {
            AttachmentProgress(
                fileName = state.currentAttachment.fileName,
                progress = state.attachmentProgress,
                totalSize = state.currentAttachment.sizeBytes,
                onCancel = onCancelUpload
            )
        }

        MessageComposer(
            value = state.input,
            enabled = true,
            isOffline = state.isOffline,
            replyingTo = state.replyingTo,
            editing = state.editing,
            currentAttachment = state.currentAttachment,
            isUploadingAttachment = state.isUploadingAttachment,
            onValueChange = onSetInput,
            onSend = onSend,
            onCancelReply = onCancelReply,
            onCancelEdit = onCancelEdit,
            onAttach = onAttach,
            onCancelUpload = onCancelUpload
        )
    }
}

@Composable
private fun LoadEarlierButton(isLoading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(onClick = onClick, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isLoading) "Loading..." else "Load earlier messages")
        }
    }
}

@Composable
private fun StartOfConversationChip() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text("Beginning of conversation") }
        )
    }
}

@Composable
private fun MessageItem(
    event: MessageEvent,
    index: Int,
    events: List<MessageEvent>,
    state: org.mlm.mages.ui.RoomUiState,
    lastOutgoingIndex: Int,
    seenByNames: List<String>,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onOpenAttachment: () -> Unit,
    onOpenThread: () -> Unit
) {
    val eventDate = formatDate(event.timestamp)
    val prevDate = events.getOrNull(index - 1)?.let { formatDate(it.timestamp) }

    // Date header
    if (prevDate != eventDate) {
        DateHeader(eventDate)
    }

    // Unread divider
    val lastReadTs = state.lastReadTs
    if (lastReadTs != null) {
        val prevTs = events.getOrNull(index - 1)?.timestamp
        val showUnread = (event.timestamp > lastReadTs) && (prevTs == null || prevTs <= lastReadTs)
        if (showUnread) UnreadDivider()
    } else if (index == 0) {
        UnreadDivider()
    }

    // Message bubble
    val chips = state.reactionChips[event.eventId] ?: emptyList()
    val prevEvent = events.getOrNull(index - 1)
    val shouldGroup = prevEvent != null &&
            prevEvent.sender == event.sender &&
            prevDate == eventDate &&
            (event.timestamp - prevEvent.timestamp) < 300_000

    val isMine = event.sender == state.myUserId

    MessageBubble(
        isMine = isMine,
        body = event.body,
        sender = formatDisplayName(event.sender),
        timestamp = event.timestamp,
        grouped = shouldGroup,
        reactionChips = chips,
        eventId = event.eventId,
        onLongPress = onLongPress,
        onReact = onReact,
        lastReadByOthersTs = state.lastIncomingFromOthersTs,
        thumbPath = state.thumbByEvent[event.eventId],
        attachmentKind = event.attachment?.kind,
        durationMs = event.attachment?.durationMs,
        onOpenAttachment = onOpenAttachment,
        replyPreview = event.replyToBody,
        replySender = event.replyToSender?.let { formatDisplayName(it) },
        sendState = event.sendState
    )

    // Thread count
    val threadCount = state.threadCount[event.eventId] ?: 0
    if (threadCount > 0) {
        Row(
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            TextButton(onClick = onOpenThread) {
                Text("View thread ($threadCount)")
            }
        }
    }

    Spacer(Modifier.height(2.dp))

    // Read status for last outgoing message
    if (index == lastOutgoingIndex) {
        if (state.isDm) {
            val lastOutgoing = events.getOrNull(lastOutgoingIndex)
            val statusText = when {
                lastOutgoing?.eventId.isNullOrBlank() -> "Sending…"
                state.lastOutgoingRead -> "Seen ${formatTime(lastOutgoing!!.timestamp)}"
                else -> "Delivered"
            }
            MessageStatusLine(text = statusText, isMine = true)
        } else {
            if (seenByNames.isNotEmpty()) {
                SeenByChip(names = seenByNames)
            }
        }
    }
}

@Composable
private fun DateHeader(date: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg, horizontal = Spacing.xxl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(horizontal = Spacing.md)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 4.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
        )
    }
}