package org.mlm.frair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import kotlin.let

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            App()
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
