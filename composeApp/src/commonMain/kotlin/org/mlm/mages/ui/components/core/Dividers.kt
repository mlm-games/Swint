package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

@Composable
fun DayDivider(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Spacing.sm),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
fun UnreadDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm, horizontal = Spacing.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(horizontal = Spacing.md)
        ) {
            Text(
                text = "Unread messages",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}