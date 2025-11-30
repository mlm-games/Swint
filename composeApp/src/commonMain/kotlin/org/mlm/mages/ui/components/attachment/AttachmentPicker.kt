package org.mlm.mages.ui.components.attachment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPicker(
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onDismiss: () -> Unit,
    onCreatePoll: (() -> Unit)? = null,
    onShareLocation: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            Text(
                "Share",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            // Media section
            AttachmentOption(
                Icons.Default.Image,
                "Photo",
                "Send an image from gallery"
            ) { onPickImage(); onDismiss() }

            AttachmentOption(
                Icons.Default.VideoLibrary,
                "Video",
                "Share a video"
            ) { onPickVideo(); onDismiss() }

            AttachmentOption(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                "Document",
                "Send a file"
            ) { onPickDocument(); onDismiss() }

            // Interactive content
            if (onCreatePoll != null || onShareLocation != null) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

                Text(
                    "Interactive",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )

                if (onCreatePoll != null) {
                    AttachmentOption(
                        Icons.Default.Poll,
                        "Poll",
                        "Create a poll for the room"
                    ) { onCreatePoll(); onDismiss() }
                }

                if (onShareLocation != null) {
                    AttachmentOption(
                        Icons.Default.LocationOn,
                        "Location",
                        "Share your live location"
                    ) { onShareLocation(); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ) {
                Box(Modifier.padding(Spacing.md)) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}