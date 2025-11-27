package org.mlm.mages

import kotlinx.coroutines.flow.Flow
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.SendUpdate
import org.mlm.mages.matrix.SpaceHierarchyPage
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.matrix.VerificationObserver
import kotlin.time.ExperimentalTime

class MatrixService(val port: MatrixPort) {
    suspend fun init(hs: String) = port.init(hs.trim())
    suspend fun login(user: String, password: String, deviceDisplayName: String?) =
         port.login(user.trim(), password, deviceDisplayName)

    fun isLoggedIn(): Boolean = port.isLoggedIn()

    fun observeSends(): Flow<SendUpdate> = port.observeSends()
    suspend fun thumbnailToCache(mxc: String, w: Int, h: Int, crop: Boolean) = port.thumbnailToCache(mxc, w, h, crop)

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

        suspend fun retryByTxn(roomId: String, txnId: String) =
        runCatching { port.retryByTxn(roomId, txnId) }.getOrElse { false }

    suspend fun downloadToCacheFile(mxc: String, filenameHint: String? = null): Result<String> =
                port.downloadToCacheFile(mxc, filenameHint)

    suspend fun isSpace(roomId: String): Boolean =
        runCatching { port.isSpace(roomId) }.getOrDefault(false)

    suspend fun mySpaces(): List<SpaceInfo> =
        runCatching { port.mySpaces() }.getOrDefault(emptyList())

    suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): String? = runCatching { port.createSpace(name, topic, isPublic, invitees) }.getOrNull()

    suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String? = null,
        suggested: Boolean? = null
    ): Boolean = runCatching { port.spaceAddChild(spaceId, childRoomId, order, suggested) }.getOrDefault(false)

    suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Boolean =
        runCatching { port.spaceRemoveChild(spaceId, childRoomId) }.getOrDefault(false)

    suspend fun spaceHierarchy(
        spaceId: String,
        from: String? = null,
        limit: Int = 50,
        maxDepth: Int? = null,
        suggestedOnly: Boolean = false
    ): SpaceHierarchyPage? = runCatching {
        port.spaceHierarchy(spaceId, from, limit, maxDepth, suggestedOnly)
    }.getOrNull()

    suspend fun spaceInviteUser(spaceId: String, userId: String): Boolean =
        runCatching { port.spaceInviteUser(spaceId, userId) }.getOrDefault(false)

}