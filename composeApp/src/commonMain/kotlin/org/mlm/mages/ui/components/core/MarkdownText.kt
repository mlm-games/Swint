package org.mlm.mages.ui.components.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.mlm.mages.ui.util.parseMarkdown

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val styled = remember(text) { parseMarkdown(text) }
    Text(text = styled, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}