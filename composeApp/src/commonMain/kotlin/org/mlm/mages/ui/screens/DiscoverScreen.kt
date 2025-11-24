package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom
import org.mlm.mages.ui.controller.DiscoverController
import org.mlm.mages.ui.controller.DiscoverUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoverUi,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onOpenUser: suspend (DirectoryUser) -> Unit,
    onOpenRoom: suspend (PublicRoom) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Group, contentDescription = null) } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search users or public rooms") },
                singleLine = true
            )

            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Users
            if (state.users.isNotEmpty()) {
                Text("Users", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 260.dp)) {
                    items(state.users) { u ->
                        ListItem(
                            headlineContent = { Text(u.displayName ?: u.userId) },
                            supportingContent = { if (!u.displayName.isNullOrBlank()) Text(u.userId) },
                            leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            overlineContent = null,
                            trailingContent = {
                                TextButton(onClick = { scope.launch { onOpenUser(u) } }) { Text("Message") }
                            }
                        )
                        Divider()
                    }
                }
            }

            // Rooms
            if (state.rooms.isNotEmpty()) {
                Text("Public rooms", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = true)) {
                    items(state.rooms) { r ->
                        ListItem(
                            headlineContent = { Text(r.name ?: r.alias ?: r.roomId) },
                            supportingContent = {
                                val line = buildString {
                                    if (!r.topic.isNullOrBlank()) append(r.topic)
                                }
                                if (line.isNotBlank()) Text(line)
                            },
                            leadingContent = { Icon(Icons.Default.Group, contentDescription = null) },
                            trailingContent = {
                                TextButton(onClick = { scope.launch { onOpenRoom(r) } }) { Text("Join") }
                            }
                        )
                        Divider()
                    }
                }
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}