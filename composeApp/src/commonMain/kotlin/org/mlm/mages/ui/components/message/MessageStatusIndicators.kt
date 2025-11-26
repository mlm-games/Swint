package org.mlm.mages.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatSeenBy

@Composable
fun MessageStatusLine(text: String, isMine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun SeenByChip(names: List<String>) {
    if (names.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                text = formatSeenBy(names),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}