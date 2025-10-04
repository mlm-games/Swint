package org.mlm.frair.matrix

import kotlinx.coroutines.flow.Flow
import org.mlm.frair.MessageEvent
import org.mlm.frair.RoomSummary

data class DeviceSummary(
    val deviceId: String,
    val displayName: String,
    val ed25519: String,
    val isOwn: Boolean,
    val locallyTrusted: Boolean
)

sealed class TimelineDiff<out T> {
    data class Insert<T>(val item: T) : TimelineDiff<T>()
    data class Update<T>(val item: T) : TimelineDiff<T>()
    data class Remove(val itemId: String) : TimelineDiff<Nothing>()
    data object Clear : TimelineDiff<Nothing>()
    data class Reset<T>(val items: List<T>) : TimelineDiff<T>()
}

enum class SasPhase { Requested, Ready, Emojis, Confirmed, Cancelled, Failed, Done }

enum class SendState { Enqueued, Sending, Sent, Retrying, Failed }

data class SendUpdate(
    val roomId: String,
    val txnId: String,
    val attempts: Int,
    val state: SendState,
    val eventId: String?,
    val error: String?
)

interface VerificationObserver {
    fun onPhase(flowId: String, phase: SasPhase)
    fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>)
    fun onError(flowId: String, message: String)
}

interface MatrixPort {

    data class SyncStatus(val phase: SyncPhase, val message: String?)
    enum class SyncPhase { Idle, Running, BackingOff, Error }
    enum class ConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Syncing,
        Reconnecting
    }

    data class PaginationState(
        val roomId: String,
        val prevBatch: String?,
        val nextBatch: String?,
        val atStart: Boolean,
        val atEnd: Boolean
    )
    interface SyncObserver { fun onState(status: SyncStatus) }

    suspend fun init(hs: String)
    suspend fun login(user: String, pass: String)
    suspend fun listRooms(): List<RoomSummary>
    suspend fun recent(roomId: String, limit: Int = 50): List<MessageEvent>
    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>>
    suspend fun send(roomId: String, body: String): Boolean
    fun isLoggedIn(): Boolean
    fun close()

    suspend fun cancelVerificationRequest(flowId: String): Boolean

    suspend fun setTyping(roomId: String, typing: Boolean): Boolean
    fun whoami(): String?  // "@user:server" or null if not logged in

    suspend fun enqueueText(roomId: String, body: String, txnId: String? = null): String
    fun startSendWorker()
    fun observeSends(): Flow<SendUpdate>

    suspend fun mediaCacheStats(): Pair<Long, Long>
    suspend fun mediaCacheEvict(maxBytes: Long): Long
    suspend fun thumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): Result<String>

    interface VerificationInboxObserver {
        fun onRequest(flowId: String, fromUser: String, fromDevice: String)
        fun onError(message: String)
    }
    suspend fun initCaches(): Boolean
    suspend fun cacheMessages(roomId: String, messages: List<MessageEvent>): Boolean
    suspend fun getCachedMessages(roomId: String, limit: Int): List<MessageEvent>
    suspend fun savePaginationState(state: PaginationState): Boolean
    suspend fun getPaginationState(roomId: String): PaginationState?

    fun observeConnection(observer: ConnectionObserver): ULong
    fun stopConnectionObserver(token: ULong)

    fun startVerificationInbox(observer: VerificationInboxObserver): ULong
    fun stopVerificationInbox(token: ULong)
    interface ConnectionObserver {
        fun onConnectionChange(state: ConnectionState)
    }

    fun stopTypingObserver(token: ULong)

    suspend fun paginateBack(roomId: String, count: Int): Boolean
    suspend fun paginateForward(roomId: String, count: Int): Boolean
    suspend fun markRead(roomId: String): Boolean
    suspend fun markReadAt(roomId: String, eventId: String): Boolean
    suspend fun react(roomId: String, eventId: String, emoji: String): Boolean
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String): Boolean
    suspend fun edit(roomId: String, targetEventId: String, newBody: String): Boolean
    suspend fun redact(roomId: String, eventId: String, reason: String? = null): Boolean
    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong

    fun startSupervisedSync(observer: SyncObserver)

    suspend fun listMyDevices(): List<DeviceSummary>
    suspend fun setLocalTrust(deviceId: String, verified: Boolean): Boolean

    suspend fun startSelfSas(targetDeviceId: String, observer: VerificationObserver): String
    suspend fun startUserSas(userId: String, observer: VerificationObserver): String

    suspend fun acceptVerification(flowId: String, observer: VerificationObserver): Boolean
    suspend fun confirmVerification(flowId: String): Boolean
    suspend fun cancelVerification(flowId: String): Boolean

    suspend fun logout(): Boolean

    suspend fun cancelTxn(txnId: String): Boolean
    suspend fun retryTxnNow(txnId: String): Boolean
    suspend fun pendingSends(): UInt

    suspend fun checkVerificationRequest(userId: String, flowId: String): Boolean

    suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean

    suspend fun sendAttachmentBytes(
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean

    suspend fun downloadToPath(
        mxcUri: String,
        savePath: String,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Result<String>

    suspend fun recoverWithKey(recoveryKey: String): Boolean
}

expect fun createMatrixPort(hs: String): MatrixPort