@file:Suppress("AssignedValueIsNeverRead")

package org.mlm.mages

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mages.composeapp.generated.resources.Res
import mages.composeapp.generated.resources.ic_notif
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.jetbrains.compose.resources.painterResource
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.Notifier
import org.mlm.mages.storage.loadBoolean
import org.mlm.mages.storage.loadString
import org.mlm.mages.storage.provideAppDataStore
import org.mlm.mages.storage.saveBoolean
import java.io.IOException

private const val PREF_START_IN_TRAY = "pref.startInTray"



fun main() = application {
    val dataStore = provideAppDataStore()
    val initialStartInTray = remember {
        runBlocking { loadBoolean(dataStore, PREF_START_IN_TRAY) ?: false }
    }
    var startInTray by remember { mutableStateOf(initialStartInTray) }
    var showWindow by remember { mutableStateOf(!startInTray) }
    val scope = rememberCoroutineScope()

    var service: MatrixService? = null

    fun get(): MatrixService {
        service?.let { return it }
        synchronized(this) {
            service?.let { return it }
            // Ensure store paths
            MagesPaths.init()
            // Use saved homeserver or sensible default
            val hs = runBlocking { loadString(dataStore, "homeserver") } ?: "https://matrix.org"
            val s = MatrixService(createMatrixPort(hs))
            service = s
            return s
        }
    }

    val trayIcon = painterResource(Res.drawable.ic_notif)

    Tray(
//        icon = painterResource("fastlane/android/metadata/en-US/images/icon.svg"), // fallback
        icon = trayIcon,
        tooltip = "Mages",
        onAction = { showWindow = true },
        menu = {
            Item("Show",
//                trayIcon, shortcut = KeyShortcut(Key.A, true) // TODO: Check why this doesn't build (most likely not supported for wayland yet)
            ) { showWindow = true; }
            Separator()
            Item(if (startInTray) "✓ Minimize to tray on launch" else "Minimize to tray on launch") {
                startInTray = !startInTray
                scope.launch { saveBoolean(dataStore, PREF_START_IN_TRAY, startInTray) }
            }
            Separator()
            Item("Quit") { exitApplication() }
        }
    )

    // hide on close (go to tray)
    if (showWindow) {
        val windowState = rememberWindowState()
        Window(
            onCloseRequest = { showWindow = false },
            state = windowState,
            title = "Mages"
        ) {
            val window = this.window

            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        Notifier.setWindowFocused(true)
                    }

                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        Notifier.setWindowFocused(false)
                    }
                }
                window.addWindowFocusListener(listener)

                Notifier.setWindowFocused(window.isFocused)

                onDispose {
                    window.removeWindowFocusListener(listener)
                    Notifier.setWindowFocused(false)  // Assume unfocused when window closes
                }
            }

            App(dataStore, get())
        }
    }

    MagesPaths.init()
}


object NotifierImpl {
    private var conn: DBusConnection? = null

    private fun ensure(): DBusConnection? {
        if (conn?.isConnected == true) {
            return conn
        }
        return try {
            DBusConnectionBuilder.forSessionBus().build().also { conn = it }
        } catch (_: IOException) {
            // Silently fails if DBus is not available
            null
        }
    }

    fun notify(app: String, title: String, body: String, desktopEntry: String? = "org.mlm.mages") {
        val c = ensure() ?: return
        try {
            val notifications = c.getRemoteObject(
                "org.freedesktop.Notifications",
                "/org/freedesktop/Notifications",
                Notifications::class.java
            )

            val hints = HashMap<String, Variant<*>>()
            if (desktopEntry != null) {
                hints["desktop-entry"] = Variant(desktopEntry)
            }

            notifications.Notify(
                app,
                UInt32(0),
                "", // icon
                title,
                body,
                emptyArray(), // actions
                hints,
                -1 // default timeout
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // DBus interface def
    @DBusInterfaceName("org.freedesktop.Notifications")
    interface Notifications : DBusInterface {
        fun Notify(
            appName: String,
            replacesId: UInt32,
            appIcon: String,
            summary: String,
            body: String,
            actions: Array<String>,
            hints: Map<String, Variant<*>>,
            expireTimeout: Int
        ): UInt32
    }
}