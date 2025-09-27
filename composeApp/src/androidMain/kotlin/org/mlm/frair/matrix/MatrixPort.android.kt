package org.mlm.frair.matrix

import frair.TimelineObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.mlm.frair.MessageEvent
import org.mlm.frair.RoomSummary
import frair.Client as FfiClient
import frair.MessageEvent as FfiEvent
import frair.RoomSummary as FfiRoom

class RustMatrixPort(hs: String) : MatrixPort {
    private val client: FfiClient = FfiClient(hs) // UDL “constructor” maps to Kotlin constructor

    override suspend fun init(hs: String) { /* already constructed */ }

    override suspend fun login(user: String, pass: String) {
        client.login(user, pass)
    }

    override suspend fun listRooms(): List<RoomSummary> =
        client.rooms().map { it.toModel() }

    override suspend fun recent(roomId: String, limit: Int): List<MessageEvent> =
        client.recentEvents(roomId, limit.toUInt()).map { it.toModel() }

    override fun timeline(roomId: String): Flow<MessageEvent> = callbackFlow {
        val obs = object : TimelineObserver {
            override fun onEvent(event: FfiEvent) {
                trySend(event.toModel())
            }
        }
        client.observeRoomTimeline(roomId, obs)
        awaitClose {
            // no-op; keep the client alive; call client.shutdown() when you truly want to stop
        }
    }

    override suspend fun send(roomId: String, body: String) {
        client.sendMessage(roomId, body)
    }

    override fun startSync() = client.startSlidingSync()

    override fun close() = client.shutdown()


    override suspend fun paginateBack(roomId: String, count: Int): Boolean =
        client.paginateBackwards(roomId, count.toUShort())

    override suspend fun paginateForward(roomId: String, count: Int): Boolean =
        client.paginateForwards(roomId, count.toUShort())

    override suspend fun markRead(roomId: String): Boolean =
        client.markRead(roomId)

    override suspend fun markReadAt(roomId: String, eventId: String): Boolean =
        client.markReadAt(roomId, eventId)

    override suspend fun react(roomId: String, eventId: String, emoji: String): Boolean =
        client.react(roomId, eventId, emoji)

    override suspend fun reply(roomId: String, inReplyToEventId: String, body: String): Boolean =
        client.reply(roomId, inReplyToEventId, body)

    override suspend fun edit(roomId: String, targetEventId: String, newBody: String): Boolean =
        client.edit(roomId, targetEventId, newBody)
}

private fun FfiRoom.toModel() = RoomSummary(id = id, name = name)
private fun FfiEvent.toModel() = MessageEvent(
    eventId = eventId,
    roomId = roomId,
    sender = sender,
    body = body,
    timestamp = timestampMs.toLong()
)

actual fun createMatrixPort(hs: String): MatrixPort = RustMatrixPort(hs)
