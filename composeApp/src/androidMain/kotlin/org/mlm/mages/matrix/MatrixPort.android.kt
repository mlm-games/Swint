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
    }

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

    override fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong {
        val cb = object : mages.ReceiptsObserver {
            override fun onChanged() { observer.onChanged() }
        }
        return client.observeReceipts(roomId, cb)
    }
    override fun stopReceiptsObserver(token: ULong) {
        client.unobserveReceipts(token)
    }
    override suspend fun dmPeerUserId(roomId: String): String? = client.dmPeerUserId(roomId)
    override suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean =
        client.isEventReadBy(roomId, eventId, userId)

    override fun startCallInbox(observer: MatrixPort.CallObserver): ULong {
        val cb = object : mages.CallObserver {
            override fun onInvite(invite: mages.CallInvite) {
                observer.onInvite(
                    CallInvite(
                        roomId = invite.roomId,
                        sender = invite.sender,
                        callId = invite.callId,
                        isVideo = invite.isVideo,
                        tsMs = invite.tsMs.toLong()
                    )
                )
            }
        }
        return client.startCallInbox(cb)
    }
    override fun stopCallInbox(token: ULong) {
        client.stopCallInbox(token)
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

    override suspend fun retryByTxn(roomId: String, txnId: String): Boolean {
        return client.retryByTxn(roomId, txnId)
    }

    override suspend fun downloadToCacheFile(
        mxcUri: String,
        filenameHint: String?
    ): Result<String> {
        return runCatching { client.downloadToCacheFile(mxcUri, filenameHint).path }
    }

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

    override suspend fun registerUnifiedPush(
        appId: String,
        pushKey: String,
        gatewayUrl: String,
        deviceName: String,
        lang: String,
        profileTag: String?,
    ): Boolean = client.registerUnifiedpush(appId, pushKey, gatewayUrl, deviceName, lang, profileTag)

    override suspend fun unregisterUnifiedPush(
        appId: String,
        pushKey: String,
    ): Boolean = client.unregisterUnifiedpush(appId, pushKey)

    override suspend fun wakeSyncOnce(timeoutMs: Int): Boolean = client.wakeSyncOnce(timeoutMs.toUInt())

    // Unread parity
    override suspend fun roomUnreadStats(roomId: String): UnreadStats? =
        client.roomUnreadStats(roomId)?.let { UnreadStats(it.messages.toLong(), it.notifications.toLong(), it.mentions.toLong()) }

    override suspend fun ownLastRead(roomId: String): Pair<String?, Long?> =
        client.ownLastRead(roomId).let { it.eventId to it.tsMs?.toLong() }

    override fun observeOwnReceipt(
        roomId: String,
        observer: ReceiptsObserver,
    ): ULong {
        val cb =
            object : mages.ReceiptsObserver {
                override fun onChanged() {
                    observer.onChanged()
                }
            }
        return client.observeOwnReceipt(roomId, cb) // mapped in Rust to receipt stream
    }

    override suspend fun markFullyReadAt(
        roomId: String,
        eventId: String,
    ): Boolean = client.markFullyReadAt(roomId, eventId)

    override suspend fun renderNotification(
        roomId: String,
        eventId: String,
    ): RenderedNotification? =
        client.renderNotification(roomId, eventId)?.let {
            RenderedNotification(
                roomId = it.roomId,
                eventId = it.eventId,
                roomName = it.roomName,
                sender = it.sender,
                body = it.body,
                isNoisy = it.isNoisy,
                hasMention = it.hasMention,
            )
        }

    override suspend fun encryptionCatchupOnce(): Boolean = client.encryptionCatchupOnce()

    override fun observeRoomList(observer: MatrixPort.RoomListObserver): ULong {
        val cb = object : mages.RoomListObserver {
            override fun onReset(items: List<mages.RoomListEntry>) {
                val mapped = items.map {
                    MatrixPort.RoomListEntry(
                        roomId = it.roomId,
                        name = it.name,
                        unread = it.unread.toLong(),
                        lastTs = it.lastTs.toLong()
                    )
                }
                observer.onReset(mapped)
            }
            override fun onUpdate(item: mages.RoomListEntry) {
                observer.onUpdate(
                    MatrixPort.RoomListEntry(
                        roomId = item.roomId,
                        name = item.name,
                        unread = item.unread.toLong(),
                        lastTs = item.lastTs.toLong()
                    )
                )
            }
        }
        return client.observeRoomList(cb)
    }

    override fun unobserveRoomList(token: ULong) {
        client.unobserveRoomList(token)
    }

    override suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification? {
        return client.fetchNotification(roomId, eventId)?.let {
            RenderedNotification(
                roomId = it.roomId,
                eventId = it.eventId,
                roomName = it.roomName,
                sender = it.sender,
                body = it.body,
                isNoisy = it.isNoisy,
                hasMention = it.hasMention,
            )
        }
    }



    override fun roomListSetUnreadOnly(
        token: ULong,
        unreadOnly: Boolean
    ): Boolean {
        return client.roomListSetUnreadOnly(token, unreadOnly)
    }
}

private fun FfiRoom.toModel() = RoomSummary(id = id, name = name)
private fun FfiEvent.toModel() = MessageEvent(
    itemId = itemId, eventId = eventId, roomId = roomId, sender = sender, body = body, timestamp = timestampMs.toLong()
)

actual fun createMatrixPort(hs: String): MatrixPort = RustMatrixPort(hs)