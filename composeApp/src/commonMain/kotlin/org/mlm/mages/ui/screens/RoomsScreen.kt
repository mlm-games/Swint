package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.RoomSummary
import org.mlm.mages.ui.RoomsUiState
import org.mlm.mages.ui.components.common.RoomListItem
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.ShimmerList
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    state: RoomsUiState,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (RoomSummary) -> Unit,
    onOpenSecurity: () -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenInvites: () -> Unit,
    onOpenCreateRoom: () -> Unit,
    onOpenSpaces: () -> Unit,
) {
    val filtered = remember(state.rooms, state.roomSearchQuery, state.unreadOnly, state.unread) {
        val q = state.roomSearchQuery.trim()
        var list = if (q.isBlank()) state.rooms
        else state.rooms.filter {
            it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
        }
        if (state.unreadOnly) {
            list = list.filter { (state.unread[it.id] ?: 0) > 0 }
        }
        list
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Rooms", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(enabled = !state.isBusy, onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        IconButton(onClick = onOpenSpaces) { Icon(Icons.Default.Workspaces, "Spaces") }
                        IconButton(onClick = onOpenSecurity) {
                            Icon(Icons.Default.Security, "Security")
                        }
                        IconButton(onClick = onOpenDiscover) {
                            Icon(Icons.Default.NewLabel, "Discover")
                        }
                        IconButton(onClick = onOpenInvites) {
                            Icon(Icons.Default.Mail, "Invites")
                        }
                        IconButton(onClick = onOpenCreateRoom) {
                            Icon(Icons.Default.Add, "New Room")
                        }
                    }
                )

                // Connection banners
                state.offlineBanner?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = Spacing.lg)
                        )
                    }
                } ?: state.syncBanner?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = Spacing.lg)
                        )
                    }
                }

                // Search
                OutlinedTextField(
                    value = state.roomSearchQuery,
                    onValueChange = onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    placeholder = { Text("Search rooms...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )

                // Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.Start
                ) {
                    FilterChip(
                        selected = state.unreadOnly,
                        onClick = onToggleUnreadOnly,
                        label = { Text("Unread only") }
                    )
                }
            }
        }
    ) { innerPadding ->
        when {
            state.isBusy && state.rooms.isEmpty() -> {
                ShimmerList(modifier = Modifier.padding(innerPadding))
            }
            filtered.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.MeetingRoom,
                    title = "No rooms found",
                    subtitle = if (state.roomSearchQuery.isBlank())
                        "Join a room to start chatting"
                    else
                        "No rooms match \"${state.roomSearchQuery}\"",
                    modifier = Modifier.padding(innerPadding)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered.reversed(), key = { it.id }) { room ->
                        RoomListItem(
                            room = room,
                            unreadCount = state.unread[room.id] ?: 0,
                            onClick = { onOpen(room) }
                        )
                    }
                }
            }
        }
    }
}