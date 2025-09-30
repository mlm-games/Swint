package org.mlm.frair.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.frair.AppState
import org.mlm.frair.Intent
import org.mlm.frair.matrix.DeviceSummary
import org.mlm.frair.matrix.SasPhase

@Composable
fun SecurityScreen(state: AppState, padding: PaddingValues, onIntent: (Intent) -> Unit) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (state.devices.isEmpty() && !state.isLoadingDevices) onIntent(Intent.RefreshSecurity) }

    val filtered = remember(state.devices, query) {
        if (query.isBlank()) state.devices
        else state.devices.filter {
            it.deviceId.contains(query, true) || it.displayName.contains(query, true) || it.ed25519.contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        }

        Spacer(Modifier.height(12.dp))

        // Call to action: ensure the user can verify this device or start user-verification
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Verify your session", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "If this session isn’t verified yet, restore with your recovery key or verify from another session.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            OutlinedButton(onClick = { onIntent(Intent.OpenRecoveryDialog) }) {
                                Text(
                                    "Recover with key"
                                )
                            }
                        }
                    }
                }
                // Verify a user (other user SAS)
//                var userId by remember { mutableStateOf("") }
//                OutlinedTextField(
//                    value = userId,
//                    onValueChange = { userId = it },
//                    singleLine = true,
//                    label = { Text("Verify user (MXID)") },
//                    placeholder = { Text("@user:server") },
//                    modifier = Modifier.fillMaxWidth()
//                )
//                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
//                    OutlinedButton(
//                        onClick = { if (userId.isNotBlank()) onIntent(Intent.StartUserVerify(userId.trim())) }
//                    ) { Text("Start user verification") }
//                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search devices") }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onIntent(Intent.RefreshSecurity) }, enabled = !state.isLoadingDevices) {
                Text(if (state.isLoadingDevices) "Refreshing…" else "Refresh")
            }
        }

        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty() && !state.isLoadingDevices) {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) { Text("No devices found.", style = MaterialTheme.typography.bodyMedium) }
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
private fun DeviceRow(d: DeviceSummary, onToggleTrust: (Boolean) -> Unit, onStartSas: () -> Unit) {
    val fpGrouped = remember(d.ed25519) { d.ed25519.chunked(4).joinToString(" ") }
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.displayName.ifBlank { d.deviceId }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                if (d.isOwn) AssistChip(onClick = {}, enabled = false, label = { Text("My device") })
                if (d.locallyTrusted) { Spacer(Modifier.width(6.dp)); AssistChip(onClick = {}, enabled = false, label = { Text("Locally trusted") }) }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ID: ${d.deviceId}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text("FP: $fpGrouped", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(onClick = onStartSas, enabled = !d.isOwn) { Text(if (d.isOwn) "This device" else "Verify (emoji)") }
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
    phase: SasPhase?, emojis: List<String>, otherUser: String, otherDevice: String, error: String?,
    onAccept: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Emoji Verification") },
        text = {
            Column {
                if (otherUser.isNotBlank() || otherDevice.isNotBlank())
                    Text("Other: $otherUser • $otherDevice", style = MaterialTheme.typography.labelSmall)
                if (error != null) { Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                when (phase) {
                    SasPhase.Requested -> Text("Verification requested.")
                    SasPhase.Ready -> Text("Handshake ready. Waiting for emoji…")
                    SasPhase.Emojis -> FlowRowMain(emojis)
                    SasPhase.Confirmed -> Text("Confirmed. Finalizing…")
                    SasPhase.Done -> Text("Verified!", color = MaterialTheme.colorScheme.primary)
                    SasPhase.Cancelled, SasPhase.Failed -> Text("Verification cancelled/failed.")
                    null -> Text("Starting…")
                }
            }
        },
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

@Composable private fun FlowRowMain(items: List<String>) {
    if (items.isEmpty()) return
    Column {
        val perRow = 4
        items.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e -> Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) { Text(e, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) } }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun RecoveryDialog(keyValue: String, onChange: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Recover with key") },
        text = {
            Column {
                Text("Paste your human‑readable recovery key to restore E2EE secrets.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = keyValue, onValueChange = onChange, singleLine = true, placeholder = { Text("groups of 4 characters") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Recover") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}