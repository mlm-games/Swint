package org.mlm.frair

import kotlinx.coroutines.flow.Flow
import org.mlm.frair.matrix.MatrixPort

class MatrixService(val port: MatrixPort) {
    suspend fun init(hs: String) = run { port.init(hs); true }
    suspend fun login(user: String, password: String): Boolean {
        return runCatching {
            port.login(user, password)
            port.startSync()
        }.isSuccess
    }
    suspend fun listRooms() = port.listRooms()
    suspend fun loadRecent(roomId: String, limit: Int = 50) = port.recent(roomId, limit)
    fun timeline(roomId: String): Flow<MessageEvent> = port.timeline(roomId)
    suspend fun sendMessage(roomId: String, body: String) =
        runCatching { port.send(roomId, body) }.isSuccess

    fun startSync() = port.startSync()
}