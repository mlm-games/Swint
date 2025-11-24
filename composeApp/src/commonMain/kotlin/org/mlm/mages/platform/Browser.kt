package org.mlm.mages.platform

import androidx.compose.runtime.Composable

@Composable
expect fun rememberOpenBrowser(): (String) -> Boolean
