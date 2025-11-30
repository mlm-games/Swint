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
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomNotificationSheet(
    currentMode: RoomNotificationMode?,
    isLoading: Boolean,
    onModeChange: (RoomNotificationMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxl)
        ) {
            Text(
                "Notification Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                NotificationOption(
                    icon = Icons.Default.Notifications,
                    title = "All Messages",
                    subtitle = "Get notified for every message",
                    isSelected = currentMode == RoomNotificationMode.AllMessages,
                    onClick = { onModeChange(RoomNotificationMode.AllMessages); onDismiss() }
                )

                NotificationOption(
                    icon = Icons.Default.AlternateEmail,
                    title = "Mentions & Keywords Only",
                    subtitle = "Only notify when mentioned or keywords match",
                    isSelected = currentMode == RoomNotificationMode.MentionsAndKeywordsOnly,
                    onClick = { onModeChange(RoomNotificationMode.MentionsAndKeywordsOnly); onDismiss() }
                )

                NotificationOption(
                    icon = Icons.Default.NotificationsOff,
                    title = "Mute",
                    subtitle = "No notifications from this room",
                    isSelected = currentMode == RoomNotificationMode.Mute,
                    onClick = { onModeChange(RoomNotificationMode.Mute); onDismiss() }
                )
            }
        }
    }
}

@Composable
private fun NotificationOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                icon,
                null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface
        )
    )
}