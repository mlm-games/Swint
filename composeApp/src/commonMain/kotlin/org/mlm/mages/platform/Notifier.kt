package org.mlm.mages.platform

expect object Notifier {
    fun notifyRoom(title: String, body: String)
}

