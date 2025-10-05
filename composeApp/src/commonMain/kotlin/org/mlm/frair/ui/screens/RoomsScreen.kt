package org.mlm.frair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.frair.AppState
import org.mlm.frair.Intent
import org.mlm.frair.RoomSummary
import org.mlm.frair.ui.components.EmptyStateView
import org.mlm.frair.ui.components.ShimmerLoadingList

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = Modifier.padding(padding),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Rooms", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(
                            enabled = !state.isBusy,
                            onClick = { onIntent(Intent.RefreshRooms) }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh"
                            )
                        }
                        IconButton(onClick = { onIntent(Intent.OpenSecurity) }) {
                            Icon(Icons.Default.Security, "Security")
                        }
                        IconButton(onClick = { onIntent(Intent.Logout) }) {
                            Icon(Icons.Default.Logout, "Logout")
                        }
                    }
                )

                // Offline banner
                if (state.offlineBanner != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            state.offlineBanner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                        )
                    }
                } else if (state.syncBanner != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            state.syncBanner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                        )
                    }
                }

                // Search bar
                OutlinedTextField(
                    value = state.roomSearchQuery,
                    onValueChange = { onIntent(Intent.SetRoomSearch(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search rooms...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }
        }
    ) { innerPadding ->
        if (state.isBusy && state.rooms.isEmpty()) {
            ShimmerLoadingList(Modifier.padding(innerPadding))
        } else if (filtered.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.MeetingRoom,
                title = "No rooms found",
                subtitle = if (state.roomSearchQuery.isBlank())
                    "Join a room to start chatting"
                else
                    "No rooms match \"${state.roomSearchQuery}\"",
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filtered, key = { it.id }) { room ->
                    RoomCard(
                        room = room,
                        unreadCount = state.unread[room.id] ?: 0,
                        onClick = { onIntent(Intent.OpenRoom(room)) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomCard(
    room: RoomSummary,
    unreadCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unreadCount > 0)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with gradient background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = room.name.take(2).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = room.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (unreadCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}