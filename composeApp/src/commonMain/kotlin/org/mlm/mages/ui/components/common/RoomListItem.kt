package org.mlm.mages.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.RoomSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing

/**
 * Reusable room list item component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListItem(
    room: RoomSummary,
    unreadCount: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = room.id,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unreadCount > 0)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                name = room.name,
                size = org.mlm.mages.ui.theme.Sizes.avatarMedium,
                shape = MaterialTheme.shapes.medium
            )

            Spacer(Modifier.width(Spacing.md))

            Column(Modifier.weight(1f)) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            if (unreadCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}