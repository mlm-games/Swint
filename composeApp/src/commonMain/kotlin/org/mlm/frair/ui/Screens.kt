package org.mlm.frair.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.frair.*
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.ui.screens.SecurityScreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RootScaffold(state: AppState, onIntent: (Intent) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (val s = state.screen) {
                                is Screen.Login -> "Frair"
                                is Screen.Rooms -> "Rooms"
                                is Screen.Room -> s.room.name
                                is Screen.Security -> "Security"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Show sync banner if present
                        if (state.syncBanner != null) {
                            Text(
                                state.syncBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Show offline banner if offline
                        if (state.offlineBanner != null) {
                            Text(
                                state.offlineBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (state.syncBanner != null) {
                            Text(
                                state.syncBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (state.screen is Screen.Room || state.screen is Screen.Security) {
                        TextButton(onClick = { onIntent(Intent.Back) }) { Text("Back") }
                    }
                },
                actions = {
                    when (state.screen) {
                        is Screen.Rooms -> Row {
                            TextButton(
                                enabled = !state.isBusy,
                                onClick = { onIntent(Intent.RefreshRooms) }
                            ) {
                                Text(if (state.isBusy) "…" else "Refresh")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onIntent(Intent.OpenSecurity) }) { Text("Security") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onIntent(Intent.Logout) }) { Text("Log out") }
                        }
                        is Screen.Room -> TextButton(
                            enabled = !state.isBusy,
                            onClick = { onIntent(Intent.SyncNow) }
                        ) {
                            Text(if (state.isBusy) "…" else "Sync")
                        }
                        else -> {}
                    }
                }
            )
            if (state.isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                ) {}
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val s = state.screen) {
            is Screen.Login -> LoginScreen(state, padding, onIntent)
            is Screen.Rooms -> RoomsScreen(state, padding, onIntent)
            is Screen.Room -> RoomScreen(state, padding, onIntent)
            is Screen.Security -> SecurityScreen(state, padding, onIntent)
        }
    }
}

@Composable
fun RoomsScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    val filtered = remember(state.rooms, state.roomSearchQuery) {
        if (state.roomSearchQuery.isBlank()) {
            state.rooms
        } else {
            state.rooms.filter {
                it.name.contains(state.roomSearchQuery, true) ||
                        it.id.contains(state.roomSearchQuery, true)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        OutlinedTextField(
            value = state.roomSearchQuery,
            onValueChange = { onIntent(Intent.SetRoomSearch(it)) },
            label = { Text("Search rooms") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        if (state.isBusy && state.rooms.isEmpty()) {
            ListSkeleton()
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No rooms")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { r ->
                    RoomRow(
                        name = r.name,
                        id = r.id,
                        unread = state.unread[r.id] ?: 0,
                        onClick = { onIntent(Intent.OpenRoom(r)) },
                        onLong = { /* future */ }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun RoomRow(
    name: String,
    id: String,
    unread: Int,
    onClick: () -> Unit,
    onLong: () -> Unit
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                if (unread > 0) {
                    AssistChip(
                        onClick = onClick,
                        label = { Text(unread.coerceAtMost(999).toString()) }
                    )
                }
            }
        },
        supportingContent = {
            Text(
                id,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.75f)
            )
        },
        leadingContent = { Avatar(initials = name.take(2).uppercase()) },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    )
}

@Composable
fun RoomScreen(state: AppState, padding: PaddingValues, onIntent: (Intent) -> Unit) {
    val room = (state.screen as Screen.Room).room
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val events = remember(state.events) { state.events.sortedBy { it.timestamp } }
    val outbox = state.pendingByRoom[room.id].orEmpty()

    val showJump by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            events.size - lastVisible > 8
        }
    }

    var actionTarget by remember { mutableStateOf<MessageEvent?>(null) }
    var showRedact by remember { mutableStateOf(false) }
    var redactReason by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            // Show pagination state
            if (state.hitStart) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Start of history") }
                    )
                }
            }

            val canPaginateBack = !state.isPaginatingBack &&
                    !state.isOffline &&
                    state.connectionState == MatrixPort.ConnectionState.Connected &&
                    !state.hitStart

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = { onIntent(Intent.PaginateBack) },
                    enabled = canPaginateBack
                ) {
                    Text(
                        when {
                            state.isPaginatingBack -> "Loading…"
                            state.isOffline -> "Offline"
                            state.hitStart -> "No more"
                            else -> "Load older"
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                var lastSender: String? = null
                itemsIndexed(events, key = { _, e -> e.eventId }) { _, ev ->
                    val grouped = lastSender == ev.sender
                    lastSender = ev.sender

                    MessageBubble(
                        isMine = ev.sender == state.user,
                        body = ev.body,
                        sender = if (!grouped && ev.sender != state.user) ev.sender else null,
                        timestamp = ev.timestamp,
                        grouped = grouped,
                        onLongPress = { actionTarget = ev }
                    )
                    ReactionBar(emojis = state.myReactions[ev.eventId].orEmpty())
                    Spacer(Modifier.height(6.dp))
                }
            }

            OutboxChips(outbox)

            if (state.replyingTo != null || state.editing != null) {
                ActionBanner(
                    replyingTo = state.replyingTo,
                    editing = state.editing,
                    onCancelReply = { onIntent(Intent.CancelReply) },
                    onCancelEdit = { onIntent(Intent.CancelEdit) }
                )
            }

            MessageComposer(
                value = state.input,
                enabled = !state.isBusy && !state.isOffline,
                hint = when {
                    state.isOffline -> "Offline"
                    state.replyingTo != null -> "Reply…"
                    state.editing != null -> "Edit message…"
                    else -> "Message"
                },
                onValueChange = { onIntent(Intent.ChangeInput(it)) },
                onSend = {
                    if (!state.isOffline || state.editing == null) {
                        if (state.editing != null) onIntent(Intent.ConfirmEdit)
                        else onIntent(Intent.Send) //TODO: save any draft present locally
                    }
                }
            )

            TypingIndicator(names = state.typing[room.id].orEmpty().toList())
        }

        if (showJump) {
            ExtendedFloatingActionButton(
                content = { Text("Jump to bottom") },
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(events.lastIndex.coerceAtLeast(0))
                    }
                    onIntent(Intent.MarkRoomRead(room.id))
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            )
        }
    }

    if (showRedact && actionTarget != null) {
        AlertDialog(
            onDismissRequest = { showRedact = false },
            title = { Text("Delete message") },
            text = {
                Column {
                    Text("Optional reason:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = redactReason,
                        onValueChange = { redactReason = it },
                        singleLine = true,
                        placeholder = { Text("Reason (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(
                            Intent.DeleteMessage(
                                actionTarget!!,
                                reason = redactReason.ifBlank { null }
                            )
                        )
                        showRedact = false
                        redactReason = ""
                        actionTarget = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRedact = false
                        redactReason = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (actionTarget != null) {
        MessageActionSheet(
            event = actionTarget!!,
            isMine = actionTarget!!.sender == state.user,
            onDismiss = { actionTarget = null },
            onCopy = { /* clipboard handled */ },
            onReply = {
                onIntent(Intent.StartReply(actionTarget!!))
                actionTarget = null
            },
            onEdit = {
                onIntent(Intent.StartEdit(actionTarget!!))
                actionTarget = null
            },
            onDelete = { showRedact = true },
            onReact = { emoji -> onIntent(Intent.React(actionTarget!!, emoji)) },
            onMarkReadHere = {
                onIntent(Intent.MarkReadHere(actionTarget!!))
                actionTarget = null
            }
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
    )
}

@Composable
fun LoginScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Welcome to Frair", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            LabeledField("Homeserver", state.homeserver, { onIntent(Intent.ChangeHomeserver(it)) })
            Spacer(Modifier.height(8.dp))
            LabeledField("User", state.user, { onIntent(Intent.ChangeUser(it)) })
            Spacer(Modifier.height(8.dp))
            LabeledField("Password", state.pass, { onIntent(Intent.ChangePass(it)) }, isPassword = true)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onIntent(Intent.SubmitLogin) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isBusy) "Signing in…" else "Sign in")
            }
        }
    }
}