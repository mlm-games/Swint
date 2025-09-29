package org.mlm.frair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.frair.AppState
import org.mlm.frair.Intent
import org.mlm.frair.matrix.DeviceSummary
import org.mlm.frair.matrix.SasPhase

/**
 * Improved Security screen:
 * - Stable UI (AlertDialog for SAS instead of a bottom sheet to avoid MPP glitches)
 * - Device list with search, clear "My device" badge, and copyable fingerprint
 * - Verify via Emoji (SAS) for non-own devices
 * - Local trust toggle
 * - Recovery dialog kept, with clearer wording
 */
@Composable
fun SecurityScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    var query by remember { mutableStateOf("") }

    // One-shot auto-refresh if empty
    LaunchedEffect(Unit) {
        if (state.devices.isEmpty() && !state.isLoadingDevices) {
            onIntent(Intent.RefreshSecurity)
        }
    }

    val filtered = remember(state.devices, query) {
        if (query.isBlank()) state.devices
        else state.devices.filter {
            it.deviceId.contains(query, ignoreCase = true) ||
                    it.displayName.contains(query, ignoreCase = true) ||
                    it.ed25519.contains(query, ignoreCase = true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // Search + actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search devices") },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { onIntent(Intent.RefreshSecurity) },
                enabled = !state.isLoadingDevices
            ) { Text(if (state.isLoadingDevices) "Refreshing…" else "Refresh") }
        }

        Spacer(Modifier.height(10.dp))

        // Recovery
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Recover E2EE", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "If you’ve previously set a recovery key, you can restore your end‑to‑end encryption keys.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onIntent(Intent.OpenRecoveryDialog) }) { Text("Recover with key") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Device list
        if (filtered.isEmpty() && !state.isLoadingDevices) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("No devices found.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered, key = { it.deviceId }) { d ->
                    DeviceRow(
                        d = d,
                        onToggleTrust = { checked -> onIntent(Intent.ToggleTrust(d.deviceId, checked)) },
                        onStartSas = { onIntent(Intent.StartSelfVerify(d.deviceId)) }
                    )
                    Divider()
                }
            }
        }
    }

    // SAS flow (AlertDialog for stability across Android/JVM)
    if (state.sasFlowId != null) {
        SasDialog(
            phase = state.sasPhase,
            emojis = state.sasEmojis,
            otherUser = state.sasOtherUser.orEmpty(),
            otherDevice = state.sasOtherDevice.orEmpty(),
            error = state.sasError,
            onAccept = { onIntent(Intent.AcceptSas) },
            onConfirm = { onIntent(Intent.ConfirmSas) },
            onCancel = { onIntent(Intent.CancelSas) }
        )
    }

    // Recovery key input
    if (state.showRecoveryDialog) {
        RecoveryDialog(
            keyValue = state.recoveryKeyInput,
            onChange = { onIntent(Intent.SetRecoveryKey(it)) },
            onCancel = { onIntent(Intent.CloseRecoveryDialog) },
            onConfirm = { onIntent(Intent.SubmitRecoveryKey) },
        )
    }
}

@Composable
private fun DeviceRow(
    d: DeviceSummary,
    onToggleTrust: (Boolean) -> Unit,
    onStartSas: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val fpGrouped = remember(d.ed25519) { d.ed25519.chunked(4).joinToString(" ") }

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.displayName.ifBlank { d.deviceId },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                if (d.isOwn) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("My device") })
                }
                if (d.locallyTrusted) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = {}, enabled = false, label = { Text("Locally trusted") })
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ID: ${d.deviceId}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FP: $fpGrouped", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(fpGrouped)) }) { Text("Copy") }
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(
                    onClick = onStartSas,
                    enabled = !d.isOwn
                ) { Text(if (d.isOwn) "This device" else "Verify (emoji)") }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trusted", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = d.locallyTrusted, onCheckedChange = onToggleTrust)
                }
            }
        }
    )
}

@Composable
private fun SasDialog(
    phase: SasPhase?,
    emojis: List<String>,
    otherUser: String,
    otherDevice: String,
    error: String?,
    onAccept: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val body: @Composable () -> Unit = {
        Column {
            if (!otherUser.isNullOrBlank() || !otherDevice.isNullOrBlank()) {
                Text("Other: $otherUser • $otherDevice", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
            }

            if (error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            when (phase) {
                SasPhase.Requested -> {
                    Text("Verification requested.", style = MaterialTheme.typography.bodyMedium)
                }
                SasPhase.Ready -> {
                    Text("Handshake ready. Waiting for emoji…", style = MaterialTheme.typography.bodyMedium)
                }
                SasPhase.Emojis -> {
                    Text("Compare these emojis:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRowMain(emojis)
                }
                SasPhase.Confirmed -> {
                    Text("Confirmed. Finalizing…", style = MaterialTheme.typography.bodyMedium)
                }
                SasPhase.Done -> {
                    Text("Verified!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                SasPhase.Cancelled, SasPhase.Failed -> {
                    Text("Verification cancelled/failed.", style = MaterialTheme.typography.bodyMedium)
                }
                null -> {
                    Text("Starting…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Emoji Verification") },
        text = body,
        confirmButton = {
            when (phase) {
                SasPhase.Requested -> Button(onClick = onAccept) { Text("Accept") }
                SasPhase.Emojis -> Button(onClick = onConfirm) { Text("They match") }
                SasPhase.Confirmed, SasPhase.Done, SasPhase.Cancelled, SasPhase.Failed -> TextButton(onClick = onCancel) { Text("Close") }
                SasPhase.Ready, null -> TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
        dismissButton = {
            when (phase) {
                SasPhase.Requested, SasPhase.Ready, SasPhase.Emojis -> TextButton(onClick = onCancel) { Text("Cancel") }
                else -> {}
            }
        }
    )
}

/**
 * Simple wrapping rows for emojis without FlowRow dependency.
 */
@Composable
private fun FlowRowMain(items: List<String>) {
    if (items.isEmpty()) return
    Column {
        val perRow = 4
        items.chunked(perRow).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { e ->
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(e, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryDialog(
    keyValue: String,
    onChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Recover with key") },
        text = {
            Column {
                Text(
                    "Paste your human‑readable recovery key to restore E2EE secrets.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyValue,
                    onValueChange = onChange,
                    singleLine = true,
                    placeholder = { Text("e.g. groups of 4 characters") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Recover") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}