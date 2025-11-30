package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomProfile
import org.mlm.mages.matrix.RoomUpgradeInfo

data class RoomInfoUiState(
    val profile: RoomProfile? = null,
    val members: List<MemberSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editedName: String = "",
    val editedTopic: String = "",
    val isSaving: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,

    val directoryVisibility: RoomDirectoryVisibility? = null,
    val isAdminBusy: Boolean = false,
    val successor: RoomUpgradeInfo? = null,
    val predecessor: RoomPredecessorInfo? = null,

    val notificationMode: RoomNotificationMode? = null,
    val isLoadingNotificationMode: Boolean = false,
)

class RoomInfoViewModel(
    private val service: MatrixService,
    private val roomId: String
) : BaseViewModel<RoomInfoUiState>(RoomInfoUiState()) {

    sealed class Event {
        object LeaveSuccess : Event()
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        launch(onError = {
            updateState { copy(isLoading = false, error = it.message ?: "Failed to load room info") }
        }) {
            updateState { copy(isLoading = true, error = null) }

            val profile = service.port.roomProfile(roomId)
            val members = service.port.listMembers(roomId)
            val tags = service.port.roomTags(roomId)

            val sorted = members.sortedWith(
                compareByDescending<MemberSummary> { it.isMe }
                    .thenBy { it.displayName ?: it.userId }
            )

            val vis = runSafe { service.port.roomDirectoryVisibility(roomId) }
            val successor = runSafe { service.port.roomSuccessor(roomId) }
            val predecessor = runSafe { service.port.roomPredecessor(roomId) }

            updateState {
                copy(
                    profile = profile,
                    members = sorted,
                    editedName = profile?.name ?: "",
                    editedTopic = profile?.topic ?: "",
                    isLoading = false,
                    isFavourite = tags?.first ?: false,
                    isLowPriority = tags?.second ?: false,
                    directoryVisibility = vis,
                    successor = successor,
                    predecessor = predecessor,
                    error = if (profile == null) "Failed to load room info" else null
                )
            }
        }
    }

    fun updateName(name: String) {
        updateState { copy(editedName = name) }
    }

    fun updateTopic(topic: String) {
        updateState { copy(editedTopic = topic) }
    }

    suspend fun saveName(): Boolean {
        val name = currentState.editedName.trim()
        if (name.isBlank()) return false

        updateState { copy(isSaving = true) }
        val success = runSafe { service.port.setRoomName(roomId, name) } ?: false
        updateState { copy(isSaving = false) }

        if (success) {
            refresh()
        } else {
            _events.send(Event.ShowError("Failed to update name"))
        }
        return success
    }

    suspend fun saveTopic(): Boolean {
        val topic = currentState.editedTopic.trim()
        updateState { copy(isSaving = true) }
        val success = runSafe { service.port.setRoomTopic(roomId, topic) } ?: false
        updateState { copy(isSaving = false) }

        if (success) {
            refresh()
        } else {
            _events.send(Event.ShowError("Failed to update topic"))
        }
        return success
    }

    suspend fun toggleFavourite(): Boolean {
        val current = currentState.isFavourite
        updateState { copy(isSaving = true) }
        val success = runSafe { service.port.setRoomFavourite(roomId, !current) } ?: false
        updateState { copy(isSaving = false) }

        if (success) {
            updateState { copy(isFavourite = !current) }
            if (!current && currentState.isLowPriority) {
                runSafe { service.port.setRoomLowPriority(roomId, false) }
                updateState { copy(isLowPriority = false) }
            }
        } else {
            _events.send(Event.ShowError("Failed to update favourite"))
        }
        return success
    }

    suspend fun toggleLowPriority(): Boolean {
        val current = currentState.isLowPriority
        updateState { copy(isSaving = true) }
        val success = runSafe { service.port.setRoomLowPriority(roomId, !current) } ?: false
        updateState { copy(isSaving = false) }

        if (success) {
            updateState { copy(isLowPriority = !current) }
            if (!current && currentState.isFavourite) {
                runSafe { service.port.setRoomFavourite(roomId, false) }
                updateState { copy(isFavourite = false) }
            }
        } else {
            _events.send(Event.ShowError("Failed to update priority"))
        }
        return success
    }

    suspend fun setDirectoryVisibility(v: RoomDirectoryVisibility): Boolean {
        updateState { copy(isAdminBusy = true) }
        val ok = runSafe { service.port.setRoomDirectoryVisibility(roomId, v) } ?: false
        updateState { copy(isAdminBusy = false) }
        if (ok) refresh() else _events.send(Event.ShowError("Failed to update visibility"))
        return ok
    }

    suspend fun enableEncryption(): Boolean {
        updateState { copy(isAdminBusy = true) }
        val ok = runSafe { service.port.enableRoomEncryption(roomId) } ?: false
        updateState { copy(isAdminBusy = false) }
        if (ok) refresh() else _events.send(Event.ShowError("Failed to enable encryption"))
        return ok
    }

    suspend fun leave(): Boolean {
        val ok = runSafe { service.port.leaveRoom(roomId) } ?: false
        if (ok) {
            _events.send(Event.LeaveSuccess)
        } else {
            _events.send(Event.ShowError("Failed to leave room"))
        }
        return ok
    }

    fun clearError() {
        updateState { copy(error = null) }
    }

    fun openRoom(roomId: String) {
        launch {
            val profile = runSafe { service.port.roomProfile(roomId) }
            _events.send(Event.OpenRoom(roomId, profile?.name ?: roomId))
        }
    }
}