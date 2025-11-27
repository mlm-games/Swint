package org.mlm.mages.platform

expect object Notifier {
    fun notifyRoom(title: String, body: String)
    fun setCurrentRoom(roomId: String?)
    fun setWindowFocused(focused: Boolean)
    fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean
}