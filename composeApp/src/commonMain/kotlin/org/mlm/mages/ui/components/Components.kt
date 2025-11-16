package org.mlm.mages.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Composable
fun Avatar(initials: String, size: Dp = 36.dp, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.size(size)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(initials.take(2), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// Lightweight fallback reply parser: looks for quoted lines ("> ") then a blank line.
fun parseReplyFallback(body: String): Pair<String?, String> {
    val lines = body.lines()
    if (lines.isEmpty()) return null to body
    val quoteLines = mutableListOf<String>()
    var idx = 0
    while (idx < lines.size && lines[idx].startsWith(">")) {
        quoteLines += lines[idx].removePrefix(">").trim()
        idx++
    }
    if (idx < lines.size && lines[idx].isBlank() && quoteLines.isNotEmpty()) idx++ // skip blank after quote
    val rest = lines.drop(idx).joinToString("\n").ifBlank { body }
    val preview = quoteLines.firstOrNull()
    return preview to rest
}

@Composable
fun ReactionBar(emojis: Set<String>, onClick: ((String) -> Unit)? = null) {
    if (emojis.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        emojis.forEach { e ->
            AssistChip(
                onClick = { onClick?.invoke(e) },
                label = { Text(e) }
            )
        }
    }
}

@Composable
fun ActionBanner(replyingTo: MessageEvent?, editing: MessageEvent?, onCancelReply: () -> Unit, onCancelEdit: () -> Unit) {
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
fun UnreadDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                "Unread messages",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
fun OutboxChips(items: List<SendIndicator>) { // Old
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
fun MarkdownText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val styled = remember(text) { parseMarkdownSafe(text) }
    Text(styled, color = color, style = MaterialTheme.typography.bodyMedium)
}

private fun parseMarkdownSafe(input: String): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val codeStyle = SpanStyle(background = Color(0x33FFFFFF))
    val linkStyle = SpanStyle(color = Color(0xFF7E57C2), textDecoration = TextDecoration.Underline)

    val out = StringBuilder(input.length)
    data class Range(val start: Int, val end: Int, val style: SpanStyle)
    val spans = mutableListOf<Range>()

    var i = 0
    var boldStart = -1
    var italicStart = -1
    var codeStart = -1

    fun startsWith(token: String) = input.regionMatches(i, token, 0, token.length)
    fun isLinkChar(c: Char) = !c.isWhitespace()

    while (i < input.length) {
        when {
            // inline code `
            input[i] == '`' -> {
                if (codeStart >= 0) {
                    val start = codeStart
                    val end = out.length
                    if (end > start) spans += Range(start, end, codeStyle)
                    codeStart = -1
                } else {
                    codeStart = out.length
                }
                i += 1 // consume `
            }

            // bold ** .. **
            startsWith("**") -> {
                if (boldStart >= 0) {
                    val start = boldStart
                    val end = out.length
                    if (end > start) spans += Range(start, end, boldStyle)
                    boldStart = -1
                } else {
                    boldStart = out.length
                }
                i += 2 // consume **
            }

            // italic * .. * (ignore if currently in bold opener like *** – kept simple)
            input[i] == '*' -> {
                if (italicStart >= 0) {
                    val start = italicStart
                    val end = out.length
                    if (end > start) spans += Range(start, end, italicStyle)
                    italicStart = -1
                } else {
                    italicStart = out.length
                }
                i += 1 // consume *
            }

            // bare links http(s)://...
            startsWith("http://") || startsWith("https://") -> {
                val start = out.length
                val j0 = i
                var j = i
                while (j < input.length && isLinkChar(input[j])) j++
                out.append(input, j0, j)
                val end = out.length
                if (end > start) spans += Range(start, end, linkStyle)
                i = j
            }

            else -> {
                // regular char
                out.append(input[i])
                i += 1
            }
        }
    }

    return buildAnnotatedString {
        append(out.toString())
        val textLen = out.length
        for (r in spans) {
            val s = r.start.coerceIn(0, textLen)
            val e = r.end.coerceIn(s, textLen)
            if (e > s) addStyle(r.style, s, e)
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
fun formatTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}