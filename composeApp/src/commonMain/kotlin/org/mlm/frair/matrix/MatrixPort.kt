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

enum class SasPhase { Requested, Ready, Emojis, Confirmed, Cancelled, Failed, Done }

interface VerificationObserver {
    fun onPhase(flowId: String, phase: SasPhase)
    fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>)
    fun onError(flowId: String, message: String)
}

interface MatrixPort {

    data class SyncStatus(val phase: SyncPhase, val message: String?)
    enum class SyncPhase { Idle, Running, BackingOff, Error }
    interface SyncObserver { fun onState(status: SyncStatus) }

    suspend fun init(hs: String)
    suspend fun login(user: String, pass: String)
    suspend fun listRooms(): List<RoomSummary>
    suspend fun recent(roomId: String, limit: Int = 50): List<MessageEvent>
    fun timeline(roomId: String): Flow<MessageEvent>
    suspend fun send(roomId: String, body: String)
    fun startSync()
    fun isLoggedIn(): Boolean
    fun close()

    suspend fun paginateBack(roomId: String, count: Int): Boolean
    suspend fun paginateForward(roomId: String, count: Int): Boolean
    suspend fun markRead(roomId: String): Boolean
    suspend fun markReadAt(roomId: String, eventId: String): Boolean
    suspend fun react(roomId: String, eventId: String, emoji: String): Boolean
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String): Boolean
    suspend fun edit(roomId: String, targetEventId: String, newBody: String): Boolean

    suspend fun redact(roomId: String, eventId: String, reason: String? = null): Boolean
    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit)

    fun startSupervisedSync(observer: SyncObserver)


    suspend fun listMyDevices(): List<DeviceSummary>
    suspend fun setLocalTrust(deviceId: String, verified: Boolean): Boolean

    suspend fun startSelfSas(targetDeviceId: String, observer: VerificationObserver): String
    suspend fun acceptVerification(flowId: String): Boolean
    suspend fun confirmVerification(flowId: String): Boolean
    suspend fun cancelVerification(flowId: String): Boolean
    suspend fun logout(): Boolean

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

    // Recovery
    suspend fun recoverWithKey(recoveryKey: String): Boolean
}

expect fun createMatrixPort(hs: String): MatrixPort