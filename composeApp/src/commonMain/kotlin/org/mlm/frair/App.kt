package org.mlm.frair

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.mlm.frair.matrix.createMatrixPort
import org.mlm.frair.ui.MainTheme
import org.mlm.frair.ui.RootScaffold

@Composable
fun App(
    dataStore: DataStore<Preferences>,
    deepLinks: Flow<String>? = null
) {
    MainTheme {
        val store = remember {
            val port = createMatrixPort("https://matrix.org")
            AppStore(MatrixService(port), dataStore)
        }
        // Consume deep-link room ids
        LaunchedEffect(deepLinks) {
            deepLinks?.let { flow ->
                flow.collectLatest { roomId ->
                    store.dispatch(Intent.OpenRoomById(roomId))
                }
            }
        }
        val state by store.state.collectAsState()
        RootScaffold(state = state) { store.dispatch(it) }
    }
}