package org.mlm.mages.ui.components.attachment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPicker(
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            Text("Choose attachment type", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.lg))
            Spacer(Modifier.height(Spacing.lg))
            AttachmentOption(Icons.Default.Image, "Photo", "Send an image from gallery") { onPickImage(); onDismiss() }
            AttachmentOption(Icons.Default.VideoLibrary, "Video", "Share a video") { onPickVideo(); onDismiss() }
            AttachmentOption(Icons.AutoMirrored.Filled.InsertDriveFile, "Document", "Send a file") { onPickDocument(); onDismiss() }
        }
    }
}

@Composable
private fun AttachmentOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Box(Modifier.padding(Spacing.md)) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}