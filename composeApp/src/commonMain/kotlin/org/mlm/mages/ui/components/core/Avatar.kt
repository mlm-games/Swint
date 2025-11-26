package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.mlm.mages.ui.theme.Sizes

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