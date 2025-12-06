package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.*
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.ui.VerificationRequestUi

class SecurityViewModel(
    private val service: MatrixService
) : BaseViewModel<SecurityUiState>(SecurityUiState()) {

    // One-time events
    sealed class Event {
        data object LogoutSuccess : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var inboxToken: ULong? = null

    init {
        refreshDevices()
        refreshIgnored()
        loadPresence()
        startVerificationInbox()
    }

    //  Tab Selection 

    fun setSelectedTab(index: Int) {
        updateState { copy(selectedTab = index) }
    }

    //  Devices 

    fun refreshDevices() {
        launch(
            onError = { t ->
                updateState { copy(isLoadingDevices = false, error = "Failed to load devices: ${t.message}") }
            }
        ) {
            updateState { copy(isLoadingDevices = true, error = null) }
            val devices = service.listMyDevices()
            updateState { copy(devices = devices, isLoadingDevices = false) }
        }
    }

    //  Verification 

    private fun startVerificationInbox() {
        inboxToken?.let { service.stopVerificationInbox(it) }

        inboxToken = service.startVerificationInbox(object : MatrixPort.VerificationInboxObserver {
            override fun onRequest(flowId: String, fromUser: String, fromDevice: String) {
                updateState {
                    val pending = pendingVerifications + VerificationRequestUi(
                        flowId = flowId,
                        fromUser = fromUser,
                        fromDevice = fromDevice
                    )
                    copy(
                        pendingVerifications = pending,
                        sasFlowId = sasFlowId ?: flowId,
                        sasPhase = sasPhase ?: SasPhase.Requested,
                        sasOtherUser = sasOtherUser ?: fromUser,
                        sasOtherDevice = sasOtherDevice ?: fromDevice,
                        sasError = null,
                        sasIncoming = true
                    )
                }
            }

            override fun onError(message: String) {
                updateState { copy(error = "Verification inbox: $message") }
            }
        })
    }

    private fun commonObserver(): VerificationObserver = object : VerificationObserver {
        override fun onPhase(flowId: String, phase: SasPhase) {
            updateState { copy(sasFlowId = flowId, sasPhase = phase, sasError = null) }

            if (phase == SasPhase.Done || phase == SasPhase.Cancelled) {
                clearVerificationFlow(flowId)
                refreshDevices()
            }
        }

        override fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>) {
            updateState {
                copy(
                    sasFlowId = flowId,
                    sasOtherUser = otherUser,
                    sasOtherDevice = otherDevice,
                    sasEmojis = emojis
                )
            }
        }

        override fun onError(flowId: String, message: String) {
            updateState { copy(sasFlowId = flowId, sasError = message) }
        }
    }

    fun startSelfVerify(deviceId: String) {
        val myUserId = service.port.whoami() ?: return

        updateState { copy(sasOtherUser = myUserId, sasIncoming = false) }

        launch {
            val flowId = service.startSelfSas(deviceId, commonObserver())
            if (flowId.isBlank()) {
                updateState { copy(sasError = "Failed to start verification") }
            } else {
                updateState { copy(sasFlowId = flowId) }
            }
        }
    }

    fun startUserVerify(userId: String) {
        updateState { copy(sasOtherUser = userId, sasIncoming = false) }

        launch {
            val flowId = service.startUserSas(userId, commonObserver())
            if (flowId.isBlank()) {
                updateState { copy(sasError = "Failed to start verification") }
            } else {
                updateState { copy(sasFlowId = flowId) }
            }
        }
    }

    fun acceptSas() {
        val flowId = currentState.sasFlowId ?: return
        launch {
            val ok = service.acceptVerification(flowId, currentState.sasOtherUser, commonObserver())
            if (!ok) {
                updateState { copy(sasError = "Accept failed") }
            }
        }
    }

    fun confirmSas() {
        val flowId = currentState.sasFlowId ?: return
        launch {
            val ok = service.confirmVerification(flowId)
            if (!ok) {
                updateState { copy(sasError = "Confirm failed") }
            }
        }
    }

    fun cancelSas() {
        val flowId = currentState.sasFlowId ?: return
        val phase = currentState.sasPhase
        val targetUser = currentState.sasOtherUser

        launch {
            val ok = if (phase == SasPhase.Requested) {
                service.cancelVerificationRequest(flowId, targetUser)
            } else {
                service.cancelVerification(flowId)
            }

            if (!ok) {
                updateState { copy(sasError = "Cancel failed") }
            }
            else {
                clearVerificationFlow(flowId)
            }
        }
    }

    //  Recovery 

    fun openRecoveryDialog() {
        updateState { copy(showRecoveryDialog = true, recoveryKeyInput = "") }
    }

    fun closeRecoveryDialog() {
        updateState { copy(showRecoveryDialog = false, recoveryKeyInput = "") }
    }

    fun setRecoveryKey(value: String) {
        updateState { copy(recoveryKeyInput = value) }
    }

    fun submitRecoveryKey() {
        val key = currentState.recoveryKeyInput.trim()
        if (key.isBlank()) {
            updateState { copy(sasError = "Enter a recovery key") }
            return
        }

        launch {
            val ok = service.recoverWithKey(key)
            if (ok) {
                updateState { copy(showRecoveryDialog = false, recoveryKeyInput = "", sasError = null) }
                _events.send(Event.ShowSuccess("Recovery successful"))
            } else {
                updateState { copy(sasError = "Recovery failed") }
            }
        }
    }

    //  Privacy / Ignored Users 

    fun refreshIgnored() {
        launch {
            val list = runSafe { service.port.ignoredUsers() } ?: emptyList()
            updateState { copy(ignoredUsers = list) }
        }
    }

    fun unignoreUser(userId: String) {
        launch {
            val ok = service.port.unignoreUser(userId)
            if (ok) {
                refreshIgnored()
                _events.send(Event.ShowSuccess("User unignored"))
            } else {
                _events.send(Event.ShowError("Failed to unignore user"))
            }
        }
    }

    //  Presence 

    fun loadPresence() {
        launch {
            val myId = service.port.whoami() ?: return@launch
            val result = runSafe { service.port.getPresence(myId) }
            if (result != null) {
                updateState {
                    copy(
                        presence = presence.copy(
                            currentPresence = result.first,
                            statusMessage = result.second ?: ""
                        )
                    )
                }
            }
        }
    }

    fun setPresence(presence: Presence) {
        updateState { copy(presence = this.presence.copy(currentPresence = presence)) }
    }

    fun setStatusMessage(message: String) {
        updateState { copy(presence = presence.copy(statusMessage = message)) }
    }

    fun savePresence() {
        launch {
            updateState { copy(presence = presence.copy(isSaving = true)) }

            val ok = service.port.setPresence(
                currentState.presence.currentPresence,
                currentState.presence.statusMessage.ifBlank { null }
            )

            updateState { copy(presence = presence.copy(isSaving = false)) }

            if (ok) {
                _events.send(Event.ShowSuccess("Status updated"))
            } else {
                _events.send(Event.ShowError("Failed to update status"))
            }
        }
    }

    //  Logout 

    fun logout() {
        launch {
            val ok = service.logout()
            if (ok) {
                _events.send(Event.LogoutSuccess)
            } else {
                _events.send(Event.ShowError("Logout failed"))
            }
        }
    }

    private fun clearVerificationFlow(flowId: String) {
        updateState {
            val remaining = pendingVerifications.filterNot { it.flowId == flowId }
            copy(
                pendingVerifications = remaining,
                sasFlowId = remaining.firstOrNull()?.flowId,
                sasPhase = if (remaining.isNotEmpty()) SasPhase.Requested else null,
                sasOtherUser = remaining.firstOrNull()?.fromUser,
                sasOtherDevice = remaining.firstOrNull()?.fromDevice,
                sasEmojis = emptyList(),
                sasError = null,
                sasIncoming = remaining.isNotEmpty()
            )
        }
    }


    override fun onCleared() {
        super.onCleared()
        inboxToken?.let { service.stopVerificationInbox(it) }
    }
}