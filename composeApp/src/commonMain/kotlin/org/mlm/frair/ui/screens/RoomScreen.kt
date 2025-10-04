package org.mlm.frair.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.frair.*
import org.mlm.frair.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    val room = (state.screen as Screen.Room).room
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showActions by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<MessageEvent?>(null) }
    var showAttachmentPicker by remember { mutableStateOf(false) }

    val events = remember(state.events) { state.events.sortedBy { it.timestamp } }
    val outbox = state.pendingByRoom[room.id].orEmpty()

    val isNearBottom by remember(listState, events) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            events.isNotEmpty() && lastVisible >= events.lastIndex - 3
        }
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        topBar = {
            // Custom top bar with room info
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Column {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Room avatar
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Text(
                                            text = room.name.take(2).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = room.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    // Typing indicator or member count
                                    val typing = state.typing[room.id].orEmpty()
                                    AnimatedContent(
                                        targetState = typing.isNotEmpty(),
                                        transitionSpec = {
                                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                        }
                                    ) { hasTyping ->
                                        if (hasTyping) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TypingDots()
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = formatTypingText(typing.toList()),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = room.id,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { onIntent(Intent.Back) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { /* Room info */ }) {
                                Icon(Icons.Default.Info, "Room info")
                            }
                            IconButton(onClick = { showActions = !showActions }) {
                                Icon(Icons.Default.MoreVert, "More")
                            }
                        }
                    )

                    // Connection status bar
                    AnimatedVisibility(visible = state.isOffline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Offline - Messages will be queued",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    // Sync progress bar
                    AnimatedVisibility(visible = state.syncBanner != null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                // Upload progress
                AnimatedVisibility(
                    visible = state.currentAttachment != null && state.isUploadingAttachment
                ) {
                    state.currentAttachment?.let { attachment ->
                        val progress = state.uploadProgress[attachment.path] ?: 0f
                        AttachmentProgress(
                            fileName = attachment.fileName,
                            progress = progress,
                            totalSize = attachment.sizeBytes,
                            onCancel = { onIntent(Intent.CancelCurrentUpload) }
                        )
                    }
                }

                // Send status chips
                AnimatedVisibility(visible = outbox.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(outbox) { indicator ->
                                SendStatusChip(indicator)
                            }
                        }
                    }
                }

                // Pending message count
                AnimatedVisibility(visible = state.pendingSendCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${state.pendingSendCount} messages in queue",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            TextButton(
                                onClick = { onIntent(Intent.ClearFailedMessages) }
                            ) {
                                Text("Clear failed", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // Modern composer with attachment support
                MessageComposer(
                    value = state.input,
                    enabled = !state.isBusy,
                    isOffline = state.isOffline,
                    replyingTo = state.replyingTo,
                    editing = state.editing,
                    currentAttachment = state.currentAttachment,
                    isUploadingAttachment = state.isUploadingAttachment,
                    onValueChange = { onIntent(Intent.ChangeInput(it)) },
                    onSend = {
                        if (state.editing != null) {
                            onIntent(Intent.ConfirmEdit)
                        } else {
                            onIntent(Intent.Send)
                        }
                    },
                    onCancelReply = { onIntent(Intent.CancelReply) },
                    onCancelEdit = { onIntent(Intent.CancelEdit) },
                    onAttach = { showAttachmentPicker = true },
                    onCancelUpload = { onIntent(Intent.CancelCurrentUpload) }
                )
            }
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
                            listState.animateScrollToItem(events.size - 1)
                        }
                        onIntent(Intent.MarkRoomRead(room.id))
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (events.isEmpty() && !state.isBusy) {
                EmptyRoomView()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    reverseLayout = false
                ) {
                    // Load more button
                    if (!state.hitStart) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = { onIntent(Intent.PaginateBack) },
                                    enabled = !state.isPaginatingBack && !state.isOffline
                                ) {
                                    if (state.isPaginatingBack) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (state.isPaginatingBack) "Loading..."
                                        else "Load earlier messages"
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            Box(
                                modifier = Modifier
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
                    }

                    // Messages with date headers
                    var lastDate: String? = null
                    itemsIndexed(
                        items = events,
                        key = { _, event -> event.itemId }
                    ) { index, event ->
                        val eventDate = formatDate(event.timestamp)

                        // Date separator
                        if (eventDate != lastDate) {
                            DateHeader(eventDate)
                            lastDate = eventDate
                        }

                        // Message bubble
                        val previousEvent = if (index > 0) events[index - 1] else null
                        val grouped = previousEvent?.sender == event.sender &&
                                (event.timestamp - (previousEvent?.timestamp ?: 0)) < 60000

                        MessageBubble(
                            isMine = (event.sender == state.myUserId),
                            body = event.body,
                            sender = event.sender,
                            timestamp = event.timestamp,
                            grouped = grouped,
                            reactions = state.myReactions[event.eventId].orEmpty(),
                            onLongPress = { actionTarget = event },
                            onReact = { emoji ->
                                onIntent(Intent.React(event, emoji))
                            }
                        )

                        if (!grouped || index == events.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }

                    // Load more at bottom (for forward pagination)
                    if (state.isPaginatingForward) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }

            // Download progress overlay
            if (state.downloadProgress.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            state.downloadProgress.forEach { (uri, progress) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Downloading... ${(progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Attachment picker
    if (showAttachmentPicker) {
        AttachmentPicker(
            onAttachmentSelected = { attachment ->
                onIntent(Intent.AttachFile(
                    path = attachment.path,
                    mimeType = attachment.mimeType,
                    fileName = attachment.fileName
                ))
                showAttachmentPicker = false
            },
            onDismiss = { showAttachmentPicker = false }
        )
    }

    // Message action sheet
    actionTarget?.let { target ->
        MessageActionSheet(
            event = target,
            isMine = (target.sender == state.myUserId),
            onDismiss = { actionTarget = null },
            onReply = {
                onIntent(Intent.StartReply(target))
                actionTarget = null
            },
            onEdit = {
                onIntent(Intent.StartEdit(target))
                actionTarget = null
            },
            onDelete = {
                onIntent(Intent.DeleteMessage(target))
                actionTarget = null
            },
            onReact = { emoji ->
                onIntent(Intent.React(target, emoji))
            },
            onMarkReadHere = {
                onIntent(Intent.MarkReadHere(target))
                actionTarget = null
            }
        )
    }

    // Room actions dropdown
    DropdownMenu(
        expanded = showActions,
        onDismissRequest = { showActions = false }
    ) {
        DropdownMenuItem(
            text = { Text("Room settings") },
            onClick = {
                showActions = false
                // Navigate to room settings
            },
            leadingIcon = { Icon(Icons.Default.Settings, null) }
        )
        DropdownMenuItem(
            text = { Text("Media & files") },
            onClick = {
                showActions = false
                // Navigate to media gallery
            },
            leadingIcon = { Icon(Icons.Default.PermMedia, null) }
        )
        DropdownMenuItem(
            text = { Text("Search in room") },
            onClick = {
                showActions = false
                // Open search
            },
            leadingIcon = { Icon(Icons.Default.Search, null) }
        )
        DropdownMenuItem(
            text = { Text("Clear cache") },
            onClick = {
                showActions = false
                onIntent(Intent.ShowMediaCacheInfo)
            },
            leadingIcon = { Icon(Icons.Default.CleaningServices, null) }
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