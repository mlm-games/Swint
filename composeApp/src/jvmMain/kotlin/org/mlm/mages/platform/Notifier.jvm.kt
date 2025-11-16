package org.mlm.mages.platform

import org.mlm.mages.NotifierImpl

actual object Notifier {
    actual fun notifyRoom(title: String, body: String) {
        NotifierImpl.notify(app = "Mages", title = title, body = body, desktopEntry = "org.mlm.mages")
    }
}
