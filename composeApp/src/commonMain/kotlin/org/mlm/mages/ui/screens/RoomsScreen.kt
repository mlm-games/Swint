package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.RoomSummary
import org.mlm.mages.ui.RoomsUiState
import org.mlm.mages.ui.components.EmptyStateView
import org.mlm.mages.ui.components.ShimmerLoadingList

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
                        IconButton(enabled = !state.isBusy, onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = onOpenSecurity) { Icon(Icons.Default.Security, "Security") }
                        IconButton(onClick = onOpenDiscover) { Icon(Icons.Default.Search, "Discover") }
                    }
                )
                state.offlineBanner?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp))
                    }
                } ?: state.syncBanner?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp))
                    }
                }

                OutlinedTextField(
                    value = state.roomSearchQuery,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search rooms...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
        if (state.isBusy && state.rooms.isEmpty()) {
            ShimmerLoadingList(Modifier.padding(innerPadding))
        } else if (filtered.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.MeetingRoom,
                title = "No rooms found",
                subtitle = if (state.roomSearchQuery.isBlank()) "Join a room to start chatting" else "No rooms match \"${state.roomSearchQuery}\"",
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filtered.reversed(), key = { it.id }) { room ->
                    RoomCard(room = room, unreadCount = state.unread[room.id] ?: 0) { onOpen(room) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomCard(room: RoomSummary, unreadCount: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (unreadCount > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            // Avatar gradient box
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(room.name.take(2).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                Text(room.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (unreadCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text(if (unreadCount > 99) "99+" else unreadCount.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}