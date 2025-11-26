package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.ui.SpaceSettingsUiState
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSettingsScreen(
    state: SpaceSettingsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAddChild: (roomId: String, suggested: Boolean) -> Unit,
    onRemoveChild: (childRoomId: String) -> Unit,
    onInviteUser: (userId: String) -> Unit
) {
    var showAddRoomSheet by remember { mutableStateOf(false) }
    var showInviteSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Space Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Loading indicator
            AnimatedVisibility(visible = state.isLoading || state.isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.md)
            ) {
                state.space?.let { space ->
                    item {
                        SpaceInfoHeader(space)
                    }
                }

                item {
                    SectionTitle("Actions")
                }

                item {
                    ListItem(
                        headlineContent = { Text("Add rooms") },
                        supportingContent = { Text("Add existing rooms to this space") },
                        leadingContent = {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { showAddRoomSheet = true }
                    )
                }

                item {
                    ListItem(
                        headlineContent = { Text("Invite users") },
                        supportingContent = { Text("Invite users to this space") },
                        leadingContent = {
                            Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { showInviteSheet = true }
                    )
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md)) }

                item {
                    SectionTitle("Rooms in this space (${state.children.size})")
                }

                if (state.children.isEmpty()) {
                    item {
                        Text(
                            "No rooms in this space yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                } else {
                    items(state.children, key = { it.roomId }) { child ->
                        ChildRoomItem(
                            child = child,
                            onRemove = { onRemoveChild(child.roomId) },
                            isRemoving = state.isSaving
                        )
                    }
                }
            }
        }
    }

    if (showAddRoomSheet) {
        AddRoomSheet(
            availableRooms = state.availableRooms,
            onAdd = { roomId, suggested ->
                onAddChild(roomId, suggested)
                showAddRoomSheet = false
            },
            onDismiss = { showAddRoomSheet = false }
        )
    }

    if (showInviteSheet) {
        InviteUserSheet(
            onInvite = { userId ->
                onInviteUser(userId)
                showInviteSheet = false
            },
            onDismiss = { showInviteSheet = false }
        )
    }
}

@Composable
private fun SpaceInfoHeader(space: org.mlm.mages.matrix.SpaceInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Workspaces,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    space.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${space.memberCount} members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    )
}

@Composable
private fun ChildRoomItem(
    child: SpaceChildInfo,
    onRemove: () -> Unit,
    isRemoving: Boolean
) {
    ListItem(
        headlineContent = {
            Text(
                child.name ?: child.alias ?: child.roomId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = child.topic?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Surface(
                color = if (child.isSpace)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(40.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (child.isSpace) Icons.Default.Workspaces else Icons.Default.Tag,
                        null
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove, enabled = !isRemoving) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRoomSheet(
    availableRooms: List<RoomSummary>,
    onAdd: (roomId: String, suggested: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRoom by remember { mutableStateOf<RoomSummary?>(null) }
    var suggested by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text("Add room to space", style = MaterialTheme.typography.titleMedium)

            if (availableRooms.isEmpty()) {
                Text(
                    "All your rooms are already in this space",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.lg)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(availableRooms, key = { it.id }) { room ->
                        ListItem(
                            headlineContent = { Text(room.name) },
                            supportingContent = { Text(room.id, maxLines = 1) },
                            leadingContent = {
                                RadioButton(
                                    selected = selectedRoom?.id == room.id,
                                    onClick = { selectedRoom = room }
                                )
                            },
                            modifier = Modifier.clickable { selectedRoom = room }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = suggested,
                        onCheckedChange = { suggested = it }
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Mark as suggested")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = { selectedRoom?.let { onAdd(it.id, suggested) } },
                    enabled = selectedRoom != null
                ) {
                    Text("Add")
                }
            }

            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteUserSheet(
    onInvite: (userId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var userId by remember { mutableStateOf("") }
    val isValid = userId.startsWith("@") && ":" in userId && userId.length > 3

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text("Invite user to space", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                label = { Text("User ID") },
                placeholder = { Text("@user:server.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = userId.isNotBlank() && !isValid
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = { onInvite(userId.trim()) },
                    enabled = isValid
                ) {
                    Text("Invite")
                }
            }

            Spacer(Modifier.height(Spacing.lg))
        }
    }
}