@file:Suppress("AssignedValueIsNeverRead")

package org.mlm.mages

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.Notifier
import org.mlm.mages.storage.*
import java.io.IOException
import javax.swing.SwingUtilities

private const val PREF_START_IN_TRAY = "pref.startInTray"

fun main() = application {
    MagesPaths.init()

    val dataStore = remember { provideAppDataStore() }

    val initialStartInTray = remember {
        runBlocking { loadBoolean(dataStore, PREF_START_IN_TRAY) ?: false }
    }

    var startInTray by remember { mutableStateOf(initialStartInTray) }
    var showWindow by remember { mutableStateOf(!startInTray) }

    val scope = rememberCoroutineScope()

    var service by remember { mutableStateOf<MatrixService?>(null) }
    val serviceLock = remember { Any() }

    fun getService(): MatrixService {
        service?.let { return it }
        return synchronized(serviceLock) {
            service?.let { return it }

            val hs = runBlocking { loadString(dataStore, "homeserver") } ?: "https://matrix.org"
            MatrixService(createMatrixPort(hs)).also { created ->
                service = created
            }
        }
    }

    val windowState = rememberWindowState()

    val tray: SystemTray? = remember {
        // Enable this if you need diagnostics in the console
        SystemTray.DEBUG = false

        val osName = System.getProperty("os.name").lowercase()
        // Work around some macOS issues by forcing Swing
        if (osName.contains("mac")) {
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.Swing
        }

        val t = SystemTray.get()
        if (t == null) {
            println("SystemTray.get() returned null – no tray available on this platform/configuration.")
        }
        t
    }

    DisposableEffect(tray) {
        if (tray == null) {
            return@DisposableEffect onDispose { }
        }

        val iconStream = Thread.currentThread()
            .contextClassLoader
            .getResourceAsStream("ic_notif.png")

        if (iconStream != null) {
            tray.setImage(iconStream)
        } else {
            println("Error: icon not found on classpath.")
        }

        tray.setStatus("Mages")

        tray.menu.add(MenuItem("Show").apply {
            setCallback {
                // Dorkbox callbacks are on their own thread – bounce to EDT/Compose thread
                SwingUtilities.invokeLater {
                    showWindow = true
                }
            }
        })

        tray.menu.add(dorkbox.systemTray.Separator())

        val minimizeItem = MenuItem(
            if (startInTray) "✓ Minimize to tray on launch"
            else "Minimize to tray on launch"
        )

        minimizeItem.setCallback {
            SwingUtilities.invokeLater {
                startInTray = !startInTray
                minimizeItem.text =
                    if (startInTray) "✓ Minimize to tray on launch"
                    else "Minimize to tray on launch"
            }

            scope.launch {
                saveBoolean(dataStore, PREF_START_IN_TRAY, startInTray)
            }
        }
        tray.menu.add(minimizeItem)

        tray.menu.add(dorkbox.systemTray.Separator())

        tray.menu.add(MenuItem("Quit").apply {
            setCallback {
                SwingUtilities.invokeLater {
                    tray.shutdown()
                    exitApplication()
                }
            }
        })

        onDispose {
            tray.shutdown()
        }
    }

    Window(
        onCloseRequest = {
            showWindow = false
        },
        state = windowState,
        visible = showWindow,
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
                Notifier.setWindowFocused(false)
            }
        }

        App(dataStore, getService())
    }
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