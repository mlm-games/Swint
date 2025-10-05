package org.mlm.frair.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.frair.AppState
import org.mlm.frair.Intent
import org.mlm.frair.ui.components.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCacheScreen(
    state: AppState,
    padding: PaddingValues,
    onIntent: (Intent) -> Unit
) {
    LaunchedEffect(Unit) {
        onIntent(Intent.ShowMediaCacheInfo)
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        topBar = {
            TopAppBar(
                title = { Text("Media Cache", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(Intent.Back) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cache stats card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Media Cache",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "Size",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                formatBytes(state.mediaCacheSize),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Files",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                state.mediaCacheFiles.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { (state.mediaCacheSize / (500 * 1024 * 1024f)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        "Media files are cached locally for faster loading",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // Cache management options
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Cache Management",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { onIntent(Intent.ClearMediaCache(100 * 1024 * 1024)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CleaningServices, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Keep last 100 MB")
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { onIntent(Intent.ClearMediaCache(50 * 1024 * 1024)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CleaningServices, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Keep last 50 MB")
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { onIntent(Intent.ClearMediaCache(0)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear all cache")
                    }
                }
            }
        }
    }
}