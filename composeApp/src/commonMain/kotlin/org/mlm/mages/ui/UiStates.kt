package org.mlm.mages.ui

import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.Presence
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomUpgradeInfo
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.components.AttachmentData


data class LoginUiState(
    val homeserver: String = "https://matrix.org",
    val user: String = "",
    val pass: String = "",
    val isBusy: Boolean = false,
    val error: String? = null
)

data class RoomsUiState(
    val rooms: List<RoomSummary> = emptyList(),
    val roomSearchQuery: String = "",
    val unread: Map<String, Int> = emptyMap(),
    val offlineBanner: String? = null,
    val syncBanner: String? = null,
    val unreadOnly: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val favourites: Set<String> = emptySet(),
    val lowPriority: Set<String> = emptySet(),

    val allItems: List<RoomListItemUi> = emptyList(),
    val favouriteItems: List<RoomListItemUi> = emptyList(),
    val normalItems: List<RoomListItemUi> = emptyList(),
    val lowPriorityItems: List<RoomListItemUi> = emptyList(),
)

data class RoomUiState(
    val roomId: String,
    val roomName: String,
    val myUserId: String? = null,
    val allEvents: List<MessageEvent> = emptyList(),
    val events: List<MessageEvent> = emptyList(),
    val input: String = "",
    val replyingTo: MessageEvent? = null,
    val editing: MessageEvent? = null,
    val typingNames: List<String> = emptyList(),
    val isPaginatingBack: Boolean = false,
    val hitStart: Boolean = false,
    val isOffline: Boolean = false,
    val currentAttachment: AttachmentData? = null,
    val isUploadingAttachment: Boolean = false,
    val attachmentProgress: Float = 0f,
    val error: String? = null,

    val lastReadTs: Long? = null,
    val isDm: Boolean = false,
    val lastIncomingFromOthersTs: Long? = null,
    val lastOutgoingRead: Boolean = false,

    val thumbByEvent: Map<String, String> = emptyMap(),
    val reactionChips: Map<String, List<ReactionChip>> = emptyMap(),
    val threadCount: Map<String, Int> = emptyMap(),

    val liveLocationShares: Map<String, LiveLocationShare> = emptyMap(),
    val liveLocationSubToken: ULong? = null,

    val notificationMode: RoomNotificationMode = RoomNotificationMode.AllMessages,
    val isLoadingNotificationMode: Boolean = false,

    val successor: RoomUpgradeInfo? = null,
    val predecessor: RoomPredecessorInfo? = null,

    val members: List<MemberSummary> = emptyList(),
    val isLoadingMembers: Boolean = false,

    val showAttachmentPicker: Boolean = false,
    val showPollCreator: Boolean = false,
    val showLiveLocation: Boolean = false,
    val showNotificationSettings: Boolean = false,
    val showMembers: Boolean = false,
    val selectedMemberForAction: MemberSummary? = null,
    val showInviteDialog: Boolean = false,

    val showForwardPicker: Boolean = false,
    val forwardingEvent: MessageEvent? = null,
    val forwardableRooms: List<ForwardableRoom> = emptyList(),
    val isLoadingForwardRooms: Boolean = false,
    val forwardSearchQuery: String = "",
)


data class ForwardableRoom(
    val roomId: String,
    val name: String,
    val avatarUrl: String?,
    val isDm: Boolean,
    val lastActivity: Long
)

data class PresenceUiState(
    val currentPresence: Presence = Presence.Online,
    val statusMessage: String = "",
    val isSaving: Boolean = false,
)

data class VerificationRequestUi(
    val flowId: String,
    val fromUser: String,
    val fromDevice: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class SecurityUiState(
    val devices: List<DeviceSummary> = emptyList(),
    val isLoadingDevices: Boolean = false,
    val error: String? = null,

    // Tabs
    val selectedTab: Int = 0,

    // Recovery
    val showRecoveryDialog: Boolean = false,
    val recoveryKeyInput: String = "",

    // Verification
    val pendingVerifications: List<VerificationRequestUi> = emptyList(),
    val sasFlowId: String? = null,
    val sasPhase: SasPhase? = null,
    val sasEmojis: List<String> = emptyList(),
    val sasOtherUser: String? = null,
    val sasOtherDevice: String? = null,
    val sasError: String? = null,
    val sasIncoming: Boolean = false,

    // Privacy
    val ignoredUsers: List<String> = emptyList(),

    // Presence
    val presence: PresenceUiState = PresenceUiState(),
)

data class SpacesUiState(
    val spaces: List<SpaceInfo> = emptyList(),
    val filteredSpaces: List<SpaceInfo> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,

    // Create space
    val showCreateSpace: Boolean = false,
    val createName: String = "",
    val createTopic: String = "",
    val createIsPublic: Boolean = false,
    val createInvitees: List<String> = emptyList(),
    val isCreating: Boolean = false,
)

data class SpaceDetailUiState(
    val spaceId: String,
    val spaceName: String,
    val space: SpaceInfo? = null,
    val hierarchy: List<SpaceChildInfo> = emptyList(),
    val subspaces: List<SpaceChildInfo> = emptyList(),
    val rooms: List<SpaceChildInfo> = emptyList(),
    val nextBatch: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

data class SpaceSettingsUiState(
    val spaceId: String,
    val space: SpaceInfo? = null,
    val children: List<SpaceChildInfo> = emptyList(),
    val availableRooms: List<RoomSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,

    // Dialogs
    val showAddRoom: Boolean = false,
    val showInviteUser: Boolean = false,
    val inviteUserId: String = "",
)

data class ThreadUi(
    val roomId: String,
    val rootEventId: String,
    val messages: List<MessageEvent> = emptyList(),
    val nextBatch: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val reactionChips: Map<String, List<ReactionChip>> = emptyMap(),

    val editingEvent: MessageEvent? = null,
    val editInput: String = ""
)

enum class LastMessageType {
    Text,
    Image,
    Video,
    Audio,
    File,
    Sticker,
    Location,
    Poll,
    Call,
    Encrypted,
    Redacted,
    Unknown
}

/**
 * UI model, contains only what the UI needs (preview text, unread, etc).
 */
data class RoomListItemUi(
    val roomId: String,
    val name: String,
    val avatarUrl: String? = null,
    val isDm: Boolean = false,
    val isEncrypted: Boolean = false,

    val unreadCount: Int = 0,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,

    val lastMessageBody: String? = null,
    val lastMessageSender: String? = null,
    val lastMessageType: LastMessageType = LastMessageType.Text,
    val lastMessageTs: Long? = null,
)