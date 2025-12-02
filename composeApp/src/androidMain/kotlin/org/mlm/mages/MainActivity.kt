package org.mlm.mages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.nav.handleMatrixLink
import org.mlm.mages.nav.parseMatrixLink
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.push.PREF_INSTANCE
import org.mlm.mages.storage.provideAppDataStore
import org.mlm.mages.push.PushManager
import org.mlm.mages.push.PusherReconciler
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : ComponentActivity() {
    private val deepLinkRoomIds = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val deepLinks = deepLinkRoomIds.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val dataStore = provideAppDataStore(this)
        ensureCallNotificationChannel()

        handleIntent(intent)
        MagesPaths.init(this)


        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            val saved = UnifiedPush.getSavedDistributor(this)
            val dists = UnifiedPush.getDistributors(this)
            Log.i(
                "UP-Mages",
                "tryUseCurrentOrDefaultDistributor success=$success, " +
                        "savedDistributor=$saved, distributors=$dists"
            )

            if (success) {
                UnifiedPush.register(this, PREF_INSTANCE)
                lifecycleScope.launch {
                    runCatching { PusherReconciler.ensureServerPusherRegistered(this@MainActivity) }
                }
            } else {
                PushManager.registerWithDialog(this, PREF_INSTANCE)
            }
        }

        setContent {
            val service = remember { MatrixProvider.get(this) }
            App(dataStore = dataStore, service = service, deepLinks = deepLinks)
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.data?.let { uri ->
            if (uri.scheme == "mages" && uri.host == "room") {
                val roomId = uri.getQueryParameter("id")
                if (!roomId.isNullOrBlank()) {
                    deepLinkRoomIds.tryEmit(roomId)
                }
                return
            }

            val url = uri.toString()
            val link = parseMatrixLink(url)
            if (link !is org.mlm.mages.nav.MatrixLink.Unsupported) {
                lifecycleScope.launch {
                    val svc = MatrixProvider.get(this@MainActivity)
                    handleMatrixLink(
                        service = svc,
                        link = link,
                    ) { roomId, _ ->
                        deepLinkRoomIds.tryEmit(roomId)
                    }
                }
            }
        }
    }

    private fun ensureCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                "calls",
                "Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming calls"
                setSound(null, null) // TODO: sound configurable later
                enableVibration(true)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun hideSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // Use the cutout area on devices with a notch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
