package org.mlm.frair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import org.mlm.frair.storage.provideAppDataStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val dataStore = provideAppDataStore(this)

        handleIntent(intent)
        setContent {
            App(dataStore)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
//        intent.data?.let { uri ->
//            when {
//                uri.scheme == "frair" && uri.host == "room" -> {
//                    val roomId = uri.getQueryParameter("id")
//                    roomId?.let {
//                        store.dispatch(org.mlm.frair.Intent.OpenRoomById(it))
//                    }
//                }
//            }
//        }
    }
}
