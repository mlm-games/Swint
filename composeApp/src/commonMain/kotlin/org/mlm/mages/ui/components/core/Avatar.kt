package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import org.mlm.mages.ui.theme.Sizes
import java.util.Locale

/**
 * Unified avatar component reducing duplication across screens.
 * Used in RoomCard, MessageBubble, ThreadScreen, RoomInfoScreen, etc.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatarSmall,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val initials = remember(name) { extractInitials(name) }

    Surface(
        color = containerColor,
        shape = shape,
        modifier = modifier.size(size)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = initials,
                style = when {
                    size >= Sizes.avatarLarge -> MaterialTheme.typography.titleLarge
                    size >= Sizes.avatarMedium -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.labelLarge
                },
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

/**
 * Avatar with explicit initials (for when name parsing isn't desired).
 */
@Composable
fun Avatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatarSmall,
    color: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.size(size)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(initials.take(2), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Extracts initials from a name or Matrix ID.
 * @user:server.com -> U
 * Display Name -> DN
 * single -> S
 */
fun extractInitials(name: String): String {
    val clean = name.trim()

    // Handle Matrix IDs
    if (clean.startsWith("@")) {
        val localpart = clean.substringAfter("@").substringBefore(":")
        return localpart.take(2).uppercase()
    }

    // Handle display names
    val words = clean.split(" ").filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

/**
 * Formats a Matrix ID to a display name.
 * @user:server.com -> user
 */
fun formatDisplayName(mxid: String): String {
    return mxid.substringAfter("@").substringBefore(":")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}