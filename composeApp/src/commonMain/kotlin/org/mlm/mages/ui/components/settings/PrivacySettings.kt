package org.mlm.mages.ui.components.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.mlm.mages.ui.theme.Spacing

@Composable
fun PrivacyTab(
    ignoredUsers: List<String>,
    onUnignore: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(Spacing.lg)) {
        Text("Ignored users", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.md))
        if (ignoredUsers.isEmpty()) {
            Text("You haven't ignored anyone.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(ignoredUsers) { mxid ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mxid, Modifier.weight(1f))
                        TextButton(onClick = { onUnignore(mxid) }) {
                            Text("Unignore")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySettingItem(title: String, subtitle: String, isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = isEnabled, onCheckedChange = onToggle)
    }
}