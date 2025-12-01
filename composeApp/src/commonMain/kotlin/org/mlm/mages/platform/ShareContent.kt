package org.mlm.mages.platform

import androidx.compose.runtime.Composable

data class ShareContent(
    val text: String? = null,
    val filePath: String? = null,
    val mimeType: String? = null,
    val subject: String? = null,
)

@Composable
expect fun rememberShareHandler(): (ShareContent) -> Unit