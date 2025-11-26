package org.mlm.mages.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun ReactionChipsRow(
    chips: List<ReactionChip>,
    modifier: Modifier = Modifier,
    onClick: ((String) -> Unit)? = null
) {
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(top = Spacing.xs).widthIn(max = Sizes.bubbleMaxWidth),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        chips.forEach { chip ->
            InputChip(
                selected = chip.mine,
                onClick = { onClick?.invoke(chip.key) },
                label = { Text("${chip.key} ${chip.count}") }
            )
        }
    }
}