package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.controller.RoomInfoUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomInfoScreen(
    state: RoomInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onSaveName: suspend () -> Boolean,
    onSaveTopic: suspend () -> Boolean,
    onToggleFavourite: suspend () -> Boolean,
    onToggleLowPriority: suspend () -> Boolean,
    onLeave: suspend () -> Boolean,
    onLeaveSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLeaveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading && state.profile == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Room header
                item {
                    RoomHeader(state)
                }

                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Room Priority",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = state.isFavourite,
                                    onClick = {
                                        scope.launch {
                                            val success = onToggleFavourite()
                                            if (!success) {
                                                snackbarHostState.showSnackbar("Failed to update favourite")
                                            }
                                        }
                                    },
                                    label = { Text("Favourite") },
                                    leadingIcon = {
                                        Icon(
                                            if (state.isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    enabled = !state.isSaving,
                                    modifier = Modifier.weight(1f)
                                )

                                FilterChip(
                                    selected = state.isLowPriority,
                                    onClick = {
                                        scope.launch {
                                            val success = onToggleLowPriority()
                                            if (!success) {
                                                snackbarHostState.showSnackbar("Failed to update priority")
                                            }
                                        }
                                    },
                                    label = { Text("Low Priority") },
                                    leadingIcon = {
                                        Icon(
                                            if (state.isLowPriority) Icons.Default.ArrowDownward else Icons.Default.Remove,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    enabled = !state.isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Edit name
                item {
                    EditableField(
                        label = "Room name",
                        value = state.editedName,
                        onValueChange = onNameChange,
                        onSave = {
                            scope.launch {
                                val success = onSaveName()
                                if (!success) {
                                    snackbarHostState.showSnackbar("Failed to update name")
                                }
                            }
                        },
                        isSaving = state.isSaving
                    )
                }

                // Edit topic
                item {
                    EditableField(
                        label = "Topic",
                        value = state.editedTopic,
                        onValueChange = onTopicChange,
                        onSave = {
                            scope.launch {
                                val success = onSaveTopic()
                                if (!success) {
                                    snackbarHostState.showSnackbar("Failed to update topic")
                                }
                            }
                        },
                        isSaving = state.isSaving,
                        singleLine = false
                    )
                }

                item { HorizontalDivider() }

                // Members header
                item {
                    Text(
                        text = "Members (${state.members.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Members list
                items(state.members) { member ->
                    MemberRow(member)
                }

                item { HorizontalDivider() }

                // Leave room button
                item {
                    Button(
                        onClick = { showLeaveDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.profile?.isDm == true) "End conversation" else "Leave room")
                    }
                }
            }
        }

        // Leave confirmation dialog
        if (showLeaveDialog) {
            AlertDialog(
                onDismissRequest = { showLeaveDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                title = { Text("Leave room?") },
                text = {
                    Text("You will no longer receive messages from this room. You can rejoin if invited again.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLeaveDialog = false
                            scope.launch {
                                val success = onLeave()
                                if (success) {
                                    onLeaveSuccess()
                                } else {
                                    snackbarHostState.showSnackbar("Failed to leave room")
                                }
                            }
                        }
                    ) {
                        Text("Leave", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Error handling
        LaunchedEffect(state.error) {
            state.error?.let {
                snackbarHostState.showSnackbar(it)
            }
        }
    }
}

@Composable
private fun RoomHeader(state: RoomInfoUiState) {
    val profile = state.profile ?: return

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (profile.isDm) Icons.Default.Person else Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (profile.isEncrypted) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (state.isFavourite) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favourite",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Text(
                    text = "${profile.memberCount} members",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditableField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
    singleLine: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            enabled = !isSaving
        )

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Save")
            }
        }
    }
}

@Composable
private fun MemberRow(member: MemberSummary) {
    ListItem(
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (member.isMe)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = if (member.isMe)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = member.displayName ?: member.userId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (member.displayName != null) {
                Text(
                    text = member.userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            if (member.isMe) {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}