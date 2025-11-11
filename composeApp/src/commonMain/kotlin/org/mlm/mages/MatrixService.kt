package org.mlm.mages

import kotlinx.coroutines.flow.Flow
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.SendUpdate
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.matrix.VerificationObserver
import kotlin.time.ExperimentalTime

class MatrixService(val port: MatrixPort) {
    suspend fun init(hs: String) = port.init(hs.trim())
    suspend fun login(user: String, password: String, deviceDisplayName: String?) =
        runCatching { port.login(user.trim(), password, deviceDisplayName) }

    fun isLoggedIn(): Boolean = port.isLoggedIn()

    fun observeSends(): Flow<SendUpdate> = port.observeSends()

    suspend fun mediaCacheStats() = port.mediaCacheStats()
    suspend fun mediaCacheEvict(maxBytes: Long) = port.mediaCacheEvict(maxBytes)
    suspend fun thumbnailToCache(mxc: String, w: Int, h: Int, crop: Boolean) = port.thumbnailToCache(mxc, w, h, crop)

    suspend fun initCaches(): Boolean =
        runCatching { port.initCaches() }.getOrElse { false }

    suspend fun cacheMessages(roomId: String, messages: List<MessageEvent>): Boolean =
        runCatching { port.cacheMessages(roomId, messages) }.getOrElse { false }

    suspend fun getCachedMessages(roomId: String, limit: Int = 50): List<MessageEvent> =
        runCatching { port.getCachedMessages(roomId, limit) }.getOrElse { emptyList() }

    suspend fun savePaginationState(state: MatrixPort.PaginationState): Boolean =
        runCatching { port.savePaginationState(state) }.getOrElse { false }

    suspend fun getPaginationState(roomId: String): MatrixPort.PaginationState? =
        runCatching { port.getPaginationState(roomId) }.getOrNull()

    fun startSupervisedSync(obs: MatrixPort.SyncObserver) = runCatching { port.startSupervisedSync(obs) }


    // Connection monitoring
    fun observeConnection(observer: MatrixPort.ConnectionObserver): ULong =
        port.observeConnection(observer)
    fun stopConnectionObserver(token: ULong) = port.stopConnectionObserver(token)

    fun startVerificationInbox(cb: MatrixPort.VerificationInboxObserver): ULong =
        port.startVerificationInbox(cb)
    fun stopVerificationInbox(token: ULong) = port.stopVerificationInbox(token)

    suspend fun listRooms(): List<RoomSummary> = port.listRooms()
    suspend fun loadRecent(roomId: String, limit: Int = 50): List<MessageEvent> = port.recent(roomId, limit)
    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>> = port.timelineDiffs(roomId)

    suspend fun sendMessage(roomId: String, body: String): Boolean =
        port.send(roomId, body)
    suspend fun paginateBack(roomId: String, count: Int) = runCatching { port.paginateBack(roomId, count) }.getOrElse { false }
    suspend fun paginateForward(roomId: String, count: Int) = runCatching { port.paginateForward(roomId, count) }.getOrElse { false }

    suspend fun markRead(roomId: String) = runCatching { port.markRead(roomId) }.getOrElse { false }
    suspend fun markReadAt(roomId: String, eventId: String) = runCatching { port.markReadAt(roomId, eventId) }.getOrElse { false }

    suspend fun react(roomId: String, eventId: String, emoji: String) = runCatching { port.react(roomId, eventId, emoji) }.getOrElse { false }
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String) = runCatching { port.reply(roomId, inReplyToEventId, body) }.getOrElse { false }
    suspend fun edit(roomId: String, targetEventId: String, newBody: String) = runCatching { port.edit(roomId, targetEventId, newBody) }.getOrElse { false }

    suspend fun redact(roomId: String, eventId: String, reason: String? = null) =
        runCatching { port.redact(roomId, eventId, reason) }.getOrElse { false }

    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong =
        port.observeTyping(roomId, onUpdate)

    fun stopTypingObserver(token: ULong) = port.stopTypingObserver(token)

    suspend fun enqueueText(roomId: String, body: String, txnId: String? = null) = port.enqueueText(roomId, body, txnId)
    fun startSendWorker() = port.startSendWorker()

    suspend fun listMyDevices(): List<DeviceSummary> = runCatching { port.listMyDevices() }.getOrElse { emptyList() }
    suspend fun setLocalTrust(deviceId: String, verified: Boolean) = runCatching { port.setLocalTrust(deviceId, verified) }.getOrElse { false }

    suspend fun startSelfSas(deviceId: String, observer: VerificationObserver) = port.startSelfSas(deviceId, observer)

//    suspend fun startVerification(targetUser: String, targetDevice: String, observer: VerificationObserver) =
//        port.startVerification(targetUser, targetDevice, observer)

    suspend fun acceptVerification(flowId: String, otherUserId: String?, observer: VerificationObserver) =
        port.acceptVerification(flowId, otherUserId, observer)
    suspend fun confirmVerification(flowId: String) = port.confirmVerification(flowId)
    suspend fun cancelVerification(flowId: String) = port.cancelVerification(flowId)
    suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?) =
        port.cancelVerificationRequest(flowId, otherUserId)

    suspend fun logout(): Boolean = port.logout()

    suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) = runCatching { port.sendAttachmentFromPath(roomId, path, mime, filename, onProgress) }.getOrElse { false }

    suspend fun sendAttachmentBytes(
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) = runCatching { port.sendAttachmentBytes(roomId, data, mime, filename, onProgress) }.getOrElse { false }

    suspend fun downloadToPath(
        mxc: String,
        savePath: String,
        onProgress: ((Long, Long?) -> Unit)? = null
    ): Result<String> = port.downloadToPath(mxc, savePath, onProgress)

    // Recovery
    suspend fun recoverWithKey(recoveryKey: String) =
        runCatching { port.recoverWithKey(recoveryKey) }.getOrElse { false }

    @OptIn(ExperimentalTime::class)
    fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    suspend fun startUserSas(userId: String, observer: VerificationObserver) =
        port.startUserSas(userId, observer)

}