package org.mlm.mages.ui.components.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.mlm.mages.ui.theme.Spacing

@Composable
fun PrivacyTab() {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text("Privacy Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(Spacing.md))
                PrivacySettingItem("Read Receipts", "Let others know when you've read their messages", true) {}
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                PrivacySettingItem("Typing Indicators", "Show when you're typing a message", true) {}
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                PrivacySettingItem("Message History", "Share message history with new members", false) {}
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