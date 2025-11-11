package org.mlm.frair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.mlm.frair.platform.FrairPaths
import org.mlm.frair.storage.provideAppDataStore

class MainActivity : ComponentActivity() {
    private val deepLinkRoomIds = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val deepLinks = deepLinkRoomIds.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val dataStore = provideAppDataStore(this)

        handleIntent(intent)
        FrairPaths.init(this)
        setContent {
            App(dataStore = dataStore, deepLinks = deepLinks)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.data?.let { uri ->
            if (uri.scheme == "frair" && uri.host == "room") {
                val roomId = uri.getQueryParameter("id")
                if (!roomId.isNullOrBlank()) {
                    deepLinkRoomIds.tryEmit(roomId)
                }
            }
        }
    }
}
