package org.mlm.mages.matrix

import mages.SasEmojis
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import mages.Client as FfiClient
import mages.RoomSummary as FfiRoom
import mages.MessageEvent as FfiEvent
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.platform.MagesPaths

class RustMatrixPort(hs: String) : MatrixPort {
    @Volatile private var client: FfiClient = FfiClient(hs, MagesPaths.storeDir())
    private val clientLock = Any()
    private var currentHs = hs

    override suspend fun init(hs: String) {
        synchronized(clientLock) {
            if (hs != currentHs) {
                client.let { c ->
                    runCatching { c.shutdown() }
                    runCatching { c.close() }
                }
                client = mages.Client(hs, MagesPaths.storeDir())
                currentHs = hs
            }
        }
    }

    override fun close() {
        synchronized(clientLock) {
            client.let { c ->
                runCatching { c.shutdown() }
                runCatching { c.close() }              // key: avoid Cleaner path
            }
        }
    }

    override suspend fun login(user: String, password: String, deviceDisplayName: String?) {
        client.login(user, password, deviceDisplayName)
    }
    override fun isLoggedIn(): Boolean = client.isLoggedIn()

    override suspend fun listRooms(): List<RoomSummary> = client.rooms().map { it.toModel() }
    override suspend fun recent(roomId: String, limit: Int): List<MessageEvent> =
        client.recentEvents(roomId, limit.toUInt()).map { it.toModel() }

    override fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>> = callbackFlow {
        val obs = object : mages.TimelineDiffObserver {
            override fun onInsert(event: mages.MessageEvent) { trySendBlocking(TimelineDiff.Insert(event.toModel())) }
            override fun onUpdate(event: mages.MessageEvent) { trySendBlocking(TimelineDiff.Update(event.toModel())) }
            override fun onRemove(itemId: String) { trySendBlocking(TimelineDiff.Remove(itemId)) } // was eventId
            override fun onClear() { trySendBlocking(TimelineDiff.Clear) }
            override fun onReset(events: List<mages.MessageEvent>) {
                trySendBlocking(TimelineDiff.Reset(events.map { it.toModel() }))
            }
        }
        val subId: ULong = client.observeRoomTimelineDiffs(roomId, obs)
        awaitClose { client.unobserveRoomTimeline(subId) }
    }

    override suspend fun initCaches(): Boolean = client.initCaches()

    override suspend fun cacheMessages(roomId: String, messages: List<MessageEvent>): Boolean {
        val ffiMessages = messages.map { msg ->
            mages.MessageEvent(
                itemId = msg.itemId,
                eventId = msg.eventId,
                roomId = msg.roomId,
                sender = msg.sender,
                body = msg.body,
                timestampMs = msg.timestamp.toULong()
            )
        }
        return client.cacheMessages(roomId, ffiMessages)
    }

    override suspend fun getCachedMessages(roomId: String, limit: Int): List<MessageEvent> =
        client.getCachedMessages(roomId, limit.toUInt()).map { it.toModel() }

    override suspend fun savePaginationState(state: MatrixPort.PaginationState): Boolean {
        val ffiState = mages.PaginationState(
            roomId = state.roomId,
            prevBatch = state.prevBatch,
            nextBatch = state.nextBatch,
            atStart = state.atStart,
            atEnd = state.atEnd
        )
        return client.savePaginationState(ffiState)
    }

    override suspend fun getPaginationState(roomId: String): MatrixPort.PaginationState? {
        return client.getPaginationState(roomId)?.let { ffi ->
            MatrixPort.PaginationState(
                roomId = ffi.roomId,
                prevBatch = ffi.prevBatch,
                nextBatch = ffi.nextBatch,
                atStart = ffi.atStart,
                atEnd = ffi.atEnd
            )
        }
    }

    override fun observeConnection(observer: MatrixPort.ConnectionObserver): ULong {
        val cb = object : mages.ConnectionObserver {
            override fun onConnectionChange(state: mages.ConnectionState) {
                val mapped = when (state) {
                    is mages.ConnectionState.Disconnected -> MatrixPort.ConnectionState.Disconnected
                    is mages.ConnectionState.Connecting -> MatrixPort.ConnectionState.Connecting
                    is mages.ConnectionState.Connected -> MatrixPort.ConnectionState.Connected
                    is mages.ConnectionState.Syncing -> MatrixPort.ConnectionState.Syncing
                    is mages.ConnectionState.Reconnecting -> MatrixPort.ConnectionState.Reconnecting
                }
                observer.onConnectionChange(mapped)
            }
        }
        return client.monitorConnection(cb)
    }
    override fun stopConnectionObserver(token: ULong) {
        client.unobserveConnection(token)
    }

    override fun startVerificationInbox(observer: MatrixPort.VerificationInboxObserver): ULong {
        val cb = object : mages.VerificationInboxObserver {
            override fun onRequest(flowId: String, fromUser: String, fromDevice: String) {
                observer.onRequest(flowId, fromUser, fromDevice)
            }
            override fun onError(message: String) { observer.onError(message) }
        }
        return client.startVerificationInbox(cb)
    }
    override fun stopVerificationInbox(token: ULong) {
        client.unobserveVerificationInbox(token)
    }


    override suspend fun send(roomId: String, body: String): Boolean = client.sendMessage(roomId, body)
    override suspend fun enqueueText(roomId: String, body: String, txnId: String?): String =
        client.enqueueText(roomId, body, txnId)
    override fun startSendWorker() = client.startSendWorker()

    override fun observeSends(): Flow<SendUpdate> = callbackFlow {
        val obs = object : mages.SendObserver {
            override fun onUpdate(update: mages.SendUpdate) {
                trySend(
                    SendUpdate(
                        roomId = update.roomId,
                        txnId = update.txnId,
                        attempts = update.attempts.toInt(),
                        state = when (update.state) {
                            mages.SendState.ENQUEUED -> SendState.Enqueued
                            mages.SendState.SENDING -> SendState.Sending
                            mages.SendState.SENT -> SendState.Sent
                            mages.SendState.RETRYING -> SendState.Retrying
                            mages.SendState.FAILED -> SendState.Failed
                        },
                        eventId = update.eventId,
                        error = update.error
                    )
                )
            }
        }
        val token = client.observeSends(obs)
        awaitClose { client.unobserveSends(token) }
        client.observeSends(obs)
        awaitClose { }
    }

    override suspend fun mediaCacheStats(): Pair<Long, Long> {
        val s = client.mediaCacheStats(); return s.bytes.toLong() to s.files.toLong()
    }
    override suspend fun mediaCacheEvict(maxBytes: Long): Long = client.mediaCacheEvict(maxBytes.toULong()).toLong()
    override suspend fun thumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): Result<String> =
        runCatching { client.thumbnailToCache(mxcUri, width.toUInt(), height.toUInt(), crop) }


    override suspend fun setTyping(roomId: String, typing: Boolean): Boolean {
        return client.setTyping(roomId, typing)
    }

    override fun whoami(): String? {
        return client.whoami()
    }

    override suspend fun paginateBack(roomId: String, count: Int) = client.paginateBackwards(roomId, count.toUShort())
    override suspend fun paginateForward(roomId: String, count: Int) = client.paginateForwards(roomId, count.toUShort())
    override suspend fun markRead(roomId: String) = client.markRead(roomId)
    override suspend fun markReadAt(roomId: String, eventId: String) = client.markReadAt(roomId, eventId)
    override suspend fun react(roomId: String, eventId: String, emoji: String) = client.react(roomId, eventId, emoji)
    override suspend fun reply(roomId: String, inReplyToEventId: String, body: String) =
        client.reply(roomId, inReplyToEventId, body)
    override suspend fun edit(roomId: String, targetEventId: String, newBody: String) =
        client.edit(roomId, targetEventId, newBody)
    override suspend fun redact(roomId: String, eventId: String, reason: String?) =
        client.redact(roomId, eventId, reason)

    override fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong {
        val obs = object : mages.TypingObserver {
            override fun onUpdate(names: List<String>) { onUpdate(names) }
        }
        return client.observeTyping(roomId, obs)
    }

    override fun stopTypingObserver(token: ULong) {
        client.unobserveTyping(token)
    }

    override fun startSupervisedSync(observer: MatrixPort.SyncObserver) {
        val cb = object : mages.SyncObserver {
            override fun onState(status: mages.SyncStatus) {
                val phase = when (status.phase) {
                    mages.SyncPhase.IDLE -> MatrixPort.SyncPhase.Idle
                    mages.SyncPhase.RUNNING -> MatrixPort.SyncPhase.Running
                    mages.SyncPhase.BACKING_OFF -> MatrixPort.SyncPhase.BackingOff
                    mages.SyncPhase.ERROR -> MatrixPort.SyncPhase.Error
                }
                observer.onState(MatrixPort.SyncStatus(phase, status.message))
            }
        }
        client.startSupervisedSync(cb)
    }

    override suspend fun listMyDevices(): List<DeviceSummary> =
        client.listMyDevices().map {
            DeviceSummary(
                deviceId = it.deviceId,
                displayName = it.displayName,
                ed25519 = it.ed25519,
                isOwn = it.isOwn,
                locallyTrusted = it.locallyTrusted
            )
        }

    override suspend fun setLocalTrust(deviceId: String, verified: Boolean): Boolean =
        client.setLocalTrust(deviceId, verified)

    private fun mages.SasPhase.toCommon(): SasPhase = when (this) {
        mages.SasPhase.REQUESTED -> SasPhase.Requested
        mages.SasPhase.READY -> SasPhase.Ready
        mages.SasPhase.EMOJIS -> SasPhase.Emojis
        mages.SasPhase.CONFIRMED -> SasPhase.Confirmed
        mages.SasPhase.CANCELLED -> SasPhase.Cancelled
        mages.SasPhase.FAILED -> SasPhase.Failed
        mages.SasPhase.DONE -> SasPhase.Done
    }

    override suspend fun startSelfSas(targetDeviceId: String, observer: VerificationObserver): String {
        val obs = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }
            override fun onEmojis(payload: mages.SasEmojis) {
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
            }
            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.startSelfSas(targetDeviceId, obs)
    }

    override suspend fun startUserSas(userId: String, observer: VerificationObserver): String {
        val obs = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }
            override fun onEmojis(payload: mages.SasEmojis) {
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
            }
            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.startUserSas(userId, obs)
    }

//    override suspend fun startVerification(
//        targetUser: String,
//        targetDevice: String,
//        observer: VerificationObserver
//    ): Boolean {
//        val cb = object : mages.VerificationObserver {
//            override fun onPhase(flowId: String, phase: mages.SasPhase) {
//                observer.onPhase(flowId, when (phase) {
//                    mages.SasPhase.REQUESTED -> SasPhase.Requested
//                    mages.SasPhase.READY -> SasPhase.Ready
//                    mages.SasPhase.EMOJIS -> SasPhase.Emojis
//                    mages.SasPhase.CONFIRMED -> SasPhase.Confirmed
//                    mages.SasPhase.CANCELLED -> SasPhase.Cancelled
//                    mages.SasPhase.FAILED -> SasPhase.Failed
//                    mages.SasPhase.DONE -> SasPhase.Done
//                })
//            }
//            override fun onEmojis(payload: SasEmojis) {
//                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
//            }
//            override fun onError(flowId: String, message: String) {
//                observer.onError(flowId, message)
//            }
//        }
//        return client.startVerification(targetUser, targetDevice, cb)
//    }

    override suspend fun acceptVerification(
        flowId: String,
        otherUserId: String?,
        observer: VerificationObserver
    ): Boolean {
        val cb = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(flowId, when (phase) {
                    mages.SasPhase.REQUESTED -> SasPhase.Requested
                    mages.SasPhase.READY -> SasPhase.Ready
                    mages.SasPhase.EMOJIS -> SasPhase.Emojis
                    mages.SasPhase.CONFIRMED -> SasPhase.Confirmed
                    mages.SasPhase.CANCELLED -> SasPhase.Cancelled
                    mages.SasPhase.FAILED -> SasPhase.Failed
                    mages.SasPhase.DONE -> SasPhase.Done
                })
            }
            override fun onEmojis(payload: SasEmojis) {
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
            }
            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.acceptVerification(flowId, cb)
    }

    override suspend fun confirmVerification(flowId: String): Boolean =
        client.confirmVerification(flowId)

    override suspend fun cancelVerification(flowId: String): Boolean =
        client.cancelVerification(flowId)

    override suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?): Boolean =
        client.cancelVerificationRequest(flowId)

    override fun enterForeground() {
        client.enterForeground()
    }
    override fun enterBackground() {
        client.enterBackground()
    }

    override suspend fun logout(): Boolean = client.logout()
    override suspend fun cancelTxn(txnId: String): Boolean =
        client.cancelTxn(txnId)

    override suspend fun retryTxnNow(txnId: String): Boolean =
        client.retryTxnNow(txnId)

    override suspend fun pendingSends(): UInt =
        client.pendingSends()

    override suspend fun checkVerificationRequest(userId: String, flowId: String): Boolean =
        client.checkVerificationRequest(userId, flowId)

    override suspend fun sendAttachmentFromPath(roomId: String, path: String, mime: String, filename: String?, onProgress: ((Long, Long?) -> Unit)?): Boolean {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) { onProgress(sent.toLong(), total?.toLong()) }
        } else null
        return client.sendAttachmentFromPath(roomId, path, mime, filename, cb)
    }
    override suspend fun sendAttachmentBytes(roomId: String, data: ByteArray, mime: String, filename: String, onProgress: ((Long, Long?) -> Unit)?): Boolean {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) { onProgress(sent.toLong(), total?.toLong()) }
        } else null
        return client.sendAttachmentBytes(roomId, filename, mime, data, cb)
    }
    override suspend fun downloadToPath(mxcUri: String, savePath: String, onProgress: ((Long, Long?) -> Unit)?): Result<String> {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) { onProgress(sent.toLong(), total?.toLong()) }
        } else null
        return runCatching { client.downloadToPath(mxcUri, savePath, cb).path }
    }
    override suspend fun recoverWithKey(recoveryKey: String): Boolean = client.recoverWithKey(recoveryKey)
}

private fun FfiRoom.toModel() = RoomSummary(id = id, name = name)
private fun FfiEvent.toModel() = MessageEvent(
    itemId = itemId, eventId = eventId, roomId = roomId, sender = sender, body = body, timestamp = timestampMs.toLong()
)

actual fun createMatrixPort(hs: String): MatrixPort = RustMatrixPort(hs)