package org.mlm.mages.matrix

import kotlinx.coroutines.flow.Flow
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary

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

interface ReceiptsObserver { fun onChanged() }

data class CallInvite(
    val roomId: String,
    val sender: String,
    val callId: String,
    val isVideo: Boolean,
    val tsMs: Long
)

data class RenderedNotification( val roomId: String, val eventId: String, val roomName: String, val sender: String, val body: String, val isNoisy: Boolean, val hasMention: Boolean )

data class UnreadStats(val messages: Long, val notifications: Long, val mentions: Long)
data class DirectoryUser(val userId: String, val displayName: String?, val avatarUrl: String?)
data class PublicRoom(val roomId: String, val name: String?, val topic: String?, val alias: String?, val avatarUrl: String?, val memberCount: Long, val worldReadable: Boolean, val guestCanJoin: Boolean)
data class PublicRoomsPage(val rooms: List<PublicRoom>, val nextBatch: String?, val prevBatch: String?)
data class RoomProfile(
    val roomId: String,
    val name: String,
    val topic: String?,
    val memberCount: Long,
    val isEncrypted: Boolean,
    val isDm: Boolean
)

data class MemberSummary(
    val userId: String,
    val displayName: String?,
    val isMe: Boolean,
    val membership: String
)

data class ReactionChip(val key: String, val count: Int, val mine: Boolean)
data class ThreadPage(
    val rootEventId: String,
    val roomId: String,
    val messages: List<MessageEvent>,
    val nextBatch: String?,
    val prevBatch: String?
)
data class ThreadSummary(val rootEventId: String, val roomId: String, val count: Long, val latestTsMs: Long?)

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

    interface SyncObserver { fun onState(status: SyncStatus) }

    suspend fun init(hs: String)
    suspend fun login(user: String, password: String, deviceDisplayName: String?)
    suspend fun listRooms(): List<RoomSummary>
    suspend fun recent(roomId: String, limit: Int = 50): List<MessageEvent>
    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>>
    suspend fun send(roomId: String, body: String): Boolean
    fun isLoggedIn(): Boolean
    fun close()

    suspend fun setTyping(roomId: String, typing: Boolean): Boolean
    fun whoami(): String?  // "@user:server" or null if not logged in

    suspend fun enqueueText(roomId: String, body: String, txnId: String? = null): String
    fun startSendWorker()
    fun observeSends(): Flow<SendUpdate>

//    suspend fun setMediaRetentionPolicy(
//        maxCacheSizeBytes: Long? = null,
//        maxFileSizeBytes: Long? = null,
//        lastAccessExpirySecs: Long? = null,
//        cleanupFrequencySecs: Long? = null,
//    ): Boolean
//    suspend fun mediaCacheClean(): Boolean

    suspend fun thumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): Result<String>

    interface VerificationInboxObserver {
        fun onRequest(flowId: String, fromUser: String, fromDevice: String)
        fun onError(message: String)
    }
    fun observeConnection(observer: ConnectionObserver): ULong
    fun stopConnectionObserver(token: ULong)

    fun startVerificationInbox(observer: VerificationInboxObserver): ULong
    fun stopVerificationInbox(token: ULong)
    interface ConnectionObserver {
        fun onConnectionChange(state: ConnectionState)
    }

    suspend fun retryByTxn(roomId: String, txnId: String): Boolean

    suspend fun downloadToCacheFile(mxcUri: String, filenameHint: String? = null): Result<String>

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

//    suspend fun startVerification(targetUser: String, targetDevice: String, observer: VerificationObserver): Boolean
    suspend fun acceptVerification(flowId: String, otherUserId: String?, observer: VerificationObserver): Boolean
    suspend fun confirmVerification(flowId: String): Boolean
    suspend fun cancelVerification(flowId: String): Boolean

    suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?): Boolean

    fun enterForeground()
    fun enterBackground()

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
    fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong
    fun stopReceiptsObserver(token: ULong)
    suspend fun dmPeerUserId(roomId: String): String?
    suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean

    interface CallObserver { fun onInvite(invite: CallInvite) }
    fun startCallInbox(observer: CallObserver): ULong
    fun stopCallInbox(token: ULong)
    suspend fun registerUnifiedPush(appId: String, pushKey: String, gatewayUrl: String, deviceName: String, lang: String, profileTag: String? = null): Boolean
    suspend fun unregisterUnifiedPush(appId: String, pushKey: String): Boolean
    suspend fun wakeSyncOnce(timeoutMs: Int = 2500): Boolean

    suspend fun roomUnreadStats(roomId: String): UnreadStats?
    suspend fun ownLastRead(roomId: String): Pair<String?, Long?>
    fun observeOwnReceipt(roomId: String, observer: ReceiptsObserver): ULong
    suspend fun markFullyReadAt(roomId: String, eventId: String): Boolean

    suspend fun renderNotification(roomId: String, eventId: String): RenderedNotification?

    suspend fun encryptionCatchupOnce(): Boolean

    interface RoomListObserver { fun onReset(items: List<RoomListEntry>); fun onUpdate(item: RoomListEntry) }
    data class RoomListEntry(val roomId: String, val name: String, val unread: Long, val lastTs: Long)
    fun observeRoomList(observer: RoomListObserver): ULong
    fun unobserveRoomList(token: ULong)

    suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification?

    fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean

    suspend fun loginSsoLoopback(openUrl: (String) -> Boolean, deviceName: String? = null): Boolean

    suspend fun searchUsers(term: String, limit: Int = 20): List<DirectoryUser>
    suspend fun publicRooms(server: String? = null, search: String? = null, limit: Int = 50, since: String? = null): PublicRoomsPage
    suspend fun joinByIdOrAlias(idOrAlias: String): Boolean
    suspend fun ensureDm(userId: String): String?
    suspend fun resolveRoomId(idOrAlias: String): String?

    suspend fun listInvited(): List<RoomProfile>
    suspend fun acceptInvite(roomId: String): Boolean
    suspend fun leaveRoom(roomId: String): Boolean

    suspend fun createRoom(name: String?, topic: String?, invitees: List<String>): String?
    suspend fun setRoomName(roomId: String, name: String): Boolean
    suspend fun setRoomTopic(roomId: String, topic: String): Boolean

    suspend fun roomProfile(roomId: String): RoomProfile?
    suspend fun listMembers(roomId: String): List<MemberSummary>

    suspend fun reactions(roomId: String, eventId: String): List<ReactionChip>

    suspend fun sendThreadText(roomId: String, rootEventId: String, body: String, replyToEventId: String? = null): Boolean
    suspend fun threadSummary(roomId: String, rootEventId: String, perPage: Int = 100, maxPages: Int = 10): ThreadSummary

    suspend fun threadReplies(
        roomId: String,
        rootEventId: String,
        from: String? = null,
        limit: Int = 50,
        forward: Boolean = false
    ): ThreadPage
}

expect fun createMatrixPort(hs: String): MatrixPort