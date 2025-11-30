package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLocationSheet(
    isCurrentlySharing: Boolean,
    onStartSharing: (durationMinutes: Int) -> Unit,
    onStopSharing: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDuration by remember { mutableIntStateOf(15) }
    val durations = listOf(
        15 to "15 minutes",
        60 to "1 hour",
        480 to "8 hours"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (isCurrentlySharing) Icons.Default.LocationOn else Icons.Default.LocationSearching,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = if (isCurrentlySharing) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.lg))

            Text(
                if (isCurrentlySharing) "Sharing Your Location" else "Share Live Location",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                if (isCurrentlySharing)
                    "Others in this room can see your real-time location"
                else
                    "Let others see your location in real-time",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Spacing.xl))

            if (isCurrentlySharing) {
                Button(
                    onClick = { onStopSharing(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Stop Sharing")
                }
            } else {
                Text(
                    "Share for",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(Spacing.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    durations.forEach { (minutes, label) ->
                        FilterChip(
                            selected = selectedDuration == minutes,
                            onClick = { selectedDuration = minutes },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xl))

                Button(
                    onClick = { onStartSharing(selectedDuration); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Start Sharing")
                }

                Spacer(Modifier.height(Spacing.md))

                Text(
                    "Your location will be shared with all room members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LiveLocationBanner(
    sharingUsers: List<String>,
    onViewLocations: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (sharingUsers.isEmpty()) return

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
                null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                when (sharingUsers.size) {
                    1 -> "${sharingUsers[0]} is sharing location"
                    2 -> "${sharingUsers[0]} and ${sharingUsers[1]} are sharing location"
                    else -> "${sharingUsers.size} people sharing location"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onViewLocations) {
                Text("View")
            }
        }
    }
}