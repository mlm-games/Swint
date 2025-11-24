package org.mlm.mages.ui.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.matrix.VerificationObserver
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.ui.VerificationRequestUi

class SecurityController(
    private val service: MatrixService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state

    private var inboxToken: ULong? = null

    init {
        refreshDevices()
        startVerificationInbox()
    }

    fun setSelectedTab(index: Int) {
        _state.update { it.copy(selectedTab = index) }
    }

    fun refreshDevices() {
        scope.launch {
            _state.update { it.copy(isLoadingDevices = true, error = null) }
            val devices = runCatching { service.listMyDevices() }.getOrElse {
                _state.update { s -> s.copy(isLoadingDevices = false, error = "Failed to load devices: ${it.message}") }
                return@launch
            }
            _state.update { it.copy(devices = devices, isLoadingDevices = false) }
        }
    }

    fun toggleTrust(deviceId: String, verified: Boolean) {
        scope.launch {
            val ok = service.setLocalTrust(deviceId, verified)
            if (!ok) {
                _state.update { it.copy(error = "Failed to update trust") }
            }
            refreshDevices()
        }
    }

    private fun startVerificationInbox() {
        inboxToken?.let { service.stopVerificationInbox(it) }
        inboxToken = service.startVerificationInbox(object : MatrixPort.VerificationInboxObserver {
            override fun onRequest(flowId: String, fromUser: String, fromDevice: String) {
                _state.update { st ->
                    val pending = st.pendingVerifications + VerificationRequestUi(
                        flowId,
                        fromUser,
                        fromDevice
                    )
                    st.copy(
                        pendingVerifications = pending,
                        // auto-focus on first req if none active
                        sasFlowId = st.sasFlowId ?: flowId,
                        sasPhase = st.sasPhase ?: SasPhase.Requested,
                        sasOtherUser = st.sasOtherUser ?: fromUser,
                        sasOtherDevice = st.sasOtherDevice ?: fromDevice,
                        sasError = null
                    )
                }
            }
            override fun onError(message: String) {
                _state.update { it.copy(error = "Verification inbox: $message") }
            }
        })
    }

    private fun commonObserver(): VerificationObserver = object : VerificationObserver {
        override fun onPhase(flowId: String, phase: SasPhase) {
            _state.update { it.copy(sasFlowId = flowId, sasPhase = phase, sasError = null) }
            if (phase == SasPhase.Done || phase == SasPhase.Cancelled) {
                // remove this flow and move next
                _state.update { st ->
                    val remaining = st.pendingVerifications.filterNot { it.flowId == flowId }
                    st.copy(
                        pendingVerifications = remaining,
                        sasFlowId = remaining.firstOrNull()?.flowId,
                        sasPhase = if (remaining.isNotEmpty()) SasPhase.Requested else null,
                        sasOtherUser = remaining.firstOrNull()?.fromUser,
                        sasOtherDevice = remaining.firstOrNull()?.fromDevice,
                        sasEmojis = if (remaining.isNotEmpty()) st.sasEmojis else emptyList(),
                        sasError = null
                    )
                }
                refreshDevices()
            }
        }

        override fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>) {
            _state.update { it.copy(sasFlowId = flowId, sasOtherUser = otherUser, sasOtherDevice = otherDevice, sasEmojis = emojis) }
        }

        override fun onError(flowId: String, message: String) {
            _state.update { it.copy(sasFlowId = flowId, sasError = message) }
        }
    }

    fun startSelfVerify(deviceId: String) {
        scope.launch {
            val flowId = service.startSelfSas(deviceId, commonObserver())
            if (flowId.isBlank()) {
                _state.update { it.copy(sasError = "Failed to start verification") }
            }
        }
    }

    fun startUserVerify(userId: String) {
        scope.launch {
            val flowId = service.startUserSas(userId, commonObserver())
            if (flowId.isBlank()) _state.update { it.copy(sasError = "Failed to start verification") }
        }
    }


    fun acceptSas() {
        val flowId = _state.value.sasFlowId ?: return
        scope.launch {
            val ok = service.acceptVerification(flowId, _state.value.sasOtherUser, commonObserver())
            if (!ok) _state.update { it.copy(sasError = "Accept failed") }
        }
    }

    fun confirmSas() {
        val flowId = _state.value.sasFlowId ?: return
        scope.launch {
            val ok = service.confirmVerification(flowId)
            if (!ok) _state.update { it.copy(sasError = "Confirm failed") }
        }
    }

    fun cancelSas() {
        val flowId = _state.value.sasFlowId ?: return
        scope.launch {
            val ok = service.cancelVerification(flowId)
            if (!ok) _state.update { it.copy(sasError = "Cancel failed") }
        }
    }

    fun openRecoveryDialog() {
        _state.update { it.copy(showRecoveryDialog = true, recoveryKeyInput = "") }
    }

    fun closeRecoveryDialog() {
        _state.update { it.copy(showRecoveryDialog = false, recoveryKeyInput = "") }
    }

    fun setRecoveryKey(v: String) {
        _state.update { it.copy(recoveryKeyInput = v) }
    }

    fun submitRecoveryKey() {
        val key = _state.value.recoveryKeyInput.trim()
        if (key.isBlank()) {
            _state.update { it.copy(sasError = "Enter a recovery key") }
            return
        }
        scope.launch {
            val ok = service.recoverWithKey(key)
            if (!ok) {
                _state.update { it.copy(sasError = "Recovery failed") }
            } else {
                _state.update { it.copy(showRecoveryDialog = false, recoveryKeyInput = "", sasError = null) }
            }
        }
    }
}