package org.mlm.frair.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.frair.MessageEvent
import org.mlm.frair.platform.rememberFilePicker
import org.mlm.frair.ui.RoomUiState
import org.mlm.frair.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    state: RoomUiState,
    onBack: () -> Unit,
    onSetInput: (String) -> Unit,
    onSend: () -> Unit,
    onReply: (MessageEvent) -> Unit,
    onCancelReply: () -> Unit,
    onEdit: (MessageEvent) -> Unit,
    onCancelEdit: () -> Unit,
    onConfirmEdit: () -> Unit,
    onReact: (MessageEvent, String) -> Unit,
    onPaginateBack: () -> Unit,
    onMarkReadHere: (MessageEvent) -> Unit,
    onSendAttachment: (AttachmentData) -> Unit,
    onCancelUpload: () -> Unit
) {
    val listState = rememberLazyListState()
    val events = remember(state.events) { state.events.sortedBy { it.timestamp } }
    val isNearBottom by remember(listState, events) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            events.isNotEmpty() && lastVisible >= events.lastIndex - 3
        }
    }
    val scope = rememberCoroutineScope()
    var showAttachmentPicker by remember { mutableStateOf(false) }

    val picker = rememberFilePicker { data ->
        if (data != null) {
            onSendAttachment(data)
        }
        showAttachmentPicker = false
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Column {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(state.roomName.take(2).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(state.roomName, style = MaterialTheme.typography.titleMedium)
                                    AnimatedContent(
                                        targetState = state.typingNames.isNotEmpty(),
                                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                                        label = "typing"
                                    ) { hasTyping ->
                                        if (hasTyping) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TypingDots()
                                                Spacer(Modifier.width(4.dp))
                                                Text(formatTypingText(state.typingNames), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                            }
                                        } else {
                                            Text(state.roomId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        },
                        actions = { IconButton(onClick = { /* TODO */ }) { Icon(Icons.Default.Info, "Room info") } }
                    )
                    AnimatedVisibility(visible = state.isOffline) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Text("Offline - Messages will be queued", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                // Context banner (reply/edit)
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
                
                OutboxChips(items = state.outbox)

                MessageComposer(
                    value = state.input,
                    enabled = true,
                    isOffline = state.isOffline,
                    replyingTo = state.replyingTo,
                    editing = state.editing,
                    currentAttachment = null,
                    isUploadingAttachment = false,
                    onValueChange = onSetInput,
                    onSend = {
                        if (state.editing != null) onConfirmEdit() else onSend()
                    },
                    onCancelReply = onCancelReply,
                    onCancelEdit = onCancelEdit,
                    onAttach = { showAttachmentPicker = true },
                    onCancelUpload = onCancelUpload
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !isNearBottom, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(events.lastIndex.coerceAtLeast(0)) }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    Spacer(Modifier.width(8.dp))
                    Text("Jump to bottom")
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (events.isEmpty()) {
                EmptyRoomView()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    reverseLayout = false
                ) {
                    if (!state.hitStart) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                OutlinedButton(onClick = onPaginateBack, enabled = !state.isPaginatingBack) {
                                    if (state.isPaginatingBack) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (state.isPaginatingBack) "Loading..." else "Load earlier messages")
                                }
                            }
                        }
                    } else {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                AssistChip(onClick = {}, enabled = false, label = { Text("Beginning of conversation") })
                            }
                        }
                    }

                    var lastDate: String? = null
                    itemsIndexed(events, key = { _, e -> e.itemId }) { _, event ->
                        val eventDate = formatDate(event.timestamp)
                        if (eventDate != lastDate) {
                            DateHeader(eventDate)
                            lastDate = eventDate
                        }
                        MessageBubble(
                            isMine = (event.sender == state.myUserId),
                            body = event.body,
                            sender = event.sender,
                            timestamp = event.timestamp,
                            grouped = false,
                            reactions = emptySet(),
                            onLongPress = { /* open action sheet later */ },
                            onReact = { emoji -> onReact(event, emoji) }
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }

    if (showAttachmentPicker) {
        AttachmentPicker(
            onPickImage = { picker.pick("image/*") },
            onPickVideo = { picker.pick("video/*") },
            onPickDocument = { picker.pick("*/*") },
            onDismiss = { showAttachmentPicker = false }
        )
    }
}


@Composable
private fun DateHeader(date: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 32.dp),
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
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
        )
    }
}