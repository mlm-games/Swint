package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import org.mlm.mages.ui.components.AttachmentData

interface FilePickerController {
    fun pick(mimeFilter: String) // e.g. "image/*", "video/*", "*/*"
}

@Composable
expect fun rememberFilePicker(onPicked: (AttachmentData?) -> Unit): FilePickerController