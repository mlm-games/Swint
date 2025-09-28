package org.mlm.frair

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.mlm.frair.matrix.createMatrixPort
import org.mlm.frair.ui.MainTheme
import org.mlm.frair.ui.RootScaffold

@Composable
fun App() {
    MainTheme {
        val store = remember {
            val port = createMatrixPort("https://matrix.org")
            AppStore(MatrixService(port))
        }
        val state by store.state.collectAsState()
        RootScaffold(state = state) { store.dispatch(it) }
    }
}