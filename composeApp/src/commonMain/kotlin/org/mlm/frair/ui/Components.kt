package org.mlm.frair.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.mlm.frair.MessageEvent
import org.mlm.frair.SendIndicator
import org.mlm.frair.matrix.SendState
import kotlin.time.ExperimentalTime

@Composable
fun Avatar(
    initials: String,
    size: Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(size)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(initials.take(2), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MessageBubble(
    isMine: Boolean,
    body: String,
    sender: String?,
    timestamp: Long,
    grouped: Boolean,
    onLongPress: (() -> Unit)? = null,
) {
    val bg = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val align = if (isMine) Arrangement.End else Arrangement.Start
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(Modifier.fillMaxWidth(), horizontalArrangement = align) {
        Surface(
            color = bg,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = if (isMine) 2.dp else 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onLongPress?.invoke() }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (!isMine && !grouped && !sender.isNullOrBlank()) {
                    Text(sender, style = MaterialTheme.typography.labelSmall, color = textColor)
                    Spacer(Modifier.height(2.dp))
                }
                MarkdownText(body, color = textColor)
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun OutboxChips(items: List<SendIndicator>) {
    if (items.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { ind ->
            val label = when (ind.state) {
                SendState.Enqueued -> "Queued"
                SendState.Sending -> "Sending"
                SendState.Retrying -> "Retry ${ind.attempts}"
                SendState.Sent -> "Sent"
                SendState.Failed -> "Failed"
            }
            AssistChip(
                onClick = {},
                enabled = ind.state != SendState.Sent,
                label = { Text(if (ind.error != null && ind.state == SendState.Failed) "$label: ${ind.error}" else label) }
            )
        }
    }
}

@Composable
fun MessageComposer(
    value: String,
    enabled: Boolean,
    hint: String = "Message",
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(hint) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSend,
            enabled = enabled && value.isNotBlank()
        ) { Text("Send") }
    }
}

@Composable
fun ActionBanner(
    replyingTo: MessageEvent?,
    editing: MessageEvent?,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    if (replyingTo != null) {
        Surface(color = bg, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${replyingTo.sender}: ", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Text(replyingTo.body.take(80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onCancelReply) { Text("Cancel") }
            }
        }
    } else if (editing != null) {
        Surface(color = bg, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Editing", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancelEdit) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun TypingIndicator(names: List<String>) {
    if (names.isEmpty()) return
    val text = when (names.size) {
        1 -> "${names[0]} is typing…"
        2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "${names[0]}, ${names[1]} and others are typing…"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    event: MessageEvent,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onMarkReadHere: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val quick = listOf("👍", "💀", "😂", "🫩", "🔥", "👀")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Message actions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { e ->
                    FilledTonalButton(onClick = { onReact(e); onDismiss() }) { Text(e) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(event.body))
                onCopy(); onDismiss()
            }) { Text("Copy") }
            TextButton(onClick = { onReply(); }) { Text("Reply") }
            TextButton(onClick = { onMarkReadHere() }) { Text("Mark read to here") }
            if (isMine) {
                TextButton(onClick = { onEdit() }) { Text("Edit") }
                TextButton(onClick = { onDelete() }) { Text("Delete") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/* ------------ Markdown (lightweight) ------------ */

@Composable
fun MarkdownText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val styled = remember(text) { parseMarkdown(text) }
    Text(styled, color = color, style = MaterialTheme.typography.bodyMedium)
}

private fun parseMarkdown(input: String): AnnotatedString {
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val italic = Regex("\\*(.+?)\\*")
    val code = Regex("`([^`]+)`")
    val link = Regex("(https?://\\S+)")

    var text = input
    val spans = mutableListOf<Pair<IntRange, SpanStyle>>()

    fun apply(regex: Regex, styleFor: (MatchResult) -> SpanStyle, strip: (String) -> String = { it }) {
        regex.findAll(text).forEach { m ->
            val full = m.value
            val inner = m.groupValues[1]
            val start = text.indexOf(full)
            if (start >= 0) {
                val end = start + inner.length
                spans += (start until end) to styleFor(m)
                text = text.replaceRange(start, start + full.length, strip(inner))
            }
        }
    }

    apply(bold, { SpanStyle(fontWeight = FontWeight.Bold) })
    apply(italic, { SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) })
    apply(code, { SpanStyle(background = Color(0x33FFFFFF)) })
    apply(link, { SpanStyle(color = Color.Magenta, textDecoration = TextDecoration.Underline) })

    return buildAnnotatedString {
        append(text)
        spans.forEach { (range, style) ->
            addStyle(style, range.first, range.last + 1)
        }
    }
}

@Composable
fun ListSkeleton(lines: Int = 6) {
    Column(Modifier.fillMaxWidth()) {
        repeat(lines) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .padding(vertical = 6.dp)
            ) {}
        }
    }
}

@Composable
fun DayDivider(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Divider(Modifier.weight(1f))
        Text(text, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
        Divider(Modifier.weight(1f))
    }
}

/* ------------ Utilities ------------ */

@OptIn(ExperimentalTime::class)
private fun formatTime(epochMs: Long): String {
    val instant = kotlin.time.Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}