package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.RoomSummary
import org.mlm.mages.ui.RoomsUiState
import org.mlm.mages.ui.components.common.RoomListItem
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.ShimmerList
import org.mlm.mages.ui.theme.Spacing

enum class RoomSection { Favourites, Normal, LowPriority }

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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Group rooms by section, Matrix rooms are already sorted via sdk in reversed order (by recency from bottom)
    val groupedRooms = remember(state.rooms, state.roomSearchQuery, state.unreadOnly, state.unread, state.favourites, state.lowPriority) {
        var list = state.rooms

        val q = state.roomSearchQuery.trim()
        if (q.isNotBlank()) {
            list = list.filter {
                it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            }
        }

        if (state.unreadOnly) {
            list = list.filter { (state.unread[it.id] ?: 0) > 0 }
        }

        val favourites = list.filter { state.favourites.contains(it.id) }
        val lowPriority = list.filter { state.lowPriority.contains(it.id) }
        val normal = list.filter { !state.favourites.contains(it.id) && !state.lowPriority.contains(it.id) }

        mapOf(
            RoomSection.Favourites to favourites,
            RoomSection.Normal to normal,
            RoomSection.LowPriority to lowPriority
        )
    }

    val hasAnyRooms = groupedRooms.values.any { it.isNotEmpty() }

    // Scroll to top when first room changes (new unread activity)
    val firstFavouriteId = groupedRooms[RoomSection.Favourites]?.firstOrNull()?.id
    val firstNormalId = groupedRooms[RoomSection.Normal]?.firstOrNull()?.id
    LaunchedEffect(firstFavouriteId, firstNormalId) {
        if ((firstFavouriteId != null || firstNormalId != null) && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }

    // Show FAB when not at top and there's unread activity
    val showScrollToTopFab by remember(listState, groupedRooms, state.unread) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 2 &&
                    (groupedRooms[RoomSection.Favourites]?.firstOrNull()?.let { (state.unread[it.id] ?: 0) > 0 } == true ||
                            groupedRooms[RoomSection.Normal]?.firstOrNull()?.let { (state.unread[it.id] ?: 0) > 0 } == true)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Rooms", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = onRefresh, enabled = !state.isLoading) {
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

                if (state.isLoading && state.rooms.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollToTopFab,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Scroll to top")
                    Spacer(Modifier.width(8.dp))
                    Text("New activity")
                }
            }
        }
    ) { innerPadding ->
        when {
            state.isLoading && state.rooms.isEmpty() -> {
                ShimmerList(modifier = Modifier.fillMaxSize().padding(innerPadding))
            }
            !hasAnyRooms -> {
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
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Favourites section
                    val favourites = groupedRooms[RoomSection.Favourites] ?: emptyList()
                    if (favourites.isNotEmpty()) {
                        item(key = "header_favourites") {
                            SectionHeader(
                                icon = Icons.Default.Star,
                                title = "Favourites",
                                count = favourites.size
                            )
                        }
                        items(favourites, key = { "fav_${it.id}" }) { room ->
                            RoomListItem(
                                room = room,
                                unreadCount = state.unread[room.id] ?: 0,
                                onClick = { onOpen(room) },
                                showFavouriteIcon = true
                            )
                        }
                    }

                    // Normal rooms section
                    val normal = groupedRooms[RoomSection.Normal] ?: emptyList()
                    if (normal.isNotEmpty()) {
                        if (favourites.isNotEmpty()) {
                            item(key = "header_rooms") {
                                SectionHeader(
                                    icon = Icons.Default.ChatBubble,
                                    title = "Rooms",
                                    count = normal.size
                                )
                            }
                        }
                        items(normal, key = { "room_${it.id}" }) { room ->
                            RoomListItem(
                                room = room,
                                unreadCount = state.unread[room.id] ?: 0,
                                onClick = { onOpen(room) }
                            )
                        }
                    }

                    // Low priority section
                    val lowPriority = groupedRooms[RoomSection.LowPriority] ?: emptyList()
                    if (lowPriority.isNotEmpty()) {
                        item(key = "header_low_priority") {
                            SectionHeader(
                                icon = Icons.Default.ArrowDownward,
                                title = "Low Priority",
                                count = lowPriority.size
                            )
                        }
                        items(lowPriority, key = { "low_${it.id}" }) { room ->
                            RoomListItem(
                                room = room,
                                unreadCount = state.unread[room.id] ?: 0,
                                onClick = { onOpen(room) },
                                modifier = Modifier.alpha(0.6F)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}