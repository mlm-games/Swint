package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.Presence
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.dialogs.RecoveryDialog
import org.mlm.mages.ui.components.dialogs.SasDialog
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SecurityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var verifyUserId by remember { mutableStateOf("") }
    var showVerifyUserDialog by remember { mutableStateOf(false) }

    // Show error from state
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Security & Settings", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showLogoutConfirm = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                "Logout",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
                TabRow(selectedTabIndex = state.selectedTab) {
                    Tab(
                        selected = state.selectedTab == 0,
                        onClick = { viewModel.setSelectedTab(0) },
                        text = { Text("Devices") },
                        icon = { Icon(Icons.Default.Devices, null) }
                    )
                    Tab(
                        selected = state.selectedTab == 1,
                        onClick = { viewModel.setSelectedTab(1) },
                        text = { Text("Privacy") },
                        icon = { Icon(Icons.Default.PrivacyTip, null) }
                    )
                    Tab(
                        selected = state.selectedTab == 2,
                        onClick = { viewModel.setSelectedTab(2) },
                        text = { Text("Status") },
                        icon = { Icon(Icons.Default.Circle, null) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        AnimatedContent(
            targetState = state.selectedTab,
            modifier = Modifier.padding(padding),
            label = "TabContent"
        ) { tab ->
            when (tab) {
                0 -> DevicesTab(
                    devices = state.devices,
                    isLoading = state.isLoadingDevices,
                    onRefresh = viewModel::refreshDevices,
                    onVerifyDevice = viewModel::startSelfVerify,
                    onVerifyUser = { showVerifyUserDialog = true },
                    onOpenRecovery = viewModel::openRecoveryDialog
                )
                1 -> PrivacyTab(
                    ignoredUsers = state.ignoredUsers,
                    onUnignore = viewModel::unignoreUser
                )
                2 -> PresenceTab(
                    currentPresence = state.presence.currentPresence,
                    statusMessage = state.presence.statusMessage,
                    isSaving = state.presence.isSaving,
                    onPresenceChange = viewModel::setPresence,
                    onStatusChange = viewModel::setStatusMessage,
                    onSave = viewModel::savePresence
                )
            }
        }
    }

    // SAS Verification Dialog
    if (state.sasFlowId != null && state.sasPhase != null) {
        SasDialog(
            phase = state.sasPhase,
            emojis = state.sasEmojis,
            otherUser = state.sasOtherUser ?: "",
            otherDevice = state.sasOtherDevice ?: "",
            error = state.sasError,
            showAccept = state.sasIncoming && state.sasPhase == SasPhase.Requested,
            onAccept = viewModel::acceptSas,
            onConfirm = viewModel::confirmSas,
            onCancel = viewModel::cancelSas
        )
    }

    // Recovery Key Dialog
    if (state.showRecoveryDialog) {
        RecoveryDialog(
            keyValue = state.recoveryKeyInput,
            onChange = viewModel::setRecoveryKey,
            onCancel = viewModel::closeRecoveryDialog,
            onConfirm = viewModel::submitRecoveryKey
        )
    }

    // Verify User Dialog
    if (showVerifyUserDialog) {
        AlertDialog(
            onDismissRequest = { showVerifyUserDialog = false },
            icon = { Icon(Icons.Default.VerifiedUser, null) },
            title = { Text("Verify User") },
            text = {
                OutlinedTextField(
                    value = verifyUserId,
                    onValueChange = { verifyUserId = it },
                    label = { Text("User ID") },
                    placeholder = { Text("@user:server.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (verifyUserId.isNotBlank()) {
                            viewModel.startUserVerify(verifyUserId.trim())
                            showVerifyUserDialog = false
                            verifyUserId = ""
                        }
                    },
                    enabled = verifyUserId.startsWith("@") && ":" in verifyUserId
                ) {
                    Text("Verify")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVerifyUserDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Logout Confirmation
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DevicesTab(
    devices: List<DeviceSummary>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onVerifyDevice: (String) -> Unit,
    onVerifyUser: () -> Unit,
    onOpenRecovery: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Action cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                ActionCard(
                    icon = Icons.Default.Key,
                    title = "Recovery",
                    onClick = onOpenRecovery,
                    modifier = Modifier.weight(1f)
                )
                ActionCard(
                    icon = Icons.Default.VerifiedUser,
                    title = "Verify User",
                    onClick = onVerifyUser,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Your Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }
        }

        if (devices.isEmpty() && !isLoading) {
            item {
                EmptyState(
                    icon = Icons.Default.DevicesOther,
                    title = "No devices found",
                    subtitle = "Try refreshing the list"
                )
            }
        }

        items(devices.filter { !it.isOwn }, key = { it.deviceId }) { device ->
            DeviceCard(device = device, onVerify = { onVerifyDevice(device.deviceId) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(Spacing.sm))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceSummary, onVerify: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.verified)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (device.verified) Icons.Default.VerifiedUser else Icons.Default.Smartphone,
                null,
                tint = if (device.verified) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.displayName.ifBlank { device.deviceId },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    device.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!device.verified) {
                FilledTonalButton(onClick = onVerify) {
                    Text("Verify")
                }
            } else {
                Icon(Icons.Default.CheckCircle, "Verified", tint = Color(0xFF4CAF50))
            }
        }
    }
}

@Composable
private fun PrivacyTab(
    ignoredUsers: List<String>,
    onUnignore: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(Spacing.lg)) {
        Text("Ignored Users", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.md))

        if (ignoredUsers.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Block,
                title = "No ignored users",
                subtitle = "Users you ignore won't be able to message you"
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(ignoredUsers) { mxid ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, null)
                            Spacer(Modifier.width(Spacing.md))
                            Text(mxid, Modifier.weight(1f))
                            TextButton(onClick = { onUnignore(mxid) }) {
                                Text("Unignore")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresenceTab(
    currentPresence: Presence,
    statusMessage: String,
    isSaving: Boolean,
    onPresenceChange: (Presence) -> Unit,
    onStatusChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Text("Your Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                PresenceOption(
                    presence = Presence.Online,
                    currentPresence = currentPresence,
                    title = "Online",
                    color = Color(0xFF4CAF50),
                    onClick = { onPresenceChange(Presence.Online) }
                )
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                PresenceOption(
                    presence = Presence.Unavailable,
                    currentPresence = currentPresence,
                    title = "Away",
                    color = Color(0xFFFF9800),
                    onClick = { onPresenceChange(Presence.Unavailable) }
                )
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                PresenceOption(
                    presence = Presence.Offline,
                    currentPresence = currentPresence,
                    title = "Invisible",
                    color = Color(0xFF9E9E9E),
                    onClick = { onPresenceChange(Presence.Offline) }
                )
            }
        }

        Text("Status Message", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)

        OutlinedTextField(
            value = statusMessage,
            onValueChange = onStatusChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What's on your mind?") },
            singleLine = true
        )

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text("Update Status")
        }
    }
}

@Composable
private fun PresenceOption(
    presence: Presence,
    currentPresence: Presence,
    title: String,
    color: Color,
    onClick: () -> Unit
) {
    val isSelected = presence == currentPresence

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(12.dp)) {}
        Spacer(Modifier.width(Spacing.md))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}