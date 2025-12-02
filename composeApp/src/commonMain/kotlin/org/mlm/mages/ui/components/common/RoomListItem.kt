package org.mlm.mages.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.mlm.mages.ui.LastMessageType
import org.mlm.mages.ui.RoomListItemUi
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListItem(
    item: RoomListItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar + unread badge
            Box {
                RoomAvatar(
                    name = item.name,
                    avatarUrl = item.avatarUrl,
                    isDm = item.isDm,
                    isEncrypted = item.isEncrypted
                )

                if (item.unreadCount > 0) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                    ) {
                        Text(
                            if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (item.unreadCount > 0)
                            FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (item.isFavourite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favourite",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (item.isEncrypted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Last message preview row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val preview = formatMessagePreview(
                        type = item.lastMessageType,
                        body = item.lastMessageBody,
                        sender = item.lastMessageSender,
                        isDm = item.isDm
                    )

                    preview.icon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                    }

                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.unreadCount > 0)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (item.unreadCount > 0)
                            FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            // Time label
            val timeLabel = remember(item.lastMessageTs) {
                item.lastMessageTs?.let(::formatRelativeTime)
            }
            if (timeLabel != null) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoomAvatar(
    name: String,
    avatarUrl: String?,
    isDm: Boolean,
    isEncrypted: Boolean
) {
    // You can later add actual image loading from avatarUrl here
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        modifier = Modifier.size(52.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isDm) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Avatar(
                    name = name,
                    size = 52.dp,
                    shape = CircleShape
                )
            }
        }
    }
}

private data class MessagePreview(
    val text: String,
    val icon: ImageVector? = null
)

private fun formatMessagePreview(
    type: LastMessageType,
    body: String?,
    sender: String?,
    isDm: Boolean
): MessagePreview {
    val senderPrefix = if (!isDm && sender != null) {
        "${formatSenderName(sender)}: "
    } else ""

    return when (type) {
        LastMessageType.Text -> {
            val text = body?.take(100)?.replace('\n', ' ') ?: "No messages yet"
            MessagePreview(text = senderPrefix + text)
        }
        LastMessageType.Image -> MessagePreview(
            text = senderPrefix + "Photo",
            icon = Icons.Default.Image
        )
        LastMessageType.Video -> MessagePreview(
            text = senderPrefix + "Video",
            icon = Icons.Default.Videocam
        )
        LastMessageType.Audio -> MessagePreview(
            text = senderPrefix + "Audio message",
            icon = Icons.Default.Mic
        )
        LastMessageType.File -> MessagePreview(
            text = senderPrefix + (body?.takeIf { !it.startsWith("mxc://") } ?: "File"),
            icon = Icons.Default.AttachFile
        )
        LastMessageType.Sticker -> MessagePreview(
            text = senderPrefix + "Sticker",
            icon = Icons.Default.EmojiEmotions
        )
        LastMessageType.Location -> MessagePreview(
            text = senderPrefix + "Location",
            icon = Icons.Default.LocationOn
        )
        LastMessageType.Poll -> MessagePreview(
            text = senderPrefix + "Poll",
            icon = Icons.Default.Poll
        )
        LastMessageType.Call -> MessagePreview(
            text = senderPrefix + "Call",
            icon = Icons.Default.Call
        )
        LastMessageType.Encrypted -> MessagePreview(
            text = "🔒 Encrypted message"
        )
        LastMessageType.Redacted -> MessagePreview(
            text = senderPrefix + "Message deleted"
        )
        LastMessageType.Unknown -> MessagePreview(
            text = body?.take(100)?.replace('\n', ' ') ?: "Message"
        )
    }
}

private fun formatSenderName(sender: String): String {
    return sender
        .removePrefix("@")
        .substringBefore(":")
        .take(15)
}

@OptIn(ExperimentalTime::class)
fun formatRelativeTime(timestamp: Long): String {
    val now = Clock.System.now()
    val messageTime = Instant.fromEpochMilliseconds(timestamp)
    val duration = now - messageTime

    val localNow = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val localMessage = messageTime.toLocalDateTime(TimeZone.currentSystemDefault())

    return when {
        duration.inWholeMinutes < 1 -> "now"
        duration.inWholeHours < 1   -> "${duration.inWholeMinutes}m"
        localNow.date == localMessage.date -> {
            "%02d:%02d".format(localMessage.hour, localMessage.minute)
        }
        localNow.date.minus(1, DateTimeUnit.DAY) == localMessage.date -> "Yesterday"
        duration.inWholeDays < 7 -> {
            localMessage.dayOfWeek.name.lowercase()
                .replaceFirstChar { it.uppercase() }
                .take(3)
        }
        localNow.year == localMessage.year -> {
            "${localMessage.day} " +
                    localMessage.month.name.lowercase()
                        .replaceFirstChar { it.uppercase() }
                        .take(3)
        }
        else -> {
            "${localMessage.day}/${localMessage.month.number}/${localMessage.year.toString().takeLast(2)}"
        }
    }
}