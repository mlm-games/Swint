package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.CreateSpaceUiState
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSpaceScreen(
    state: CreateSpaceUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit,
    onAddInvitee: (String) -> Unit,
    onRemoveInvitee: (String) -> Unit,
    onCreate: () -> Unit
) {
    var inviteeInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Space", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            // Space preview
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Workspaces,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("Space name *") },
                placeholder = { Text("My Awesome Space") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.error != null && state.name.isBlank(),
                enabled = !state.isCreating
            )

            OutlinedTextField(
                value = state.topic,
                onValueChange = onTopicChange,
                label = { Text("Topic (optional)") },
                placeholder = { Text("What is this space about?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                enabled = !state.isCreating
            )

            // Public/Private
            Card(
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
                    Icon(
                        if (state.isPublic) Icons.Default.Public else Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.isPublic) "Public Space" else "Private Space",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (state.isPublic) 
                                "Anyone can find and join this space" 
                            else 
                                "Only invited users can join",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isPublic,
                        onCheckedChange = onPublicChange,
                        enabled = !state.isCreating
                    )
                }
            }

            // Invitees part
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
                    enabled = !state.isCreating
                )
                IconButton(
                    onClick = {
                        onAddInvitee(inviteeInput)
                        inviteeInput = ""
                    },
                    enabled = inviteeInput.startsWith("@") && ":" in inviteeInput && !state.isCreating
                ) {
                    Icon(Icons.Default.Add, "Add")
                }
            }

            if (state.invitees.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    state.invitees.forEach { mxid ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(mxid) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveInvitee(mxid) },
                                    modifier = Modifier.size(18.dp),
                                    enabled = !state.isCreating
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "Remove",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(Spacing.md),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = state.name.isNotBlank() && !state.isCreating,
                shape = MaterialTheme.shapes.large
            ) {
                if (state.isCreating) {
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