package org.mlm.mages.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.Presence
import org.mlm.mages.ui.theme.Spacing

@Composable
fun PresenceTab(
    currentPresence: Presence,
    statusMessage: String,
    isSaving: Boolean,
    onPresenceChange: (Presence) -> Unit,
    onStatusChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Text(
            "Your Status",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Presence selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                PresenceOption(
                    presence = Presence.Online,
                    currentPresence = currentPresence,
                    title = "Online",
                    subtitle = "Show that you're available",
                    color = Color(0xFF4CAF50),
                    onClick = { onPresenceChange(Presence.Online) }
                )
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                PresenceOption(
                    presence = Presence.Unavailable,
                    currentPresence = currentPresence,
                    title = "Away",
                    subtitle = "Show that you're busy or away",
                    color = Color(0xFFFF9800),
                    onClick = { onPresenceChange(Presence.Unavailable) }
                )
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                PresenceOption(
                    presence = Presence.Offline,
                    currentPresence = currentPresence,
                    title = "Invisible",
                    subtitle = "Appear offline to others",
                    color = Color(0xFF9E9E9E),
                    onClick = { onPresenceChange(Presence.Offline) }
                )
            }
        }

        // Status message
        Text(
            "Status Message",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )

        OutlinedTextField(
            value = statusMessage,
            onValueChange = onStatusChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What's on your mind?") },
            singleLine = true,
            trailingIcon = {
                if (statusMessage.isNotEmpty()) {
                    IconButton(onClick = { onStatusChange("") }) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }
            }
        )

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text("Update Status")
        }

        Spacer(Modifier.weight(1f))

        // Info card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Icon(
                    Icons.Default.Info,
                    null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "Your presence is shared with other users. Setting yourself as invisible will hide your online status from others.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun PresenceOption(
    presence: Presence,
    currentPresence: Presence,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    val isSelected = presence == currentPresence

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(12.dp)
        ) {}

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}