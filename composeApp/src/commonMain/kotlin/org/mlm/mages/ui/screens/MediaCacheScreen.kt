package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.MediaCacheUiState
import org.mlm.mages.ui.components.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCacheScreen(
    state: MediaCacheUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClearKeep: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Cache", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isBusy) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Media Cache", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text(formatBytes(state.bytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("Files", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text((state.files["total"] ?: 0L).toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)                        }
                    }
                    LinearProgressIndicator(
                        progress = { (state.bytes / (500 * 1024 * 1024f)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Media files are cached locally for faster loading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Cache Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { onClearKeep(100L * 1024 * 1024) }, modifier = Modifier.fillMaxWidth(), enabled = !state.isBusy) {
                        Icon(Icons.Default.CleaningServices, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Keep last 100 MB")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onClearKeep(50L * 1024 * 1024) }, modifier = Modifier.fillMaxWidth(), enabled = !state.isBusy) {
                        Icon(Icons.Default.CleaningServices, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Keep last 50 MB")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onClearAll,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isBusy
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear all cache")
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}