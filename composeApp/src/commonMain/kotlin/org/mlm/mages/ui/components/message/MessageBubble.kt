package org.mlm.mages.ui.components.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.AttachmentKind
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.matrix.SendState
import org.mlm.mages.platform.loadImageBitmapFromPath
import org.mlm.mages.ui.components.core.MarkdownText
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration
import org.mlm.mages.ui.util.formatTime

@Composable
fun MessageBubble(
    isMine: Boolean,
    body: String,
    sender: String?,
    timestamp: Long,
    grouped: Boolean,
    modifier: Modifier = Modifier,
    reactionChips: List<ReactionChip> = emptyList(),
    eventId: String? = null,
    replyPreview: String? = null,
    replySender: String? = null,
    sendState: SendState? = null,
    thumbPath: String? = null,
    attachmentKind: AttachmentKind? = null,
    durationMs: Long? = null,
    lastReadByOthersTs: Long? = null,
    onLongPress: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    onOpenAttachment: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = if (grouped) 2.dp else 6.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        if (!isMine && !grouped && !sender.isNullOrBlank()) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 2.dp)
            )
        }

        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = bubbleShape(isMine, grouped),
            tonalElevation = if (isMine) 3.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = Sizes.bubbleMaxWidth)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
        ) {
            Column(Modifier.padding(Spacing.md)) {
                if (!replyPreview.isNullOrBlank()) {
                    ReplyPreview(replySender, replyPreview)
                    Spacer(Modifier.height(Spacing.sm))
                }

                AttachmentThumbnail(thumbPath, attachmentKind, durationMs, onOpenAttachment)

                if (body.isNotBlank()) {
                    MarkdownText(
                        text = body,
                        color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = formatTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = Spacing.xs)
                )

                if (isMine && sendState == SendState.Failed) {
                    FailedIndicator()
                }
            }
        }

        ReactionChipsRow(chips = reactionChips, onClick = onReact)
    }
}

private fun bubbleShape(isMine: Boolean, grouped: Boolean) = RoundedCornerShape(
    topStart = if (!isMine && grouped) 4.dp else 16.dp,
    topEnd = if (isMine && grouped) 4.dp else 16.dp,
    bottomStart = if (isMine) 16.dp else 4.dp,
    bottomEnd = if (!isMine) 16.dp else 4.dp
)

@Composable
private fun ReplyPreview(sender: String?, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = buildString {
                    if (!sender.isNullOrBlank()) { append(sender); append(": ") }
                    append(body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    thumbPath: String?,
    attachmentKind: AttachmentKind?,
    durationMs: Long?,
    onOpen: (() -> Unit)?
) {
    if (thumbPath == null || (attachmentKind != AttachmentKind.Image && attachmentKind != AttachmentKind.Video)) return

    val bmp = loadImageBitmapFromPath(thumbPath)
    if (bmp != null) {
        Box(
            modifier = Modifier
                .widthIn(max = Sizes.bubbleMaxWidth)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = onOpen != null) { onOpen?.invoke() }
        ) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxWidth())
            if (attachmentKind == AttachmentKind.Video && durationMs != null) {
                DurationBadge(durationMs, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Text(
                text = if (attachmentKind == AttachmentKind.Video) "Video" else "Image",
                modifier = Modifier.padding(Spacing.md),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DurationBadge(ms: Long, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = formatDuration(ms),
            color = MaterialTheme.colorScheme.surface,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FailedIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Failed to send. Long-press to retry.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}