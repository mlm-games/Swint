package org.mlm.mages.ui.base

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Centralized snackbar management
 */
class SnackbarController(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope
) {
    fun show(message: String) {
        scope.launch {
            hostState.showSnackbar(message)
        }
    }
    
    fun showError(message: String) {
        scope.launch {
            hostState.showSnackbar("Error: $message")
        }
    }
}

@Composable
fun rememberSnackbarController(): SnackbarController {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember { SnackbarController(hostState, scope) }
}