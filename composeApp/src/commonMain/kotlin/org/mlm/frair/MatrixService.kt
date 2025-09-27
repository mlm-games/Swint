package org.mlm.frair

import kotlinx.coroutines.flow.Flow
import org.mlm.frair.matrix.MatrixPort
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class MatrixService(private val port: MatrixPort) {
    suspend fun init(hs: String) = run { port.init(hs); true }
    suspend fun login(user: String, password: String) = try {
        port.login(user, password)
        true
    } catch (e: Exception) {
        println("Login failed: ${e.message}")
        e.printStackTrace()
        false
    }
    suspend fun listRooms(): List<RoomSummary> = port.listRooms()
    suspend fun loadRecent(roomId: String, limit: Int = 50): List<MessageEvent> = port.recent(roomId, limit)
    fun timeline(roomId: String): Flow<MessageEvent> = port.timeline(roomId)
    suspend fun sendMessage(roomId: String, body: String) = runCatching { port.send(roomId, body) }.isSuccess
    @OptIn(ExperimentalTime::class)
    fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}