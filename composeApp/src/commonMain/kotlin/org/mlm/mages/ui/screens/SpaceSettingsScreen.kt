package org.mlm.mages.ui.screens

import androidx.compose.animation.*
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
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SpaceSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSettingsScreen(
    viewModel: SpaceSettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
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
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
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
                // Space info header
                state.space?.let { space ->
                    item(key = "header") {
                        SpaceInfoHeader(space = space)
                    }
                }

                // Actions
                item(key = "actions_title") {
                    SectionTitle("Actions")
                }

                item(key = "action_add_room") {
                    ListItem(
                        headlineContent = { Text("Add rooms") },
                        supportingContent = { Text("Add existing rooms to this space") },
                        leadingContent = {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.showAddRoomDialog()
                        }
                    )
                }

                item(key = "action_invite") {
                    ListItem(
                        headlineContent = { Text("Invite users") },
                        supportingContent = { Text("Invite users to this space") },
                        leadingContent = {
                            Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.showInviteDialog()
                        }
                    )
                }

                item(key = "divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                }

                // Children
                item(key = "children_title") {
                    SectionTitle("Rooms in this space (${state.children.size})")
                }

                if (state.children.isEmpty() && !state.isLoading) {
                    item(key = "empty") {
                        Text(
                            "No rooms in this space yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }

                items(state.children, key = { it.roomId }) { child ->
                    ChildRoomItem(
                        child = child,
                        onRemove = { viewModel.removeChild(child.roomId) },
                        isRemoving = state.isSaving
                    )
                }
            }
        }
    }

    // Add room dialog
    if (state.showAddRoom) {
        AddRoomDialog(
            availableRooms = state.availableRooms,
            onAdd = { roomId, suggested -> viewModel.addChild(roomId, suggested) },
            onDismiss = viewModel::hideAddRoomDialog
        )
    }

    // Invite user dialog
    if (state.showInviteUser) {
        InviteUserToSpaceDialog(
            userId = state.inviteUserId,
            onUserIdChange = viewModel::setInviteUserId,
            onInvite = viewModel::inviteUser,
            onDismiss = viewModel::hideInviteDialog,
            isSaving = state.isSaving
        )
    }
}

@Composable
private fun SpaceInfoHeader(space: SpaceInfo) {
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

@Composable
private fun AddRoomDialog(
    availableRooms: List<RoomSummary>,
    onAdd: (roomId: String, suggested: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRoom by remember { mutableStateOf<RoomSummary?>(null) }
    var suggested by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add room to space") },
        text = {
            Column {
                if (availableRooms.isEmpty()) {
                    Text(
                        "All your rooms are already in this space",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        items(availableRooms, key = { it.id }) { room ->
                            ListItem(
                                headlineContent = { Text(room.name) },
                                supportingContent = {
                                    Text(room.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
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

                    Spacer(Modifier.height(Spacing.md))

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
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedRoom?.let { onAdd(it.id, suggested) } },
                enabled = selectedRoom != null
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InviteUserToSpaceDialog(
    userId: String,
    onUserIdChange: (String) -> Unit,
    onInvite: () -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean
) {
    val isValid = userId.startsWith("@") && ":" in userId && userId.length > 3

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.PersonAdd, null) },
        title = { Text("Invite user to space") },
        text = {
            OutlinedTextField(
                value = userId,
                onValueChange = onUserIdChange,
                label = { Text("User ID") },
                placeholder = { Text("@user:server.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving,
                isError = userId.isNotBlank() && !isValid
            )
        },
        confirmButton = {
            Button(
                onClick = onInvite,
                enabled = isValid && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text("Invite")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

