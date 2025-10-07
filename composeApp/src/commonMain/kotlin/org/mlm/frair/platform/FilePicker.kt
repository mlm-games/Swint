package org.mlm.frair.platform

import androidx.compose.runtime.Composable
import org.mlm.frair.ui.components.AttachmentData

interface FilePickerController {
    fun pick(mimeFilter: String) // e.g. "image/*", "video/*", "*/*"
}

@Composable
expect fun rememberFilePicker(onPicked: (AttachmentData?) -> Unit): FilePickerController