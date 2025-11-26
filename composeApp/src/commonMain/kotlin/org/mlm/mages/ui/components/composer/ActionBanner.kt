package org.mlm.mages.ui.components.composer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.theme.Spacing

@Composable
fun ActionBanner(replyingTo: MessageEvent?, editing: MessageEvent?, onCancelReply: () -> Unit, onCancelEdit: () -> Unit) {
    when {
        replyingTo != null -> {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replying to ${replyingTo.sender}: ", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(replyingTo.body.take(80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelReply) { Text("Cancel") }
                }
            }
        }
        editing != null -> {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancelEdit) { Text("Cancel") }
                }
            }
        }
    }
}