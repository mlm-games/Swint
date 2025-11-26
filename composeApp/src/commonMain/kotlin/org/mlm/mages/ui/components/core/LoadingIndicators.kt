package org.mlm.mages.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.animation.rememberPulseAlpha
import org.mlm.mages.ui.animation.shimmer
import org.mlm.mages.ui.theme.Spacing

@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->
            val alpha = rememberPulseAlpha(index)
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun ShimmerList(itemCount: Int = 8, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(itemCount) { ShimmerListItem() }
    }
}

@Composable
private fun ShimmerListItem() {
    Row(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shimmer()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface)
        )
        Spacer(Modifier.width(Spacing.md))
        Column {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(16.dp)
                    .shimmer()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
            )
            Spacer(Modifier.height(Spacing.sm))
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(12.dp)
                    .shimmer()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }
    }
}