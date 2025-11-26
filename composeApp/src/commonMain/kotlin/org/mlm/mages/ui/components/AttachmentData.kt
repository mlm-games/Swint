package org.mlm.mages.ui.components

data class AttachmentData(
    val path: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long
)