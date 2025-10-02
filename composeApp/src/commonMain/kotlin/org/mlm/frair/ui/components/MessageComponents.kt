package org.mlm.frair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MessageBubble(
    isMine: Boolean,
    body: String,
    sender: String?,
    timestamp: Long,
    grouped: Boolean,
    reactions: Set<String> = emptySet(),
    onLongPress: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null
) {
    val (replyPreview, bodyShown) = remember(body) { parseReplyFallback(body) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (grouped) 2.dp else 6.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        // Sender name for group chats
        if (!isMine && !grouped && !sender.isNullOrBlank()) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        // Message bubble with smooth corners
        Surface(
            color = if (isMine)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = if (!isMine && grouped) 4.dp else 16.dp,
                topEnd = if (isMine && grouped) 4.dp else 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (!isMine) 16.dp else 4.dp
            ),
            tonalElevation = if (isMine) 3.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = { },
                    onLongClick = onLongPress
                )
        ) {
            Column(Modifier.padding(12.dp)) {
                // Reply preview if present
                if (!replyPreview.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(24.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = replyPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Message content with markdown
                MarkdownText(
                    text = bodyShown,
                    color = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Timestamp
                Text(
                    text = formatTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Reactions
        if (reactions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 280.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                reactions.forEach { emoji ->
                    InputChip(
                        selected = false,
                        onClick = { onReact?.invoke(emoji) },
                        label = { Text(emoji) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}