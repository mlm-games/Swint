package org.mlm.frair

import androidx.compose.runtime.*
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.matrix.createMatrixPort
import org.mlm.frair.ui.MainTheme
import org.mlm.frair.ui.RootScaffold

@Composable
fun App() {
    MainTheme {
        val store = remember {
            val port: MatrixPort = createMatrixPort("https://matrix-client.matrix.org")
            AppStore(MatrixService(port))
        }
        val state by store.state.collectAsState()
        RootScaffold(state = state) { store.dispatch(it) }
    }
}