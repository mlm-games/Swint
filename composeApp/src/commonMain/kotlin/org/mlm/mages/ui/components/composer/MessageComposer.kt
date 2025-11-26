package org.mlm.mages.ui.components.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun MessageComposer(
    value: String,
    enabled: Boolean,
    isOffline: Boolean,
    replyingTo: MessageEvent?,
    editing: MessageEvent?,
    currentAttachment: AttachmentData?,
    isUploadingAttachment: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    onCancelUpload: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.Bottom
        ) {
            AnimatedVisibility(visible = onAttach != null && !isUploadingAttachment) {
                IconButton(onClick = { onAttach?.invoke() }) {
                    Icon(Icons.Default.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled && !isUploadingAttachment,
                placeholder = { ComposerPlaceholder(isUploadingAttachment, isOffline, editing, replyingTo) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 5
            )

            Spacer(Modifier.width(Spacing.sm))

            FilledIconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank() && !isUploadingAttachment,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isUploadingAttachment) {
                    CircularProgressIndicator(modifier = Modifier.size(Sizes.iconMedium), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun ComposerPlaceholder(isUploading: Boolean, isOffline: Boolean, editing: MessageEvent?, replyingTo: MessageEvent?) {
    Text(
        text = when {
            isUploading -> "Uploading..."
            isOffline -> "Offline - messages queued"
            editing != null -> "Edit message..."
            replyingTo != null -> "Type reply..."
            else -> "Type a message..."
        }
    )
}