package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.RoomProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitesScreen(
    invites: List<RoomProfile>,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAccept: suspend (String) -> Unit,
    onDecline: (String) -> Unit,
    onOpenRoom: (roomId: String, title: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invites") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(enabled = !busy, onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

            if (invites.isEmpty() && !busy) {
                Text("No pending invites", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(invites, key = { it.roomId }) { inv ->
                        ElevatedCard {
                            ListItem(
                                headlineContent = { Text(inv.name) },
                                supportingContent = {
                                    Column {
                                        inv.topic?.let { Text(it) }
                                        Text(inv.roomId, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                trailingContent = {
                                    Row { //Setting vertical alignment won't help on mobile since misaligns are due to text overflow...
                                        TextButton(onClick = { onDecline(inv.roomId) }) { Text("Decline") }
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = {
                                            scope.launch {
                                                onAccept(inv.roomId)
                                                onOpenRoom(inv.roomId, inv.name)
                                            }
                                        }) { Text("Accept") }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}