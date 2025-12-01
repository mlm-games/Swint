package org.mlm.mages.matrix

import kotlinx.coroutines.Dispatchers
import mages.SasEmojis
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import mages.FfiRoomNotificationMode
import org.mlm.mages.AttachmentInfo
import org.mlm.mages.AttachmentKind
import org.mlm.mages.EncFile
import mages.Client as FfiClient
import mages.RoomSummary as FfiRoom
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.platform.MagesPaths

class RustMatrixPort(hs: String) : MatrixPort {
    @Volatile
    private var client: FfiClient = FfiClient(hs, MagesPaths.storeDir())
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
        val obs = object : mages.TimelineObserver {
            override fun onDiff(diff: mages.TimelineDiffKind) {
                val mapped: TimelineDiff<MessageEvent> = when (diff) {
                    is mages.TimelineDiffKind.Reset -> TimelineDiff.Reset(diff.values.map { it.toModel() })
                    is mages.TimelineDiffKind.Clear -> TimelineDiff.Clear
                    is mages.TimelineDiffKind.Append -> {
                        diff.values.forEach { trySendBlocking(TimelineDiff.Insert(it.toModel())) }
                        return
                    }
                    is mages.TimelineDiffKind.PushBack -> TimelineDiff.Insert(diff.value.toModel())
                    is mages.TimelineDiffKind.PushFront -> TimelineDiff.Insert(diff.value.toModel())
                    is mages.TimelineDiffKind.Insert -> TimelineDiff.Insert(diff.value.toModel())
                    is mages.TimelineDiffKind.Set -> TimelineDiff.Update(diff.value.toModel())
                    is mages.TimelineDiffKind.Remove -> TimelineDiff.Remove(diff.index.toString())
                    is mages.TimelineDiffKind.PopFront -> return
                    is mages.TimelineDiffKind.PopBack -> return
                    is mages.TimelineDiffKind.Truncate -> return
                }
                trySendBlocking(mapped)
            }
            override fun onError(message: String) { /* log */ }
        }

        val token = client.observeTimeline(roomId, obs)
        awaitClose { client.unobserveTimeline(token) }
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

            override fun onError(message: String) {
                observer.onError(message)
            }
        }
        return client.startVerificationInbox(cb)
    }

    override fun stopVerificationInbox(token: ULong) {
        client.unobserveVerificationInbox(token)
    }

    override suspend fun send(roomId: String, body: String): Boolean =
        client.sendMessage(roomId, body)

    override suspend fun enqueueText(roomId: String, body: String, txnId: String?): String =
        client.enqueueText(roomId, body, txnId)

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

    override suspend fun thumbnailToCache(
        info: AttachmentInfo,
        width: Int,
        height: Int,
        crop: Boolean
    ): Result<String> =
        runCatching { client.thumbnailToCache(info.toFfi(), width.toUInt(), height.toUInt(), crop) }

    override suspend fun setTyping(roomId: String, typing: Boolean): Boolean {
        return client.setTyping(roomId, typing)
    }

    override fun whoami(): String? {
        return client.whoami()
    }

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

    override fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong {
        val obs = object : mages.TypingObserver {
            override fun onUpdate(names: List<String>) {
                onUpdate(names)
            }
        }
        return client.observeTyping(roomId, obs)
    }

    override fun stopTypingObserver(token: ULong) {
        client.unobserveTyping(token)
    }

    override fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong {
        val cb = object : mages.ReceiptsObserver {
            override fun onChanged() {
                observer.onChanged()
            }
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
                verified = it.verified
            )
        }

    private fun mages.SasPhase.toCommon(): SasPhase = when (this) {
        mages.SasPhase.REQUESTED -> SasPhase.Requested
        mages.SasPhase.READY -> SasPhase.Ready
        mages.SasPhase.EMOJIS -> SasPhase.Emojis
        mages.SasPhase.CONFIRMED -> SasPhase.Confirmed
        mages.SasPhase.CANCELLED -> SasPhase.Cancelled
        mages.SasPhase.FAILED -> SasPhase.Failed
        mages.SasPhase.DONE -> SasPhase.Done
    }

    override suspend fun startSelfSas(
        targetDeviceId: String,
        observer: VerificationObserver
    ): String {
        val obs = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }

            override fun onEmojis(payload: SasEmojis) {
                observer.onEmojis(
                    payload.flowId,
                    payload.otherUser,
                    payload.otherDevice,
                    payload.emojis
                )
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

            override fun onEmojis(payload: SasEmojis) {
                observer.onEmojis(
                    payload.flowId,
                    payload.otherUser,
                    payload.otherDevice,
                    payload.emojis
                )
            }

            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.startUserSas(userId, obs)
    }

    override suspend fun acceptVerification(
        flowId: String,
        otherUserId: String?,
        observer: VerificationObserver
    ): Boolean {
        val cb = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(
                    flowId, when (phase) {
                        mages.SasPhase.REQUESTED -> SasPhase.Requested
                        mages.SasPhase.READY -> SasPhase.Ready
                        mages.SasPhase.EMOJIS -> SasPhase.Emojis
                        mages.SasPhase.CONFIRMED -> SasPhase.Confirmed
                        mages.SasPhase.CANCELLED -> SasPhase.Cancelled
                        mages.SasPhase.FAILED -> SasPhase.Failed
                        mages.SasPhase.DONE -> SasPhase.Done
                    }
                )
            }

            override fun onEmojis(payload: SasEmojis) {
                observer.onEmojis(
                    payload.flowId,
                    payload.otherUser,
                    payload.otherDevice,
                    payload.emojis
                )
            }

            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        return client.acceptVerification(flowId, otherUserId, cb)
    }

    override suspend fun confirmVerification(flowId: String): Boolean =
        client.confirmVerification(flowId)

    override suspend fun cancelVerification(flowId: String): Boolean =
        client.cancelVerification(flowId)

    override suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?): Boolean =
        client.cancelVerificationRequest(flowId, otherUserId)

    override fun enterForeground() {
        client.enterForeground()
    }

    override fun enterBackground() {
        client.enterBackground()
    }

    override suspend fun logout(): Boolean = client.logout()

    override suspend fun retryByTxn(roomId: String, txnId: String): Boolean {
        return client.retryByTxn(roomId, txnId)
    }

    override suspend fun downloadToCacheFile(
        mxcUri: String,
        filenameHint: String?
    ): Result<String> {
        return runCatching { client.downloadToCacheFile(mxcUri, filenameHint).path }
    }

    override suspend fun checkVerificationRequest(userId: String, flowId: String): Boolean =
        client.checkVerificationRequest(userId, flowId)

    override suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
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
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        return client.sendAttachmentBytes(roomId, filename, mime, data, cb)
    }

    override suspend fun downloadToPath(
        mxcUri: String,
        savePath: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<String> {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        return runCatching { client.downloadToPath(mxcUri, savePath, cb).path }
    }

    override suspend fun recoverWithKey(recoveryKey: String): Boolean =
        client.recoverWithKey(recoveryKey)

    override suspend fun registerUnifiedPush(
        appId: String,
        pushKey: String,
        gatewayUrl: String,
        deviceName: String,
        lang: String,
        profileTag: String?,
    ): Boolean =
        client.registerUnifiedpush(appId, pushKey, gatewayUrl, deviceName, lang, profileTag)

    override suspend fun unregisterUnifiedPush(
        appId: String,
        pushKey: String,
    ): Boolean = client.unregisterUnifiedpush(appId, pushKey)

    override suspend fun roomUnreadStats(roomId: String): UnreadStats? =
        client.roomUnreadStats(roomId)?.let {
            UnreadStats(
                it.messages.toLong(),
                it.notifications.toLong(),
                it.mentions.toLong()
            )
        }

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
        return client.observeOwnReceipt(roomId, cb)
    }

    override suspend fun roomNotificationMode(roomId: String): RoomNotificationMode? =
        client.roomNotificationMode(roomId)?.toKotlin()

    override suspend fun setRoomNotificationMode(roomId: String, mode: RoomNotificationMode): Boolean =
        runCatching { client.setRoomNotificationMode(roomId, mode.toFfi()) }.isSuccess

    override suspend fun markFullyReadAt(
        roomId: String,
        eventId: String,
    ): Boolean = client.markFullyReadAt(roomId, eventId)

    override suspend fun encryptionCatchupOnce(): Boolean = client.encryptionCatchupOnce()

    override fun observeRoomList(observer: MatrixPort.RoomListObserver): ULong {
        val cb = object : mages.RoomListObserver {
            override fun onReset(items: List<mages.RoomListEntry>) {
                val mapped = items.map {
                    MatrixPort.RoomListEntry(
                        roomId = it.roomId,
                        name = it.name,
                        lastTs = it.lastTs,
                        notifications = it.notifications,
                        messages = it.messages,
                        mentions = it.mentions,
                        markedUnread = it.markedUnread,
                        isFavourite = it.isFavourite,
                        isLowPriority = it.isLowPriority
                    )
                }
                observer.onReset(mapped)
            }

            override fun onUpdate(item: mages.RoomListEntry) {
                observer.onUpdate(
                    MatrixPort.RoomListEntry(
                        roomId = item.roomId,
                        name = item.name,
                        lastTs = item.lastTs,
                        notifications = item.notifications,
                        messages = item.messages,
                        mentions = item.mentions,
                        markedUnread = item.markedUnread,
                        isFavourite = item.isFavourite,
                        isLowPriority = item.isLowPriority
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
                senderUserId = it.senderUserId,
                tsMs = it.tsMs.toLong(),
            )
        }
    }

    override suspend fun fetchNotificationsSince(
        sinceMs: Long,
        maxRooms: Int,
        maxEvents: Int
    ): List<RenderedNotification> =
        runCatching {
            client.fetchNotificationsSince(
                sinceMs.toULong(),
                maxRooms.toUInt(),
                maxEvents.toUInt()
            )
        }.getOrElse { emptyList() }
            .map {
                RenderedNotification(
                    roomId = it.roomId,
                    eventId = it.eventId,
                    roomName = it.roomName,
                    sender = it.sender,
                    body = it.body,
                    isNoisy = it.isNoisy,
                    hasMention = it.hasMention,
                    senderUserId = it.senderUserId,
                    tsMs = it.tsMs.toLong()
                )
            }

    override fun roomListSetUnreadOnly(
        token: ULong,
        unreadOnly: Boolean
    ): Boolean {
        return client.roomListSetUnreadOnly(token, unreadOnly)
    }

    override suspend fun loginSsoLoopback(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): Boolean {
        val opener = object : mages.UrlOpener {
            override fun open(url: String): Boolean = openUrl(url)
        }
        return runCatching { client.loginSsoLoopback(opener, deviceName) }.isSuccess
    }

    override suspend fun searchUsers(term: String, limit: Int): List<DirectoryUser> =
        client.searchUsers(term, limit.toULong()).map { u ->
            DirectoryUser(u.userId, u.displayName, u.avatarUrl)
        }

    override suspend fun publicRooms(
        server: String?,
        search: String?,
        limit: Int,
        since: String?
    ): PublicRoomsPage {
        val resp = client.publicRooms(server, search, limit.toUInt(), since)
        return PublicRoomsPage(
            rooms = resp.rooms.map {
                PublicRoom(
                    roomId = it.roomId,
                    name = it.name,
                    topic = it.topic,
                    alias = it.alias,
                    avatarUrl = it.avatarUrl,
                    memberCount = it.memberCount.toLong(),
                    worldReadable = it.worldReadable,
                    guestCanJoin = it.guestCanJoin
                )
            },
            nextBatch = resp.nextBatch,
            prevBatch = resp.prevBatch
        )
    }

    override suspend fun joinByIdOrAlias(idOrAlias: String): Boolean =
        client.joinByIdOrAlias(idOrAlias)

    override suspend fun ensureDm(userId: String): String? =
        runCatching { client.ensureDm(userId) }.getOrNull()

    override suspend fun resolveRoomId(idOrAlias: String): String? =
        runCatching { client.resolveRoomId(idOrAlias) }.getOrNull()

    override suspend fun listInvited(): List<RoomProfile> = withContext(Dispatchers.IO) {
        client.listInvited().map {
            RoomProfile(it.roomId, it.name, it.topic, it.memberCount.toLong(), it.isEncrypted, it.isDm)
        }
    }

    override suspend fun acceptInvite(roomId: String): Boolean = withContext(Dispatchers.IO) {
        client.acceptInvite(roomId)
    }

    override suspend fun leaveRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.leaveRoom(roomId) }.isSuccess
    }

    override suspend fun createRoom(
        name: String?, topic: String?, invitees: List<String>
    ): String? = withContext(Dispatchers.IO) {
        runCatching { client.createRoom(name, topic, invitees) }.getOrNull()
    }

    override suspend fun setRoomName(roomId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        client.setRoomName(roomId, name)
    }

    override suspend fun setRoomTopic(roomId: String, topic: String): Boolean = withContext(Dispatchers.IO) {
        client.setRoomTopic(roomId, topic)
    }

    override suspend fun roomProfile(roomId: String): RoomProfile? = withContext(Dispatchers.IO) {
        runCatching { client.roomProfile(roomId) }.getOrNull()?.let {
            RoomProfile(
                it.roomId,
                it.name,
                it.topic,
                it.memberCount.toLong(),
                it.isEncrypted,
                it.isDm
            )
        }
    }

    override suspend fun listMembers(roomId: String): List<MemberSummary> = withContext(Dispatchers.IO) {
        runCatching { client.listMembers(roomId) }.getOrElse { emptyList() }.map {
            MemberSummary(it.userId, it.displayName, it.isMe, it.membership)
        }
    }

    override suspend fun reactions(roomId: String, eventId: String): List<ReactionChip> =
        client.reactionsForEvent(roomId, eventId).map { ReactionChip(it.key, it.count.toInt(), it.me) }

    override suspend fun sendThreadText(roomId: String, rootEventId: String, body: String, replyToEventId: String?): Boolean =
        client.sendThreadText(roomId, rootEventId, body, replyToEventId)

    override suspend fun threadSummary(roomId: String, rootEventId: String, perPage: Int, maxPages: Int): ThreadSummary {
        val s = client.threadSummary(roomId, rootEventId, perPage.toUInt(), maxPages.toUInt())
        return ThreadSummary(s.rootEventId, s.roomId, s.count.toLong(), s.latestTsMs?.toLong())
    }

    override suspend fun threadReplies(
        roomId: String,
        rootEventId: String,
        from: String?,
        limit: Int,
        forward: Boolean
    ): ThreadPage {
        val page = client.threadReplies(roomId, rootEventId, from, limit.toUInt(), forward)
        return ThreadPage(
            rootEventId = page.rootEventId,
            roomId = page.roomId,
            messages = page.messages.map { it.toModel() },
            nextBatch = page.nextBatch,
            prevBatch = page.prevBatch
        )
    }

    override suspend fun isSpace(roomId: String): Boolean {
        return try {
            client.isSpace(roomId)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun mySpaces(): List<SpaceInfo> {
        return try {
            client.mySpaces().map { space ->
                SpaceInfo(
                    roomId = space.roomId,
                    name = space.name,
                    topic = space.topic,
                    memberCount = space.memberCount.toLong(),
                    isEncrypted = space.isEncrypted,
                    isPublic = space.isPublic
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): String? {
        return try {
            client.createSpace(name, topic, isPublic, invitees)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String?,
        suggested: Boolean?
    ): Boolean {
        return try {
            client.spaceAddChild(spaceId, childRoomId, order, suggested)
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun spaceRemoveChild(
        spaceId: String,
        childRoomId: String
    ): Boolean {
        return try {
            client.spaceRemoveChild(spaceId, childRoomId)
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage? {
        return try {
            val page = client.spaceHierarchy(
                spaceId = spaceId,
                from = from,
                limit = limit.toUInt(),
                maxDepth = maxDepth?.toUInt(),
                suggestedOnly = suggestedOnly
            )
            SpaceHierarchyPage(
                children = page.children.map { child ->
                    SpaceChildInfo(
                        roomId = child.roomId,
                        name = child.name,
                        topic = child.topic,
                        alias = child.alias,
                        avatarUrl = child.avatarUrl,
                        isSpace = child.isSpace,
                        memberCount = child.memberCount.toLong(),
                        worldReadable = child.worldReadable,
                        guestCanJoin = child.guestCanJoin,
                        suggested = child.suggested
                    )
                },
                nextBatch = page.nextBatch
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun spaceInviteUser(spaceId: String, userId: String): Boolean {
        return try {
            client.spaceInviteUser(spaceId, userId)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>? = withContext(Dispatchers.IO) {
        runCatching { client.roomTags(roomId) }.getOrNull()?.let {
            it.isFavourite to it.isLowPriority
        }
    }

    override suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.setRoomFavourite(roomId, favourite) }.isSuccess
    }

    override suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.setRoomLowPriority(roomId, lowPriority) }.isSuccess
    }


    override suspend fun setPresence(presence: Presence, status: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.setPresence(presence.toFfi(), status) }.isSuccess
    }

    override suspend fun getPresence(userId: String): Pair<Presence, String?>? =
        withContext(Dispatchers.IO) {
            val info = runCatching { client.getPresence(userId) }.getOrNull()
                ?: return@withContext null

            info.presence.toKotlin() to info.statusMsg
        }

    override suspend fun ignoreUser(userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.ignoreUser(userId) }.isSuccess
    }

    override suspend fun unignoreUser(userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.unignoreUser(userId) }.isSuccess
    }

    override suspend fun ignoredUsers(): List<String> = withContext(Dispatchers.IO) {
        runCatching { client.ignoredUsers() }.getOrElse { emptyList() }
    }

    override suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility? = withContext(Dispatchers.IO) {
        runCatching { client.roomDirectoryVisibility(roomId) }.getOrNull()?.toKotlin()
    }

    override suspend fun setRoomDirectoryVisibility(roomId: String, visibility: RoomDirectoryVisibility): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.setRoomDirectoryVisibility(roomId, visibility.toFfi()) }.isSuccess
    }

    override suspend fun banUser(roomId: String, userId: String, reason: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.banUser(roomId, userId, reason) }.isSuccess
    }

    override suspend fun unbanUser(roomId: String, userId: String, reason: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.unbanUser(roomId, userId, reason) }.isSuccess
    }

    override suspend fun kickUser(roomId: String, userId: String, reason: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.kickUser(roomId, userId, reason) }.isSuccess
    }

    override suspend fun inviteUser(roomId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.inviteUser(roomId, userId) }.isSuccess
    }

    override suspend fun enableRoomEncryption(roomId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.enableRoomEncryption(roomId) }.isSuccess
    }

    override suspend fun roomSuccessor(roomId: String): RoomUpgradeInfo? = withContext(Dispatchers.IO) {
        runCatching { client.roomSuccessor(roomId) }.getOrNull()?.let { ffi ->
            RoomUpgradeInfo(roomId = ffi.roomId, reason = ffi.reason)
        }
    }

    override suspend fun roomPredecessor(roomId: String): RoomPredecessorInfo? = withContext(Dispatchers.IO) {
        runCatching { client.roomPredecessor(roomId) }.getOrNull()?.let { ffi ->
            RoomPredecessorInfo(roomId = ffi.roomId)
        }
    }


    override suspend fun startLiveLocationShare(
        roomId: String,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.startLiveLocation(roomId, durationMs.toULong(), null) }.isSuccess
    }

    override suspend fun stopLiveLocationShare(roomId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { client.stopLiveLocation(roomId) }.isSuccess
        }

    override suspend fun sendLiveLocation(roomId: String, geoUri: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { client.sendLiveLocation(roomId, geoUri) }.isSuccess
        }

    override fun observeLiveLocation(
        roomId: String,
        onShares: (List<LiveLocationShare>) -> Unit
    ): ULong {
        val cb = object : mages.LiveLocationObserver {
            override fun onUpdate(shares: List<mages.LiveLocationShareInfo>) {
                val mapped = shares.map {
                    LiveLocationShare(
                        userId = it.userId,
                        geoUri = it.geoUri,
                        tsMs = it.tsMs.toLong(),
                        isLive = it.isLive
                    )
                }
                onShares(mapped)
            }
        }
        return client.observeLiveLocation(roomId, cb)
    }

    override fun stopObserveLiveLocation(token: ULong) {
        client.unobserveLiveLocation(token)
    }


    override suspend fun sendPoll(
        roomId: String,
        question: String,
        answers: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val def = mages.PollDefinition(
            question = question,
            answers = answers,
            kind = mages.PollKind.DISCLOSED,
            maxSelections = 1u
        )

        runCatching { client.sendPollStart(roomId, def) }.isSuccess
    }

    override suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String?,
        observer: CallWidgetObserver
    ): CallSession? {
        val ffiIntent = when (intent) {
            CallIntent.StartCall -> mages.ElementCallIntent.START_CALL
            CallIntent.JoinExisting -> mages.ElementCallIntent.JOIN_EXISTING
        }
        val cb = object : mages.CallWidgetObserver {
            override fun onToWidget(message: String) {
                observer.onToWidget(message)
            }
        }
        return try {
            val info = client.startElementCall(roomId, elementCallUrl, ffiIntent, cb)
            CallSession(
                sessionId = info.sessionId,
                widgetUrl = info.widgetUrl
            )
        } catch (_: Throwable) {
            null
        }
    }

    override fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean =
        runCatching { client.callWidgetFromWebview(sessionId, message) }.isSuccess

    override fun stopElementCall(sessionId: ULong): Boolean =
        client.stopElementCall(sessionId)
}


private fun FfiRoom.toModel() = RoomSummary(id = id, name = name)

private fun mages.MessageEvent.toModel() = MessageEvent(
    itemId = itemId,
    eventId = eventId,
    roomId = roomId,
    sender = sender,
    body = body,
    timestamp = timestampMs.toLong(),
    sendState = sendState?.toKotlin(),
    txnId = txnId,
    replyToEventId = replyToEventId,
    replyToSender = replyToSender,
    replyToBody = replyToBody,
    attachment = attachment?.toModel(),
    threadRootEventId = threadRootEventId
)

private fun mages.SendState.toKotlin(): SendState = when (this) {
    mages.SendState.SENDING -> SendState.Sending
    mages.SendState.SENT -> SendState.Sent
    mages.SendState.FAILED -> SendState.Failed
    mages.SendState.ENQUEUED -> SendState.Enqueued
    mages.SendState.RETRYING -> SendState.Retrying
}

private fun mages.EncFile.toModel() = EncFile(url = url, json = json)

private fun mages.AttachmentInfo.toModel() = AttachmentInfo(
    kind = when (kind) {
        mages.AttachmentKind.IMAGE -> AttachmentKind.Image
        mages.AttachmentKind.VIDEO -> AttachmentKind.Video
        mages.AttachmentKind.FILE -> AttachmentKind.File
    },
    mxcUri = mxcUri,
    mime = mime,
    sizeBytes = sizeBytes?.toLong(),
    width = width?.toInt(),
    height = height?.toInt(),
    durationMs = durationMs?.toLong(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toModel(),
    thumbnailEncrypted = thumbnailEncrypted?.toModel(),
)

private fun EncFile.toFfi() = mages.EncFile(url = url, json = json)

private fun FfiRoomNotificationMode.toKotlin(): RoomNotificationMode = when (this) {
    FfiRoomNotificationMode.ALL_MESSAGES -> RoomNotificationMode.AllMessages
    FfiRoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> RoomNotificationMode.MentionsAndKeywordsOnly
    FfiRoomNotificationMode.MUTE -> RoomNotificationMode.Mute
}

private fun AttachmentInfo.toFfi() = mages.AttachmentInfo(
    kind = when (kind) {
        AttachmentKind.Image -> mages.AttachmentKind.IMAGE
        AttachmentKind.Video -> mages.AttachmentKind.VIDEO
        AttachmentKind.File  -> mages.AttachmentKind.FILE
    },
    mxcUri = mxcUri,
    mime = mime,
    sizeBytes = sizeBytes?.toULong(),
    width = width?.toUInt(),
    height = height?.toUInt(),
    durationMs = durationMs?.toULong(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toFfi(),
    thumbnailEncrypted = thumbnailEncrypted?.toFfi(),
)

private fun RoomNotificationMode.toFfi(): FfiRoomNotificationMode = when (this) {
    RoomNotificationMode.AllMessages -> FfiRoomNotificationMode.ALL_MESSAGES
    RoomNotificationMode.MentionsAndKeywordsOnly -> FfiRoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
    RoomNotificationMode.Mute -> FfiRoomNotificationMode.MUTE
}

private fun Presence.toFfi(): mages.Presence = when (this) {
    Presence.Online -> mages.Presence.ONLINE
    Presence.Offline -> mages.Presence.OFFLINE
    Presence.Unavailable -> mages.Presence.UNAVAILABLE
}

private fun mages.Presence.toKotlin(): Presence = when (this) {
    mages.Presence.ONLINE -> Presence.Online
    mages.Presence.OFFLINE -> Presence.Offline
    mages.Presence.UNAVAILABLE -> Presence.Unavailable
}

private fun RoomDirectoryVisibility.toFfi(): mages.RoomDirectoryVisibility = when (this) {
    RoomDirectoryVisibility.Public -> mages.RoomDirectoryVisibility.PUBLIC
    RoomDirectoryVisibility.Private -> mages.RoomDirectoryVisibility.PRIVATE
}

private fun mages.RoomDirectoryVisibility.toKotlin(): RoomDirectoryVisibility = when (this) {
    mages.RoomDirectoryVisibility.PUBLIC -> RoomDirectoryVisibility.Public
    mages.RoomDirectoryVisibility.PRIVATE -> RoomDirectoryVisibility.Private
}

actual fun createMatrixPort(hs: String): MatrixPort = RustMatrixPort(hs)