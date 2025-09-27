package org.mlm.frair

import kotlinx.serialization.Serializable

@Serializable
data class RoomSummary(
    val id: String,
    val name: String
)

@Serializable
data class MessageEvent(
    val eventId: String,
    val roomId: String,
    val sender: String,
    val body: String,
    val timestamp: Long
)

@Serializable
data class RoomsPayload(val rooms: List<RoomSummary>)

@Serializable
data class EventsPayload(val events: List<MessageEvent>)
