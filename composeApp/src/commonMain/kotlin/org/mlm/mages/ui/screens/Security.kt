package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.settings.PrivacyTab
import org.mlm.mages.ui.components.dialogs.RecoveryDialog
import org.mlm.mages.ui.components.dialogs.SasDialog
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBack: () -> Unit,
    onRefreshDevices: () -> Unit,
    onStartSelfVerify: (deviceId: String) -> Unit,
    onStartUserVerify: (userId: String) -> Unit,
    onAcceptSas: () -> Unit,
    onConfirmSas: () -> Unit,
    onCancelSas: () -> Unit,
    onOpenRecovery: () -> Unit,
    onCloseRecovery: () -> Unit,
    onChangeRecoveryKey: (String) -> Unit,
    onSubmitRecoveryKey: () -> Unit,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & Privacy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back") } },
                actions = { IconButton(onClick = onRefreshDevices) { Icon(Icons.Default.Refresh, "Refresh") } }
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            val tabs = listOf("Devices", "Recovery", "Privacy")
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { onSelectTab(index) },
                        text = { Text(title) },
                        icon = { Icon(when (index) { 0 -> Icons.Default.Devices; 1 -> Icons.Default.Key; else -> Icons.Default.Lock }, null) }
                    )
                }
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { slideInHorizontally { it } togetherWith slideOutHorizontally { -it } },
                label = "sec-tabs"
            ) { tab ->
                when (tab) {
                    0 -> DevicesTab(isLoading = state.isLoadingDevices, devices = state.devices, onVerify = onStartSelfVerify)
                    1 -> RecoveryTab(onOpenRecovery = onOpenRecovery, onLogout = onLogout)
                    else -> PrivacyTab()
                }
            }
        }
    }

    if (state.showRecoveryDialog) {
        RecoveryDialog(keyValue = state.recoveryKeyInput, onChange = onChangeRecoveryKey, onCancel = onCloseRecovery, onConfirm = onSubmitRecoveryKey)
    }

    if (state.sasFlowId != null) {
        SasDialog(
            phase = state.sasPhase,
            emojis = state.sasEmojis,
            otherUser = state.sasOtherUser.orEmpty(),
            otherDevice = state.sasOtherDevice.orEmpty(),
            error = state.sasError,
            onAccept = onAcceptSas,
            onConfirm = onConfirmSas,
            onCancel = onCancelSas,
            showAccept = state.sasIncoming
        )
    }

    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
    }
}

@Composable
private fun DevicesTab(isLoading: Boolean, devices: List<DeviceSummary>, onVerify: (String) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(devices, searchQuery) {
        if (searchQuery.isBlank()) devices else devices.filter { it.deviceId.contains(searchQuery, true) || it.displayName.contains(searchQuery, true) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            placeholder = { Text("Search devices...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )
        Spacer(Modifier.height(Spacing.sm))
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filtered.isEmpty()) {
            EmptyState(icon = Icons.Default.DevicesOther, title = "No devices found", subtitle = "Try refreshing the list")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(filtered.filter { !it.isOwn }) { dev ->
                    DeviceCard(device = dev, onVerify = { onVerify(dev.deviceId) })
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceSummary, onVerify: () -> Unit) {
    val isVerified = device.verified

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isVerified) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(
                    color = if (isVerified) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isVerified) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isVerified) "Verified" else "Unverified",
                            tint = if (isVerified) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.displayName.ifBlank { device.deviceId }, style = MaterialTheme.typography.titleSmall)
                        if (isVerified) {
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(Icons.Default.Verified, "Verified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("ID: ${device.deviceId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                        Text(device.ed25519.chunked(4).joinToString(" "), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(Spacing.sm))
                    }
                }
            }

            if (!isVerified) {
                Spacer(Modifier.height(Spacing.md))
                OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Verify")
                }
            }
        }
    }
}

@Composable
private fun RecoveryTab(onOpenRecovery: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(Spacing.md))
                    Text("Recovery Key", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Text("Use your recovery key to restore encryption keys and verify this session", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Button(onClick = onOpenRecovery, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                    Icon(Icons.Default.LockOpen, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Enter Recovery Key")
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.lg)) {
                Text("Security Options", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Spacing.md))
                SecurityOption(Icons.Default.Backup, "Backup Keys", "Export your encryption keys") {}
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                SecurityOption(Icons.Default.History, "Session History", "View all active sessions") {}
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                SecurityOption(Icons.AutoMirrored.Filled.Logout, "Log out", "Sign out from this device", onLogout)
            }
        }
    }
}

@Composable
private fun SecurityOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}