package org.mlm.mages.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomUpgradeInfo
import org.mlm.mages.ui.theme.Spacing

@Composable
fun RoomUpgradeBanner(
    successor: RoomUpgradeInfo?,
    predecessor: RoomPredecessorInfo?,
    onNavigateToRoom: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        successor != null -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Upgrade,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "This room has been upgraded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (successor.reason != null) {
                            Text(
                                successor.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                    TextButton(onClick = { onNavigateToRoom(successor.roomId) }) {
                        Text("Go to new room")
                        Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
            }
        }
        
        predecessor != null -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "This room continues from a previous room",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onNavigateToRoom(predecessor.roomId) }) {
                        Text("View old room")
                    }
                }
            }
        }
    }
}