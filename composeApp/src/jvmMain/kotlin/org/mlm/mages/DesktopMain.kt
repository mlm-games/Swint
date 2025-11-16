package org.mlm.mages

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.storage.loadString
import org.mlm.mages.storage.provideAppDataStore
import java.io.IOException

fun main() = application {
    val dataStore = provideAppDataStore()
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

    Window(onCloseRequest = ::exitApplication, title = "Mages") {
        App(dataStore, get())
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