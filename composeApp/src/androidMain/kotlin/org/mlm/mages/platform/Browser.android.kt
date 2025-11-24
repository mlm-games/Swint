
package org.mlm.mages.platform

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberOpenBrowser(): (String) -> Boolean {
    val ctx = LocalContext.current
    val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
    return { url ->
        runCatching { tabs.launchUrl(ctx, Uri.parse(url)) }.isSuccess
    }
}