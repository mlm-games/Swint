// ui/screens/ThreadScreen.kt
package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.ui.components.MessageBubble
import org.mlm.mages.ui.components.formatTime
import org.mlm.mages.ui.controller.ThreadUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUi,
    myUserId: String?,
    reactionChips: (String) -> List<ReactionChip>,
    onReact: (MessageEvent, String) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onSendThread: suspend (String, String?) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var replyTo: MessageEvent? by remember { mutableStateOf(null) }
    val listState = rememberLazyListState()

    val isNearBottom by remember(listState, state.messages) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.messages.isNotEmpty() && lastVisible >= state.messages.lastIndex - 2
        }
    }

    // Get the root message (first message in thread)
    val rootMessage = state.messages.firstOrNull()
    val threadReplies = if (state.messages.size > 1) state.messages.drop(1) else emptyList()

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Forum,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Thread",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
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
                            // Scroll to bottom after sending
                            if (state.messages.isNotEmpty()) {
                                listState.animateScrollToItem(state.messages.lastIndex + 1) // +1 for root header
                            }
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
                    Spacer(Modifier.width(8.dp))
                    Text("Latest")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Loading indicator
            AnimatedVisibility(visible = state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Error message
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (state.messages.isEmpty() && !state.isLoading) {
                // Empty state
                EmptyThreadView()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    // Load more button
                    if (state.nextBatch != null) {
                        item(key = "load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    enabled = !state.isLoading
                                ) {
                                    if (state.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("Load earlier messages")
                                }
                            }
                        }
                    }

                    // Thread root message (pinned at top with special styling)
                    rootMessage?.let { root ->
                        item(key = "root_${root.itemId}") {
                            ThreadRootMessage(
                                event = root,
                                isMine = root.sender == myUserId,
                                reactionChips = reactionChips(root.eventId),
                                onReact = { emoji -> onReact(root, emoji) },
                                onReply = { replyTo = root }
                            )
                        }

                        // Divider between root and replies
                        if (threadReplies.isNotEmpty()) {
                            item(key = "divider") {
                                ThreadDivider(replyCount = threadReplies.size)
                            }
                        }
                    }

                    // Thread replies
                    items(
                        items = threadReplies,
                        key = { it.itemId }
                    ) { event ->
                        ThreadReplyMessage(
                            event = event,
                            isMine = event.sender == myUserId,
                            reactionChips = reactionChips(event.eventId),
                            onReact = { emoji -> onReact(event, emoji) },
                            onReply = { replyTo = event }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRootMessage(
    event: MessageEvent,
    isMine: Boolean,
    reactionChips: List<ReactionChip>,
    onReact: (String) -> Unit,
    onReply: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with "Thread started by"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thread started",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(12.dp))

            // Sender info
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            event.sender.substringAfter("@").take(2).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        event.sender.substringAfter("@").substringBefore(":"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        formatTime(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Message body
            Surface(
                color = if (isMine) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    event.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                    color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            // Reactions
            if (reactionChips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ReactionChipsRow(
                    chips = reactionChips,
                    onReact = onReact
                )
            }

            // Reply button
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onReply,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun ThreadReplyMessage(
    event: MessageEvent,
    isMine: Boolean,
    reactionChips: List<ReactionChip>,
    onReact: (String) -> Unit,
    onReply: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Thread line indicator
        Box(
            modifier = Modifier
                .width(24.dp)
                .padding(top = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(1.dp)
                    )
            )
        }

        // Message content
        Column(modifier = Modifier.weight(1f)) {
            MessageBubble(
                isMine = isMine,
                body = event.body,
                sender = event.sender.substringAfter("@").substringBefore(":"),
                timestamp = event.timestamp,
                grouped = false,
                reactionChips = reactionChips,
                eventId = event.eventId,
                onReact = onReact,
                onLongPress = onReply,
                replyPreview = event.replyToBody,
                replySender = event.replyToSender?.substringAfter("@")?.substringBefore(":")
            )
        }
    }
}

@Composable
private fun ReactionChipsRow(
    chips: List<ReactionChip>,
    onReact: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        chips.take(6).forEach { chip ->
            Surface(
                onClick = { onReact(chip.key) },
                color = if (chip.mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column {
            // Reply preview
            AnimatedVisibility(visible = replyTo != null) {
                replyTo?.let { event ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Replying to ${event.sender.substringAfter("@").substringBefore(":")}",
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
                            IconButton(
                                onClick = onCancelReply,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (replyTo != null) "Reply in thread…"
                            else "Add to thread…"
                        )
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
                Spacer(Modifier.width(8.dp))
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
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyThreadView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(80.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "No messages in thread",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Start the conversation by sending a message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}