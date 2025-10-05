package org.mlm.frair.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.frair.AppState
import org.mlm.frair.Intent
import org.mlm.frair.Screen
import org.mlm.frair.ui.components.SasDialog
import org.mlm.frair.ui.screens.LoginScreen
import org.mlm.frair.ui.screens.MediaCacheScreen
import org.mlm.frair.ui.screens.RoomScreen
import org.mlm.frair.ui.screens.RoomsScreen
import org.mlm.frair.ui.screens.SecurityScreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RootScaffold(state: AppState, onIntent: (Intent) -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (state.screen) {
            is Screen.Login -> LoginScreen(state, padding, onIntent)
            is Screen.Rooms -> RoomsScreen(state, PaddingValues.Zero, onIntent)
            is Screen.Room -> RoomScreen(state, padding, onIntent)
            is Screen.Security -> SecurityScreen(state, padding, onIntent)
            is Screen.MediaCache -> MediaCacheScreen(state, padding, onIntent)
        }
    }
}
