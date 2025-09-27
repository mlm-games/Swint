package org.mlm.frair.matrix

import kotlinx.coroutines.flow.Flow
import org.mlm.frair.MessageEvent
import org.mlm.frair.RoomSummary

interface MatrixPort {
    suspend fun init(hs: String)
    suspend fun login(user: String, pass: String)
    suspend fun listRooms(): List<RoomSummary>
    suspend fun recent(roomId: String, limit: Int = 50): List<MessageEvent>
    fun timeline(roomId: String): Flow<MessageEvent>
    suspend fun send(roomId: String, body: String)
    fun startSync()
    fun close()
}

expect fun createMatrixPort(hs: String): MatrixPort