package org.mlm.mages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProgressIndicatorDefaults
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
    eventId: String? = null,
    sendState: org.mlm.mages.matrix.SendState? = null,
    onLongPress: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    showTicks: Boolean = false,
    lastReadByOthersTs: Long? = null,
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

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = formatTime(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMine)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    if (isMine && showTicks) {
                        Spacer(Modifier.width(6.dp))
                        val (icon, desc, tint) = when (sendState) {
                            org.mlm.mages.matrix.SendState.Sending,
                            org.mlm.mages.matrix.SendState.Enqueued,
                            org.mlm.mages.matrix.SendState.Retrying -> Triple(
                                Icons.Default.Schedule,
                                "Sending",
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            org.mlm.mages.matrix.SendState.Failed -> Triple(
                                Icons.Default.Close,
                                "Failed",
                                MaterialTheme.colorScheme.error
                            )
                            org.mlm.mages.matrix.SendState.Sent -> Triple(
                                Icons.Default.Check,
                                "Sent",
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            null -> {
                                // Fallback to old heuristic (eventId present -> sent)
                                val isPending = eventId.isNullOrBlank()
                                if (isPending)
                                    Triple(Icons.Default.Schedule, "Sending",
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                                else
                                    Triple(Icons.Default.Check, "Sent",
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                            }
                        }
                        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(12.dp))

                    }
                }
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


data class AttachmentData(
    val path: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable

fun AttachmentPicker(
        onPickImage: () -> Unit,
        onPickVideo: () -> Unit,
        onPickDocument: () -> Unit,
        onDismiss: () -> Unit
    ) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Choose attachment type",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            AttachmentOption(
                icon = Icons.Default.Image,
                title = "Photo",
                subtitle = "Send an image from gallery",
                onClick = { onPickImage(); onDismiss() }
            )

            AttachmentOption(
                icon = Icons.Default.VideoLibrary,
                title = "Video",
                subtitle = "Share a video",
                onClick = { onPickVideo(); onDismiss() }
            )

            AttachmentOption(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = "Document",
                subtitle = "Send a file",
                onClick = { onPickDocument(); onDismiss() }
            )

//            AttachmentOption(
//                icon = Icons.Default.LocationOn,
//                title = "Location",
//                subtitle = "Share your location",
//                onClick = {
//                    onDismiss()
//                }
//            )
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ) {
                Box(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
fun AttachmentProgress(
    fileName: String,
    progress: Float,
    totalSize: Long,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(40.dp),
            color = ProgressIndicatorDefaults.circularColor,
            strokeWidth = ProgressIndicatorDefaults.CircularStrokeWidth,
            trackColor = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
            strokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatBytes((totalSize * progress).toLong())} / ${formatBytes(totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "Cancel")
            }
        }

        LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
        color = ProgressIndicatorDefaults.linearColor,
        trackColor = ProgressIndicatorDefaults.linearTrackColor,
        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
    }
}

@Composable
fun MessageStatusLine(text: String, isMine: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (isMine)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMine)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun SeenByChip(names: List<String>) {
    if (names.isEmpty()) return
    val label = when (names.size) {
        1 -> "Seen by ${names[0]}"
        2 -> "Seen by ${names[0]} and ${names[1]}"
        else -> "Seen by ${names[0]}, ${names[1]} +${names.size - 2}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}