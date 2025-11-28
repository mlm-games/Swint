package org.mlm.mages.ui

import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.ReactionChip
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
)
data class RoomUiState(
    val roomId: String,
    val roomName: String,
    val myUserId: String? = null,
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
)
data class VerificationRequestUi(
    val flowId: String,
    val fromUser: String,
    val fromDevice: String,
    val timestamp: Long = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
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

    // Verification inbox and current SAS
    val pendingVerifications: List<VerificationRequestUi> = emptyList(),
    val sasFlowId: String? = null,
    val sasPhase: SasPhase? = null,
    val sasEmojis: List<String> = emptyList(),
    val sasOtherUser: String? = null,
    val sasOtherDevice: String? = null,
    val sasError: String? = null,
    val sasIncoming: Boolean = false,
)

data class SpacesUiState(
    val spaces: List<SpaceInfo> = emptyList(),
    val selectedSpace: SpaceInfo? = null,
    val hierarchy: List<SpaceChildInfo> = emptyList(),
    val hierarchyNextBatch: String? = null,
    val isLoading: Boolean = false,
    val isLoadingHierarchy: Boolean = false,
    val error: String? = null,
    val searchQuery: String = ""
)

data class CreateSpaceUiState(
    val name: String = "",
    val topic: String = "",
    val isPublic: Boolean = false,
    val invitees: List<String> = emptyList(),
    val isCreating: Boolean = false,
    val error: String? = null
)

data class SpaceSettingsUiState(
    val space: SpaceInfo? = null,
    val children: List<SpaceChildInfo> = emptyList(),
    val availableRooms: List<RoomSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)