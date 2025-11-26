package org.mlm.mages.ui.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
fun formatTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d".format(local.hour, local.minute)
}

@OptIn(ExperimentalTime::class)
fun formatDate(timestampMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestampMs)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val yesterday = Clock.System.now().minus(1.days).toLocalDateTime(TimeZone.currentSystemDefault()).date

    return when (localDateTime.date) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> {
            val month = localDateTime.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            "${localDateTime.day} $month ${localDateTime.year}"
        }
    }
}

fun formatDuration(ms: Long): String {
    val secs = (ms / 1000).toInt()
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}

fun formatTypingText(users: List<String>): String = when (users.size) {
    0 -> ""
    1 -> "${users[0]} is typing"
    2 -> "${users[0]} and ${users[1]} are typing"
    else -> "${users[0]}, ${users[1]} and ${users.size - 2} others are typing"
}

fun formatSeenBy(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "Seen by ${names[0]}"
    2 -> "Seen by ${names[0]} and ${names[1]}"
    else -> "Seen by ${names[0]}, ${names[1]} +${names.size - 2}"
}