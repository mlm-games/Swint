package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.theme.Spacing

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🔥", "💀")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    event: MessageEvent,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onMarkReadHere: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onReplyInThread: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            MessagePreview(event)
            Spacer(Modifier.height(Spacing.lg))
            QuickReactionsRow(onReact = { emoji -> onReact(emoji); onDismiss() })
            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
            Spacer(Modifier.height(Spacing.sm))

            if (isMine && event.sendState == SendState.Failed && onRetry != null) {
                ActionItem(Icons.Default.Refresh, "Retry send") { onRetry(); onDismiss() }
            }
            ActionItem(Icons.Default.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(event.body)); onDismiss() }
            ActionItem(Icons.AutoMirrored.Filled.Reply, "Reply") { onReply(); onDismiss() }
            if (onReplyInThread != null) {
                ActionItem(Icons.Default.Forum, "Reply in thread") { onReplyInThread(); onDismiss() }
            }
            ActionItem(Icons.Default.Bookmark, "Mark as read here") { onMarkReadHere(); onDismiss() }
            if (isMine && event.sendState != SendState.Failed) {
                ActionItem(Icons.Default.Edit, "Edit") { onEdit(); onDismiss() }
            }
            if (isMine) {
                ActionItem(Icons.Default.Delete, "Delete", MaterialTheme.colorScheme.error) { onDelete(); onDismiss() }
            }
        }
    }
}

@Composable
private fun MessagePreview(event: MessageEvent) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(Spacing.md)) {
            Text(event.sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(Spacing.xs))
            Text(event.body.take(150), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickReactionsRow(onReact: (String) -> Unit) {
    Text("Quick reactions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.lg), fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(Spacing.sm))
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(quickReactions) { emoji ->
            Surface(onClick = { onReact(emoji) }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
            }
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text, color = color) }, leadingContent = { Icon(icon, null, tint = color) }, modifier = Modifier.clickable { onClick() })
}