package org.mlm.mages.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun RecoveryDialog(keyValue: String, onChange: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(Sizes.avatarMedium)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        title = { Text("Enter Recovery Key", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text("Paste your recovery key to restore end-to-end encryption and verify this session.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = keyValue, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Enter recovery key...") }, supportingText = { Text("Recovery keys are usually 48 characters in groups of 4") }, shape = MaterialTheme.shapes.medium, singleLine = false, minLines = 3)
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = keyValue.isNotBlank()) { Text("Recover") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}