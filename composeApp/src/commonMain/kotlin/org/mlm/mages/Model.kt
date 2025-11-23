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
    val txnId: String? = null,
    val replyToEventId: String? = null,
    val replyToSender: String? = null,
    val replyToBody: String? = null,
    val attachment: AttachmentInfo? = null,
)

@Serializable
enum class AttachmentKind { Image, Video, File }

@Serializable
data class AttachmentInfo(
    val kind: AttachmentKind,
    val mxcUri: String,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailMxcUri: String? = null
)