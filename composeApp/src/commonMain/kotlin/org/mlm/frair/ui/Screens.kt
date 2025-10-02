package org.mlm.frair.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
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
import org.mlm.frair.ui.screens.LoginScreen
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (val s = state.screen) {
                                is Screen.Login -> "Frair"
                                is Screen.Rooms -> "Rooms"
                                is Screen.Room -> s.room.name
                                is Screen.Security -> "Security"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Show sync banner if present
                        if (state.syncBanner != null) {
                            Text(
                                state.syncBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Show offline banner if offline
                        if (state.offlineBanner != null) {
                            Text(
                                state.offlineBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (state.syncBanner != null) {
                            Text(
                                state.syncBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (state.screen is Screen.Room || state.screen is Screen.Security) {
                        TextButton(onClick = { onIntent(Intent.Back) }) { Text("Back") }
                    }
                },
                actions = {
                    when (state.screen) {
                        is Screen.Rooms -> Row {
                            TextButton(
                                enabled = !state.isBusy,
                                onClick = { onIntent(Intent.RefreshRooms) }
                            ) {
                                Text(if (state.isBusy) "…" else "Refresh")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onIntent(Intent.OpenSecurity) }) { Text("Security") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onIntent(Intent.Logout) }) { Text("Log out") }
                        }
                        is Screen.Room -> TextButton(
                            enabled = !state.isBusy,
                            onClick = { onIntent(Intent.SyncNow) }
                        ) {
                            Text(if (state.isBusy) "…" else "Sync")
                        }
                        else -> {}
                    }
                }
            )
            if (state.isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                ) {}
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val s = state.screen) {
            is Screen.Login -> LoginScreen(state, padding, onIntent)
            is Screen.Rooms -> RoomsScreen(state, padding, onIntent)
            is Screen.Room -> RoomScreen(state, padding, onIntent)
            is Screen.Security -> SecurityScreen(state, padding, onIntent)
        }
    }
}
