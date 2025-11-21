package org.mlm.mages

import kotlinx.serialization.Serializable

@Serializable
data class RoomSummary(
    val id: String,
    val name: String
)

@Serializable
data class MessageEvent(
    val itemId: String,
    val eventId: String,
    val roomId: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val sendState: org.mlm.mages.matrix.SendState? = null,
    val txnId: String? = null

)