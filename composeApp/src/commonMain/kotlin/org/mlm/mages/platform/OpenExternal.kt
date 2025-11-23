package org.mlm.mages.platform

import androidx.compose.runtime.Composable

@Composable
expect fun rememberFileOpener(): (String, String?) -> Boolean
