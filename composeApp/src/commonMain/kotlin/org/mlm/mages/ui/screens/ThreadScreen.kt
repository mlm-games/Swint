package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.ThreadUi
import org.mlm.mages.ui.base.SnackbarController
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.formatDisplayName
import org.mlm.mages.ui.components.message.MessageBubble
import org.mlm.mages.ui.components.sheets.MessageActionSheet
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.viewmodel.ThreadViewModel

@Composable
fun ThreadRoute(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
    snackbarController: SnackbarController
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ThreadViewModel.Event.ShowError -> snackbarController.showError(event.message)
                is ThreadViewModel.Event.ShowSuccess -> snackbarController.show(event.message)
            }
        }
    }

    ThreadScreen(
        state = state,
        myUserId = viewModel.myUserId,
        onReact = viewModel::react,
        onBack = onBack,
        onLoadMore = viewModel::loadMore,
        onSendThread = { text, replyToId ->
            viewModel.sendMessage(text, replyToId)
        },
        onEdit = viewModel::startEdit,
        onDelete = { ev -> viewModel.delete(ev) },
        onRetry = { ev -> viewModel.retry(ev) },
        snackbarController = snackbarController
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUi,
    myUserId: String?,
    onReact: (MessageEvent, String) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onSendThread: suspend (String, String?) -> Boolean,
    onEdit: (MessageEvent) -> Unit,
    onDelete: suspend (MessageEvent) -> Unit,
    onRetry: suspend (MessageEvent) -> Unit,
    snackbarController: SnackbarController
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var replyTo: MessageEvent? by remember { mutableStateOf(null) }
    var sheetEvent by remember { mutableStateOf<MessageEvent?>(null) }
    val listState = rememberLazyListState()

    val isNearBottom by remember(listState, state.messages) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.messages.isNotEmpty() && lastVisible >= state.messages.lastIndex - 2
        }
    }

    val rootMessage = state.messages.firstOrNull()
    val threadReplies = if (state.messages.size > 1) state.messages.drop(1) else emptyList()

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Forum,
                                        null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(Spacing.md))
                            Column {
                                Text("Thread", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${state.messages.size} ${if (state.messages.size == 1) "message" else "messages"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        bottomBar = {
            ThreadComposer(
                input = input,
                onInputChange = { input = it },
                replyTo = replyTo,
                onCancelReply = { replyTo = null },
                onSend = {
                    scope.launch {
                        val ok = onSendThread(input.trim(), replyTo?.eventId)
                        if (ok) {
                            input = ""
                            replyTo = null
                            if (state.messages.isNotEmpty()) {
                                listState.animateScrollToItem(state.messages.lastIndex + 1)
                            }
                        } else {
                            snackbarController.showError("Failed to send message")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isNearBottom && state.messages.size > 5,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(state.messages.lastIndex.coerceAtLeast(0) + 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Latest")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(Spacing.lg),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (state.messages.isEmpty() && !state.isLoading) {
                EmptyThreadView()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.sm)
                ) {
                    if (state.nextBatch != null) {
                        item(key = "load_more") {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.lg),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(onClick = onLoadMore, enabled = !state.isLoading) {
                                    if (state.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(Spacing.sm))
                                    }
                                    Text("Load earlier messages")
                                }
                            }
                        }
                    }

                    rootMessage?.let { root ->
                        item(key = "root_${root.itemId}") {
                            ThreadRootMessage(
                                event = root,
                                isMine = root.sender == myUserId,
                                reactionChips = state.reactionChips[root.eventId] ?: emptyList(),
                                onReact = { emoji -> onReact(root, emoji) },
                                onReply = { replyTo = root },
                                onLongPress = { sheetEvent = root }
                            )
                        }
                        if (threadReplies.isNotEmpty()) {
                            item(key = "divider") {
                                ThreadDivider(replyCount = threadReplies.size)
                            }
                        }
                    }

                    items(items = threadReplies, key = { it.itemId }) { event ->
                        val prevEvent = threadReplies.getOrNull(threadReplies.indexOf(event) - 1)
                        val shouldGroup = prevEvent != null &&
                                prevEvent.sender == event.sender &&
                                (event.timestamp - prevEvent.timestamp) < 300_000

                        ThreadReplyMessage(
                            event = event,
                            isMine = event.sender == myUserId,
                            reactionChips = state.reactionChips[event.eventId] ?: emptyList(),
                            onReact = { emoji -> onReact(event, emoji) },
                            onLongPress = { sheetEvent = event },
                            grouped = shouldGroup
                        )
                    }
                }
            }
        }
    }

    if (sheetEvent != null) {
        val ev = sheetEvent!!
        val isMine = ev.sender == myUserId
        MessageActionSheet(
            event = ev,
            isMine = isMine,
            onDismiss = { sheetEvent = null },
            onReply = { replyTo = ev; sheetEvent = null },
            onEdit = {
                onEdit(ev)
                sheetEvent = null
            },
            onDelete = {
                scope.launch {
                    onDelete(ev)
                    sheetEvent = null
                }
            },
            onReact = { emoji -> onReact(ev, emoji) },
            onMarkReadHere = { sheetEvent = null },
            onRetry = if (isMine && ev.sendState == SendState.Failed) {
                {
                    scope.launch {
                        onRetry(ev)
                        sheetEvent = null
                    }
                }
            } else null
        )
    }
}

@Composable
private fun ThreadRootMessage(
    event: MessageEvent,
    isMine: Boolean,
    reactionChips: List<ReactionChip>,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        onClick = onLongPress
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "Thread started",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(Spacing.md))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = event.sender,
                    size = 36.dp,
                    containerColor = if (isMine)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        formatDisplayName(event.sender),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        formatTime(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Surface(
                color = if (isMine)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    event.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Spacing.md),
                    color = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            if (reactionChips.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                ThreadReactionChipsRow(chips = reactionChips, onReact = onReact)
            }

            Spacer(Modifier.height(Spacing.sm))
            Surface(
                onClick = onReply,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Reply",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadDivider(replyCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(horizontal = Spacing.md)
        ) {
            Text(
                "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ThreadReplyMessage(
    event: MessageEvent,
    isMine: Boolean,
    reactionChips: List<ReactionChip>,
    onReact: (String) -> Unit,
    onLongPress: () -> Unit,
    grouped: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = if (grouped) 2.dp else 4.dp)
    ) {
        Box(modifier = Modifier.width(24.dp).padding(top = if (grouped) 4.dp else Spacing.lg)) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(if (grouped) 24.dp else 40.dp)
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp))
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            MessageBubble(
                isMine = isMine,
                body = event.body,
                sender = if (grouped) null else formatDisplayName(event.sender),
                timestamp = event.timestamp,
                grouped = grouped,
                reactionChips = reactionChips,
                eventId = event.eventId,
                onReact = onReact,
                onLongPress = onLongPress,
                replyPreview = event.replyToBody,
                replySender = event.replyToSender?.let { formatDisplayName(it) },
                sendState = event.sendState
            )
        }
    }
}

@Composable
private fun ThreadReactionChipsRow(
    chips: List<ReactionChip>,
    onReact: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        chips.take(6).forEach { chip ->
            Surface(
                onClick = { onReact(chip.key) },
                color = if (chip.mine)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(chip.key, style = MaterialTheme.typography.bodyMedium)
                    if (chip.count > 1) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${chip.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadComposer(
    input: String,
    onInputChange: (String) -> Unit,
    replyTo: MessageEvent?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column {
            AnimatedVisibility(visible = replyTo != null) {
                replyTo?.let { event ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Replying to ${formatDisplayName(event.sender)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    event.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = onCancelReply, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    "Cancel reply",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (replyTo != null) "Reply in thread…" else "Add to thread…")
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
                Spacer(Modifier.width(Spacing.sm))
                FilledIconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun EmptyThreadView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Spacing.xxl)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Forum,
                        null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xl))
            Text(
                "No messages in thread",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "Start the conversation by sending a message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}