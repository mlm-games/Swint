package org.mlm.frair

import kotlinx.coroutines.flow.Flow
import org.mlm.frair.matrix.MatrixPort

class MatrixService(val port: MatrixPort) {
    suspend fun init(hs: String) = run { port.init(hs); true }
    suspend fun login(user: String, password: String): Boolean {
        return runCatching {
            port.login(user, password)
            port.startSync()
        }.isSuccess
    }
    suspend fun listRooms() = port.listRooms()
    suspend fun loadRecent(roomId: String, limit: Int = 50) = port.recent(roomId, limit)
    fun timeline(roomId: String): Flow<MessageEvent> = port.timeline(roomId)
    suspend fun sendMessage(roomId: String, body: String) =
        runCatching { port.send(roomId, body) }.isSuccess

    fun startSync() = port.startSync()

    suspend fun paginateBack(roomId: String, count: Int) = runCatching { port.paginateBack(roomId, count) }.getOrElse { false }
    suspend fun paginateForward(roomId: String, count: Int) = runCatching { port.paginateForward(roomId, count) }.getOrElse { false }
    suspend fun markRead(roomId: String) = runCatching { port.markRead(roomId) }.getOrElse { false }
    suspend fun markReadAt(roomId: String, eventId: String) = runCatching { port.markReadAt(roomId, eventId) }.getOrElse { false }
    suspend fun react(roomId: String, eventId: String, emoji: String) = runCatching { port.react(roomId, eventId, emoji) }.getOrElse { false }
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String) = runCatching { port.reply(roomId, inReplyToEventId, body) }.getOrElse { false }
    suspend fun edit(roomId: String, targetEventId: String, newBody: String) = runCatching { port.edit(roomId, targetEventId, newBody) }.getOrElse { false }

    suspend fun redact(roomId: String, eventId: String, reason: String? = null) =
        runCatching { port.redact(roomId, eventId, reason) }.getOrElse { false }
    suspend fun startTyping(roomId: String, timeoutMs: Long = 30000) =
        runCatching { port.startTyping(roomId, timeoutMs) }.getOrElse { false }
    suspend fun stopTyping(roomId: String) =
        runCatching { port.stopTyping(roomId) }.getOrElse { false }

    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit) =
        port.observeTyping(roomId, onUpdate)
}