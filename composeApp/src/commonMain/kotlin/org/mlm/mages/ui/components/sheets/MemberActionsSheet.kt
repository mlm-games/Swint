package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberActionsSheet(
    member: MemberSummary,
    onDismiss: () -> Unit,
    onStartDm: () -> Unit,
    onKick: (reason: String?) -> Unit,
    onBan: (reason: String?) -> Unit,
    onUnban: (reason: String?) -> Unit,
    onIgnore: () -> Unit,
    canModerate: Boolean = false,
    isBanned: Boolean = false
) {
    var showKickDialog by remember { mutableStateOf(false) }
    var showBanDialog by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxl)
        ) {
            // Member header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    name = member.displayName ?: member.userId,
                    size = Sizes.avatarMedium
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        member.displayName ?: member.userId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        member.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // Actions
            ActionItem(
                icon = Icons.Default.Chat,
                title = "Send direct message",
                onClick = { onStartDm(); onDismiss() }
            )

            ActionItem(
                icon = Icons.Default.Block,
                title = "Ignore user",
                subtitle = "Hide their messages everywhere",
                onClick = { onIgnore(); onDismiss() }
            )

            if (canModerate) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

                Text(
                    "Moderation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )

                if (isBanned) {
                    ActionItem(
                        icon = Icons.Default.RemoveCircle,
                        title = "Unban user",
                        subtitle = "Allow them to rejoin",
                        onClick = { onUnban(null); onDismiss() }
                    )
                } else {
                    ActionItem(
                        icon = Icons.Default.ExitToApp,
                        title = "Remove from room",
                        subtitle = "Kick user from this room",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showKickDialog = true }
                    )

                    ActionItem(
                        icon = Icons.Default.Block,
                        title = "Ban from room",
                        subtitle = "Permanently remove and prevent rejoining",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showBanDialog = true }
                    )
                }
            }
        }
    }

    // Kick confirmation dialog
    if (showKickDialog) {
        ConfirmModerationDialog(
            title = "Remove ${member.displayName ?: member.userId}",
            message = "They will be removed from this room but can rejoin if invited.",
            reasonValue = reason,
            onReasonChange = { reason = it },
            onConfirm = {
                onKick(reason.ifBlank { null })
                onDismiss()
            },
            onDismiss = { showKickDialog = false }
        )
    }

    // Ban confirmation dialog
    if (showBanDialog) {
        ConfirmModerationDialog(
            title = "Ban ${member.displayName ?: member.userId}",
            message = "They will be removed and won't be able to rejoin unless unbanned.",
            reasonValue = reason,
            onReasonChange = { reason = it },
            isDestructive = true,
            onConfirm = {
                onBan(reason.ifBlank { null })
                onDismiss()
            },
            onDismiss = { showBanDialog = false }
        )
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, color = tint) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = tint) },
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun ConfirmModerationDialog(
    title: String,
    message: String,
    reasonValue: String,
    onReasonChange: (String) -> Unit,
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(message)
                OutlinedTextField(
                    value = reasonValue,
                    onValueChange = onReasonChange,
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}