package org.mlm.mages.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

actual fun loadImageBitmapFromPath(path: String): ImageBitmap? {
    return try {
        val bytes = File(path).readBytes()
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }
}