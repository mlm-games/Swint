package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.ui.theme.Spacing

@Composable
fun LiveLocationBanner(
    shares: Map<String, LiveLocationShare>,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (shares.isEmpty()) return
    
    val activeShares = shares.values.filter { it.isLive }
    if (activeShares.isEmpty()) return
    
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Spacer(Modifier.width(Spacing.sm))
            
            // Show overlapping avatars
            Row {
                activeShares.take(3).forEachIndexed { index, share ->
                    Avatar(
                        name = share.userId,
                        size = 24.dp,
                        modifier = Modifier.offset(x = (-8 * index).dp)
                    )
                }
            }
            
            Spacer(Modifier.width(Spacing.sm))
            
            Text(
                when (activeShares.size) {
                    1 -> "${formatDisplayName(activeShares[0].userId)} is sharing location"
                    else -> "${activeShares.size} people sharing location"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            
            TextButton(onClick = onViewAll) {
                Text("View")
            }
        }
    }
}