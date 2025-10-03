package org.mlm.frair

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import frair.Client as FfiClient
import frair.MessageEvent as FfiEvent
import frair.TimelineObserver
import org.mlm.frair.MessageEvent
import org.mlm.frair.matrix.DeviceSummary
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.matrix.SasPhase
import org.mlm.frair.matrix.SendState
import org.mlm.frair.matrix.SendUpdate
import org.mlm.frair.matrix.VerificationObserver

internal class RustMatrixPort(hs: String) : MatrixPort {
    private val client = FfiClient(hs)

    override suspend fun init(hs: String) { /* constructed with hs */ }
    override suspend fun login(user: String, pass: String) { client.login(user, pass) }
    override fun isLoggedIn(): Boolean = client.isLoggedIn()

    override suspend fun listRooms(): List<RoomSummary> = client.rooms().map { it.toModel() }
    override suspend fun recent(roomId: String, limit: Int): List<MessageEvent> =
        client.recentEvents(roomId, limit.toUInt()).map { it.toModel() }

    override fun timeline(roomId: String): Flow<MessageEvent> = callbackFlow {
        val obs = object : TimelineObserver {
            override fun onEvent(event: FfiEvent) { trySend(event.toModel()) }
        }
        client.observeRoomTimeline(roomId, obs)
        awaitClose { }
    }

    override suspend fun initCaches(): Boolean = client.initCaches()

    override suspend fun cacheMessages(roomId: String, messages: List<MessageEvent>): Boolean {
        val ffiMessages = messages.map { msg ->
            frair.MessageEvent(
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
        val ffiState = frair.PaginationState(
            roomId = state.roomId,
            prevBatch = state.prevBatch,
            nextBatch = state.nextBatch,
            atStart = state.atStart,
            atEnd = state.atEnd
        )
        return client.savePaginationState(ffiState)
    }

    override suspend fun getPaginationState(roomId: String): MatrixPort.PaginationState? =
        client.getPaginationState(roomId)?.let { ffi ->
            MatrixPort.PaginationState(
                roomId = ffi.roomId,
                prevBatch = ffi.prevBatch,
                nextBatch = ffi.nextBatch,
                atStart = ffi.atStart,
                atEnd = ffi.atEnd
            )
        }

    override fun observeConnection(observer: MatrixPort.ConnectionObserver) {
        val cb = object : frair.ConnectionObserver {
            override fun onConnectionChange(state: frair.ConnectionState) {
                val mapped = when (state) {
                    frair.ConnectionState.Disconnected -> MatrixPort.ConnectionState.Disconnected
                    frair.ConnectionState.Connecting -> MatrixPort.ConnectionState.Connecting
                    frair.ConnectionState.Connected -> MatrixPort.ConnectionState.Connected
                    frair.ConnectionState.Syncing -> MatrixPort.ConnectionState.Syncing
                    frair.ConnectionState.Reconnecting -> MatrixPort.ConnectionState.Reconnecting
                }
                observer.onConnectionChange(mapped)
            }
        }
        client.monitorConnection(cb)
    }

    override suspend fun send(roomId: String, body: String) { client.sendMessage(roomId, body) }

    override suspend fun enqueueText(roomId: String, body: String, txnId: String?): String =
        client.enqueueText(roomId, body, txnId)

    override fun startSendWorker() = client.startSendWorker()

    override fun observeSends(): Flow<SendUpdate> = callbackFlow {
        val obs = object : frair.SendObserver {
            override fun onUpdate(update: frair.SendUpdate) {
                trySend(
                    SendUpdate(
                        roomId = update.roomId,
                        txnId = update.txnId,
                        attempts = update.attempts.toInt(),
                        state = when (update.state) {
                            frair.SendState.Enqueued -> SendState.Enqueued
                            frair.SendState.Sending -> SendState.Sending
                            frair.SendState.Sent -> SendState.Sent
                            frair.SendState.Retrying -> SendState.Retrying
                            frair.SendState.Failed -> SendState.Failed
                        },
                        eventId = update.eventId,
                        error = update.error
                    )
                )
            }
        }
        client.observeSends(obs)
        awaitClose { }
    }

    override suspend fun mediaCacheStats(): Pair<Long, Long> {
        val s = client.mediaCacheStats()
        return s.bytes.toLong() to s.files.toLong()
    }

    override suspend fun mediaCacheEvict(maxBytes: Long): Long =
        client.mediaCacheEvict(maxBytes.toULong()).toLong()

    override suspend fun thumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): Result<String> =
        runCatching { client.thumbnailToCache(mxcUri, width.toUInt(), height.toUInt(), crop) }

    override fun startVerificationInbox(observer: MatrixPort.VerificationInboxObserver) {
        val cb = object : frair.VerificationInboxObserver {
            override fun onRequest(flowId: String, fromUser: String, fromDevice: String) {
                observer.onRequest(flowId, fromUser, fromDevice)
            }
            override fun onError(message: String) { observer.onError(message) }
        }
        client.startVerificationInbox(cb)
    }

    override fun close() = client.shutdown()

    override suspend fun paginateBack(roomId: String, count: Int) =
        client.paginateBackwards(roomId, count.toUShort())

    override suspend fun paginateForward(roomId: String, count: Int) =
        client.paginateForwards(roomId, count.toUShort())

    override suspend fun markRead(roomId: String) = client.markRead(roomId)

    override suspend fun markReadAt(roomId: String, eventId: String) =
        client.markReadAt(roomId, eventId)

    override suspend fun react(roomId: String, eventId: String, emoji: String) =
        client.react(roomId, eventId, emoji)

    override suspend fun reply(roomId: String, inReplyToEventId: String, body: String) =
        client.reply(roomId, inReplyToEventId, body)

    override suspend fun edit(roomId: String, targetEventId: String, newBody: String) =
        client.edit(roomId, targetEventId, newBody)

    override suspend fun redact(roomId: String, eventId: String, reason: String?) =
        client.redact(roomId, eventId, reason)

    override fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit) {
        val obs = object : frair.TypingObserver {
            override fun onUpdate(names: List<String>) { onUpdate(names) }
        }
        client.observeTyping(roomId, obs)
    }

    override fun startSupervisedSync(observer: MatrixPort.SyncObserver) {
        val cb = object : frair.SyncObserver {
            override fun onState(status: frair.SyncStatus) {
                val phase = when (status.phase) {
                    frair.SyncPhase.Idle -> MatrixPort.SyncPhase.Idle
                    frair.SyncPhase.Running -> MatrixPort.SyncPhase.Running
                    frair.SyncPhase.BackingOff -> MatrixPort.SyncPhase.BackingOff
                    frair.SyncPhase.Error -> MatrixPort.SyncPhase.Error
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

    private fun frair.SasPhase.toCommon(): SasPhase = when (this) {
        frair.SasPhase.Requested -> SasPhase.Requested
        frair.SasPhase.Ready -> SasPhase.Ready
        frair.SasPhase.Emojis -> SasPhase.Emojis
        frair.SasPhase.Confirmed -> SasPhase.Confirmed
        frair.SasPhase.Cancelled -> SasPhase.Cancelled
        frair.SasPhase.Failed -> SasPhase.Failed
        frair.SasPhase.Done -> SasPhase.Done
    }

    override suspend fun startSelfSas(targetDeviceId: String, observer: VerificationObserver): String {
        val obs = object : frair.VerificationObserver {
            override fun onPhase(flowId: String, phase: frair.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }
            override fun onEmojis(payload: frair.SasEmojis) {
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
            }
            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.startSelfSas(targetDeviceId, obs)
    }

    override suspend fun startUserSas(userId: String, observer: VerificationObserver): String {
        val obs = object : frair.VerificationObserver {
            override fun onPhase(flowId: String, phase: frair.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }
            override fun onEmojis(payload: frair.SasEmojis) {
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
            }
            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.startUserSas(userId, obs)
    }

    override suspend fun acceptVerification(flowId: String, observer: VerificationObserver): Boolean {
        val obs = object : frair.VerificationObserver {
            override fun onPhase(flowId: String, phase: frair.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }
            override fun onEmojis(payload: frair.SasEmojis) {
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)
            }
            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.acceptVerification(flowId, obs)
    }

    override suspend fun confirmVerification(flowId: String): Boolean =
        client.confirmVerification(flowId)

    override suspend fun cancelVerification(flowId: String): Boolean =
        client.cancelVerification(flowId)

    override suspend fun logout(): Boolean = client.logout()

    override suspend fun cancelTxn(txnId: String): Boolean = client.cancelTxn(txnId)

    override suspend fun retryTxnNow(txnId: String): Boolean = client.retryTxnNow(txnId)

    override suspend fun pendingSends(): UInt = client.pendingSends()

    override suspend fun checkVerificationRequest(userId: String, flowId: String): Boolean =
        client.checkVerificationRequest(userId, flow_id = flowId)

    override suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean {
        val cb = if (onProgress != null) object : frair.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        return client.sendAttachmentFromPath(roomId, path, mime, filename, cb)
    }

    override suspend fun sendAttachmentBytes(
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean {
        val cb = if (onProgress != null) object : frair.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        // Pass ByteArray directly (no copy)
        return client.sendAttachmentBytes(roomId, filename, mime, data, cb)
    }

    override suspend fun downloadToPath(
        mxcUri: String,
        savePath: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<String> {
        val cb = if (onProgress != null) object : frair.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        return runCatching { client.downloadToPath(mxcUri, savePath, cb).path }
    }

    override suspend fun recoverWithKey(recoveryKey: String): Boolean =
        client.recoverWithKey(recoveryKey)
}