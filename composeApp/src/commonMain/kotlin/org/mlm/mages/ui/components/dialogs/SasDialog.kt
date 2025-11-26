package org.mlm.mages.ui.components.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.ui.animation.AnimationSpecs
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun SasDialog(
    phase: SasPhase?,
    emojis: List<String>,
    otherUser: String,
    otherDevice: String,
    error: String?,
    showAccept: Boolean,
    onAccept: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )) {
            Column(modifier = Modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.VerifiedUser, null, Modifier.size(Sizes.avatarMedium), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(Spacing.lg))
                Text("Verify Device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                
                if (otherUser.isNotBlank() || otherDevice.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("$otherUser • $otherDevice", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp))
                    }
                }

                Spacer(Modifier.height(Spacing.xl))
                AnimatedContent(targetState = phase, transitionSpec = { AnimationSpecs.contentTransform() }, label = "SasPhase") { currentPhase ->
                    SasPhaseContent(currentPhase, emojis)
                }

                error?.let {
                    Spacer(Modifier.height(Spacing.lg))
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(Spacing.md), textAlign = TextAlign.Center)
                    }
                }

                Spacer(Modifier.height(Spacing.xl))
                SasActions(phase, onAccept, onConfirm, onCancel)
            }
        }
    }
}

@Composable
private fun SasPhaseContent(phase: SasPhase?, emojis: List<String>) {
    when (phase) {
        SasPhase.Ready -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(Spacing.sm))
            Text("Preparing emoji comparison…")
        }
        SasPhase.Requested -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Verification request received", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.sm))
            Text("Accept to continue with emoji verification", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        SasPhase.Emojis -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Compare these emojis", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(Spacing.lg))
            EmojiGrid(emojis)
            Spacer(Modifier.height(Spacing.lg))
            Text("Do these match on the other device?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SasPhase.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(Sizes.avatarLarge)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(Sizes.iconLarge), MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text("Verification Complete!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        else -> CircularProgressIndicator()
    }
}

@Composable
private fun EmojiGrid(emojis: List<String>) {
    emojis.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { emoji ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(4.dp)) {
                    Text(emoji, Modifier.padding(Spacing.md), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

@Composable
private fun SasActions(phase: SasPhase?, onAccept: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (phase) {
            SasPhase.Requested -> {
                OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text("Reject") }
                Button(onClick = onAccept, Modifier.weight(1f)) { Text("Accept") }
            }
            SasPhase.Emojis -> {
                OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text("They don't match") }
                Button(onClick = onConfirm, Modifier.weight(1f)) { Text("They match") }
            }
            SasPhase.Done -> Button(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Close") }
            else -> OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}