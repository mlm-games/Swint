package org.mlm.frair.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.frair.AppState
import org.mlm.frair.Intent
import org.mlm.frair.matrix.DeviceSummary
import org.mlm.frair.matrix.SasPhase

/**
 * Security screen: device list (fingerprints + local trust) and SAS emoji verification flow.
 *
 * Intents expected:
 * - Intent.CloseSecurity
 * - Intent.RefreshSecurity
 * - Intent.ToggleTrust(deviceId, verified)
 * - Intent.StartSelfVerify(deviceId)
 * - Intent.AcceptSas / Intent.ConfirmSas / Intent.CancelSas
 */
@Composable
fun SecurityScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    // If navigated here without preloading devices, do a one-shot refresh.
    LaunchedEffect(Unit) {
        if (state.devices.isEmpty() && !state.isLoadingDevices) {
            onIntent(Intent.RefreshSecurity)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Security", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onIntent(Intent.CloseSecurity) }) { Text("Close") }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { onIntent(Intent.RefreshSecurity) },
            enabled = !state.isLoadingDevices
        ) { Text(if (state.isLoadingDevices) "Refreshing…" else "Refresh devices") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onIntent(Intent.OpenRecoveryDialog) }) { Text("Recover with key") }
        }

        Spacer(Modifier.height(8.dp))

        if (state.devices.isEmpty() && !state.isLoadingDevices) {
            Text("No devices found.", style = MaterialTheme.typography.bodyMedium)
        }

        state.devices.forEach { d ->
            DeviceRow(
                d = d,
                onToggleTrust = { checked -> onIntent(Intent.ToggleTrust(d.deviceId, checked)) },
                onStartSas = { onIntent(Intent.StartSelfVerify(d.deviceId)) }
            )
            Divider()
        }

        // SAS modal (emoji flow)
        if (state.sasFlowId != null) {
            SasSheet(
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
private fun DeviceRow(
    d: DeviceSummary,
    onToggleTrust: (Boolean) -> Unit,
    onStartSas: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                d.displayName.ifBlank { d.deviceId },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            // Group fingerprint for readability
            val fp = d.ed25519.chunked(4).joinToString(" ")
            Text("FP: $fp", maxLines = 2)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(
                    onClick = onStartSas,
                    enabled = !d.isOwn
                ) { Text(if (d.isOwn) "This device" else "Verify (emoji)") }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verified", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = d.locallyTrusted, onCheckedChange = onToggleTrust)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SasSheet(
    phase: SasPhase?,
    emojis: List<String>,
    otherUser: String,
    otherDevice: String,
    error: String?,
    onAccept: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Verification", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Other: $otherUser • $otherDevice",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))

            when (phase) {
                SasPhase.Requested -> {
                    Text("Requested…")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAccept) { Text("Accept") }
                }
                SasPhase.Ready -> {
                    Text("Exchanging keys…")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                SasPhase.Emojis -> {
                    Text(
                        "Compare these emojis on both devices:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRowMain(emojis)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onConfirm) { Text("They match") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                SasPhase.Confirmed -> {
                    Text("Confirmed, finalizing…")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCancel) { Text("Close") }
                }
                SasPhase.Done -> {
                    Text("Verified!", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCancel) { Text("Close") }
                }
                SasPhase.Cancelled, SasPhase.Failed -> {
                    Text("Verification cancelled/failed")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCancel) { Text("Close") }
                }
                null -> {
                    Text("Starting…")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Simple wrapping row of chips without depending on FlowRow.
 */
@Composable
private fun FlowRowMain(items: List<String>) {
    Column {
        val perRow = 4
        items.chunked(perRow).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { e ->
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            e,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
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
                Text("Paste your human-readable recovery key.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyValue,
                    onValueChange = onChange,
                    singleLine = true,
                    placeholder = { Text("e.g. 4x4 grouped recovery phrase") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Recover") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}