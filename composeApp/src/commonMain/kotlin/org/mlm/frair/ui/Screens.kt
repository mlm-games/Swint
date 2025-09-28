package org.mlm.frair.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.mlm.frair.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import androidx.compose.ui.draw.alpha


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RootScaffold(
    state: AppState,
    onIntent: (Intent) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val s = state.screen) {
                            is Screen.Login -> "Frair"
                            is Screen.Rooms -> "Rooms"
                            is Screen.Room -> s.room.name
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (state.screen is Screen.Room) {
                        TextButton(onClick = { onIntent(Intent.Back) }) { Text("Back") }
                    }
                },
                actions = {
                    when (state.screen) {
                        is Screen.Rooms -> TextButton(
                            enabled = !state.isBusy,
                            onClick = { onIntent(Intent.RefreshRooms) }
                        ) { Text(if (state.isBusy) "…" else "Refresh") }

                        is Screen.Room -> TextButton(
                            enabled = !state.isBusy,
                            onClick = { onIntent(Intent.SyncNow) }
                        ) { Text(if (state.isBusy) "…" else "Sync") }

                        else -> {}
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val s = state.screen) {
            is Screen.Login -> LoginScreen(state, padding, onIntent)
            is Screen.Rooms -> RoomsScreen(state, padding, onIntent)
            is Screen.Room -> RoomScreen(state, padding, onIntent)
        }
    }
}

/* ---------------- Login ---------------- */

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
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
            LabeledField("Password", state.pass, {onIntent(Intent.ChangePass(it)) }, isPassword = true)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onIntent(Intent.SubmitLogin) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.isBusy) "Signing in…" else "Sign in") }
        }
    }
}

/* ---------------- Rooms (with unread badges) ---------------- */

@Composable
fun RoomsScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(state.rooms, query) {
        if (query.isBlank()) state.rooms
        else state.rooms.filter { it.name.contains(query, true) || it.id.contains(query, true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search rooms") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty() && !state.isBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No rooms")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { r ->
                    val unread = state.unread[r.id] ?: 0
                    RoomRow(
                        name = r.name,
                        id = r.id,
                        unread = unread,
                        onClick = { onIntent(Intent.OpenRoom(r)) },
                        onLong = { /* future room actions */ }
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
            Text(id, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.75f))
        },
        leadingContent = { Avatar(initials = name.take(2).uppercase()) },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    )
}

/* ---------------- Room (actions, reply/edit, jump-to-bottom) ---------------- */

@OptIn(ExperimentalTime::class)
@Composable
fun RoomScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    val room = (state.screen as Screen.Room).room
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Sorted ascending by timestamp
    val events = remember(state.events) { state.events.sortedBy { it.timestamp } }

    // Show “jump to bottom” when scrolled away
    val showJump by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            events.size - lastVisible > 8
        }
    }

    var actionTarget by remember { mutableStateOf<MessageEvent?>(null) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {

            if (state.hitStart) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Start of history") })
                }

                OutlinedButton(
                    onClick = { onIntent(Intent.PaginateBack) },
                    enabled = !state.isPaginatingBack
                ) { Text(if (state.isPaginatingBack) "Loading…" else "Load older") }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                var lastDay: LocalDate? = null
                var lastSender: String? = null
                itemsIndexed(events, key = { _, e -> e.eventId }) { _, ev ->
                    val day = Instant.fromEpochMilliseconds(ev.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                    if (day != lastDay) {
                        lastDay = day
                        DaySeparator(day)
                    }
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
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Reply preview / edit banner
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
                enabled = !state.isBusy,
                hint = if (state.replyingTo != null) "Reply…" else if (state.editing != null) "Edit message…" else "Message",
                onValueChange = { onIntent(Intent.ChangeInput(it)) },
                onSend = {
                    if (state.editing != null) onIntent(Intent.ConfirmEdit) else onIntent(Intent.Send)
                }
            )

            // Typing indicator hook
            TypingIndicator(names = state.typing[room.id].orEmpty().toList())
        }

        if (showJump) {
            ExtendedFloatingActionButton(
                content = { Text("Jump to bottom") },
                onClick = {
                    scope.launch { listState.animateScrollToItem(events.lastIndex.coerceAtLeast(0)) }
                    onIntent(Intent.MarkRoomRead(room.id))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            )
        }

        ExtendedFloatingActionButton(
            text = { Text(if (state.isPaginatingForward) "Loading…" else "Load newer") },
            onClick = { onIntent(Intent.PaginateForward) },
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            expanded = true,
            icon = {},
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    // Actions sheet
    if (actionTarget != null) {
        MessageActionSheet(
            event = actionTarget!!,
            isMine = actionTarget!!.sender == state.user,
            onDismiss = { actionTarget = null },
            onCopy = { /* copy already handled inside sheet */ },
            onReply = { onIntent(Intent.StartReply(actionTarget!!)); actionTarget = null },
            onEdit  = { onIntent(Intent.StartEdit(actionTarget!!)); actionTarget = null },
            onDelete = { onIntent(Intent.DeleteMessage(actionTarget!!)); actionTarget = null },
            onReact = { emoji -> onIntent(Intent.React(actionTarget!!, emoji)) }
        )
    }
}