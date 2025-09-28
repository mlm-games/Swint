package org.mlm.frair

import kotlinx.coroutines.flow.Flow
import org.mlm.frair.matrix.DeviceSummary
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.matrix.VerificationObserver
import kotlin.time.ExperimentalTime

class MatrixService(val port: MatrixPort) {
    suspend fun init(hs: String) = port.init(hs.trim())
    suspend fun login(user: String, password: String): Result<Unit> =
        runCatching { port.login(user.trim(), password) }

    fun isLoggedIn(): Boolean {
        return port.isLoggedIn()
    }

    fun startSync() = port.startSync()
    suspend fun listRooms(): List<RoomSummary> = port.listRooms()
    suspend fun loadRecent(roomId: String, limit: Int = 50): List<MessageEvent> = port.recent(roomId, limit)
    fun timeline(roomId: String): Flow<MessageEvent> = port.timeline(roomId)
    suspend fun sendMessage(roomId: String, body: String) = runCatching { port.send(roomId, body) }.isSuccess

    suspend fun paginateBack(roomId: String, count: Int) = runCatching { port.paginateBack(roomId, count) }.getOrElse { false }
    suspend fun paginateForward(roomId: String, count: Int) = runCatching { port.paginateForward(roomId, count) }.getOrElse { false }

    suspend fun markRead(roomId: String) = runCatching { port.markRead(roomId) }.getOrElse { false }
    suspend fun markReadAt(roomId: String, eventId: String) = runCatching { port.markReadAt(roomId, eventId) }.getOrElse { false }

    suspend fun react(roomId: String, eventId: String, emoji: String) = runCatching { port.react(roomId, eventId, emoji) }.getOrElse { false }
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String) = runCatching { port.reply(roomId, inReplyToEventId, body) }.getOrElse { false }
    suspend fun edit(roomId: String, targetEventId: String, newBody: String) = runCatching { port.edit(roomId, targetEventId, newBody) }.getOrElse { false }

    suspend fun redact(roomId: String, eventId: String, reason: String? = null) =
        runCatching { port.redact(roomId, eventId, reason) }.getOrElse { false }

    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit) =
        port.observeTyping(roomId, onUpdate)

    suspend fun listMyDevices(): List<DeviceSummary> = runCatching { port.listMyDevices() }.getOrElse { emptyList() }
    suspend fun setLocalTrust(deviceId: String, verified: Boolean) = runCatching { port.setLocalTrust(deviceId, verified) }.getOrElse { false }

    suspend fun startSelfSas(deviceId: String, observer: VerificationObserver) = port.startSelfSas(deviceId, observer)
    suspend fun acceptVerification(flowId: String) = port.acceptVerification(flowId)
    suspend fun confirmVerification(flowId: String) = port.confirmVerification(flowId)
    suspend fun cancelVerification(flowId: String) = port.cancelVerification(flowId)

    suspend fun logout(): Boolean = port.logout()

    @OptIn(ExperimentalTime::class)
    fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}