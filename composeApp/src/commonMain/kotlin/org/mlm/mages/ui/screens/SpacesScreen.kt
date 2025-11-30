package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.ShimmerList
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SpacesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(
    viewModel: SpacesViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spaces", fontWeight = FontWeight.SemiBold) },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::showCreateSpace,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(Spacing.sm))
                Text("Create Space")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                placeholder = { Text("Search spaces...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            when {
                state.isLoading && state.spaces.isEmpty() -> {
                    ShimmerList(modifier = Modifier.fillMaxSize())
                }
                state.filteredSpaces.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Workspaces,
                        title = if (state.searchQuery.isBlank()) "No spaces yet" else "No spaces found",
                        subtitle = if (state.searchQuery.isBlank())
                            "Create a space to organize your rooms"
                        else
                            "Try a different search term",
                        action = if (state.searchQuery.isBlank()) {
                            {
                                Button(onClick = viewModel::showCreateSpace) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text("Create Space")
                                }
                            }
                        } else null
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        items(state.filteredSpaces, key = { it.roomId }) { space ->
                            SpaceCard(
                                space = space,
                                onClick = { viewModel.openSpace(space) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Space Sheet
    if (state.showCreateSpace) {
        CreateSpaceSheet(
            name = state.createName,
            topic = state.createTopic,
            isPublic = state.createIsPublic,
            invitees = state.createInvitees,
            isCreating = state.isCreating,
            onNameChange = viewModel::setCreateName,
            onTopicChange = viewModel::setCreateTopic,
            onPublicChange = viewModel::setCreateIsPublic,
            onAddInvitee = viewModel::addCreateInvitee,
            onRemoveInvitee = viewModel::removeCreateInvitee,
            onCreate = viewModel::createSpace,
            onDismiss = viewModel::hideCreateSpace
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceCard(
    space: SpaceInfo,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
            // Space icon
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(56.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Workspaces,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.width(Spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        space.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (space.isEncrypted) {
                        Icon(
                            Icons.Default.Lock,
                            "Encrypted",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (space.isPublic) {
                        Icon(
                            Icons.Default.Public,
                            "Public",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                space.topic?.let { topic ->
                    Text(
                        topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(Spacing.xs))

                Text(
                    "${space.memberCount} members",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateSpaceSheet(
    name: String,
    topic: String,
    isPublic: Boolean,
    invitees: List<String>,
    isCreating: Boolean,
    onNameChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit,
    onAddInvitee: (String) -> Unit,
    onRemoveInvitee: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    var inviteeInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                "Create Space",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Space preview icon
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Workspaces,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Space name *") },
                placeholder = { Text("My Awesome Space") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isCreating
            )

            OutlinedTextField(
                value = topic,
                onValueChange = onTopicChange,
                label = { Text("Topic (optional)") },
                placeholder = { Text("What is this space about?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                enabled = !isCreating
            )

            // Public/Private toggle
            Card(
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
                    Icon(
                        if (isPublic) Icons.Default.Public else Icons.Default.Lock,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isPublic) "Public Space" else "Private Space",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (isPublic) "Anyone can find and join"
                            else "Only invited users can join",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPublic,
                        onCheckedChange = onPublicChange,
                        enabled = !isCreating
                    )
                }
            }

            // Invitees
            Text(
                "Invite users (optional)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inviteeInput,
                    onValueChange = { inviteeInput = it },
                    label = { Text("@user:server") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isCreating,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (inviteeInput.startsWith("@") && ":" in inviteeInput) {
                                onAddInvitee(inviteeInput)
                                inviteeInput = ""
                            }
                        }
                    )
                )
                IconButton(
                    onClick = {
                        onAddInvitee(inviteeInput)
                        inviteeInput = ""
                    },
                    enabled = inviteeInput.startsWith("@") && ":" in inviteeInput && !isCreating
                ) {
                    Icon(Icons.Default.Add, "Add")
                }
            }

            if (invitees.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    invitees.forEach { mxid ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(mxid) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveInvitee(mxid) },
                                    modifier = Modifier.size(18.dp),
                                    enabled = !isCreating
                                ) {
                                    Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = name.isNotBlank() && !isCreating,
                shape = MaterialTheme.shapes.large
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Create Space", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}