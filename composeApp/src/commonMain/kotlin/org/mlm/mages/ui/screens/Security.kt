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
import org.mlm.mages.ui.RoomsUiState
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.ui.components.EmptyStateView
import org.mlm.mages.ui.components.PrivacyTab
import org.mlm.mages.ui.components.RecoveryDialog
import org.mlm.mages.ui.components.SasDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBack: () -> Unit,
    onRefreshDevices: () -> Unit,
    onToggleTrust: (deviceId: String, verified: Boolean) -> Unit,
    onStartSelfVerify: (deviceId: String) -> Unit,
    onStartUserVerify: (userId: String) -> Unit,
    onAcceptSas: () -> Unit,
    onConfirmSas: () -> Unit,
    onCancelSas: () -> Unit,
    onOpenRecovery: () -> Unit,
    onCloseRecovery: () -> Unit,
    onChangeRecoveryKey: (String) -> Unit,
    onSubmitRecoveryKey: () -> Unit,
    onOpenMediaCache: (() -> Unit)? = null,
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
                        icon = {
                            Icon(
                                when (index) { 0 -> Icons.Default.Devices; 1 -> Icons.Default.Key; else -> Icons.Default.Lock },
                                null
                            )
                        }
                    )
                }
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { slideInHorizontally { it } togetherWith slideOutHorizontally { -it } },
                label = "sec-tabs"
            ) { tab ->
                when (tab) {
                    0 -> DevicesTab(
                        isLoading = state.isLoadingDevices,
                        devices = state.devices,
                        onToggleTrust = onToggleTrust,
                        onVerify = onStartSelfVerify
                    )
                    1 -> RecoveryTab(
                        onOpenRecovery = onOpenRecovery,
                        onOpenMediaCache = onOpenMediaCache,
                        onLogout = onLogout
                    )
                    else -> PrivacyTab()
                }
            }
        }
    }

    if (state.showRecoveryDialog) {
        RecoveryDialog(
            keyValue = state.recoveryKeyInput,
            onChange = onChangeRecoveryKey,
            onCancel = onCloseRecovery,
            onConfirm = onSubmitRecoveryKey
        )
    }

    // SAS dialog flows
    if (state.sasFlowId != null) {
        SasDialog(
            phase = state.sasPhase,
            emojis = state.sasEmojis,
            otherUser = state.sasOtherUser.orEmpty(),
            otherDevice = state.sasOtherDevice.orEmpty(),
            error = state.sasError,
            onAccept = onAcceptSas,
            onConfirm = onConfirmSas,
            onCancel = onCancelSas
        )
    }

    state.error?.let {
        // Passive surface display; you can elevate this to Snackbar if you like
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DevicesTab(
    isLoading: Boolean,
    devices: List<DeviceSummary>,
    onToggleTrust: (String, Boolean) -> Unit,
    onVerify: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(devices, searchQuery) {
        if (searchQuery.isBlank()) devices
        else devices.filter { it.deviceId.contains(searchQuery, true) || it.displayName.contains(searchQuery, true) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search devices...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )
        Spacer(Modifier.height(8.dp))
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filtered.isEmpty()) {
            EmptyStateView(icon = Icons.Default.DevicesOther, title = "No devices found", subtitle = "Try refreshing the list")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered.filter { !it.isOwn }) { dev ->
                    DeviceCard(
                        device = dev,
                        onToggleTrust = { v -> onToggleTrust(dev.deviceId, v) },
                        onVerify = { onVerify(dev.deviceId) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(
    device: DeviceSummary,
    onToggleTrust: (Boolean) -> Unit,
    onVerify: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.locallyTrusted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(
                    color = if (device.locallyTrusted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = if (device.locallyTrusted) MaterialTheme.colorScheme.onSecondary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.displayName.ifBlank { device.deviceId },
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (device.locallyTrusted) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("ID: ${device.deviceId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(
                            text = device.ed25519.chunked(4).joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onVerify, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Verify")
                }
                FilledTonalButton(
                    onClick = { onToggleTrust(!device.locallyTrusted) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (device.locallyTrusted) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (device.locallyTrusted) Icons.Default.RemoveModerator else Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (device.locallyTrusted) "Untrust" else "Trust")
                }
            }
        }
    }
}

@Composable
private fun RecoveryTab(
    onOpenRecovery: () -> Unit,
    onOpenMediaCache: (() -> Unit)?,
    onLogout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text("Recovery Key", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Text("Use your recovery key to restore encryption keys and verify this session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Button(onClick = onOpenRecovery, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                    Icon(Icons.Default.LockOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enter Recovery Key")
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Security Options", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                SecurityOption(Icons.Default.Backup, "Backup Keys", "Export your encryption keys") { /* TODO */ }
                Divider(Modifier.padding(vertical = 8.dp))
                SecurityOption(Icons.Default.History, "Session History", "View all active sessions") { /* TODO */ }
                Divider(Modifier.padding(vertical = 8.dp))
                SecurityOption(Icons.Default.CleaningServices, "Media Cache", "Manage cached media") {
                    onOpenMediaCache?.invoke()
                }
                Divider(Modifier.padding(vertical = 8.dp))
                SecurityOption(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Log out",
                    subtitle = "Sign out from this device",
                    onClick = onLogout
                )
            }
        }
    }
}

@Composable private fun SecurityOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

