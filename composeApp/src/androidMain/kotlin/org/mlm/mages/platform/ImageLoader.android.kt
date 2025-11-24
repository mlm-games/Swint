package org.mlm.mages.platform

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual fun loadImageBitmapFromPath(path: String): ImageBitmap? {
    if (!File(path).exists()) return null
    return BitmapFactory.decodeFile(path)?.asImageBitmap()
}