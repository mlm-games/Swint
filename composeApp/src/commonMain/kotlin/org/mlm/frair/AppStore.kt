package org.mlm.frair

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mlm.frair.matrix.MatrixPort
import org.mlm.frair.matrix.VerificationObserver

sealed class Screen {
    data object Login : Screen()
    data object Rooms : Screen()
    data class Room(val room: RoomSummary) : Screen()
    data object Security : Screen()
}

sealed class Intent {
    // login
    data class ChangeHomeserver(val v: String) : Intent()
    data class ChangeUser(val v: String) : Intent()
    data class ChangePass(val v: String) : Intent()
    data object SubmitLogin : Intent()

    // rooms
    data object RefreshRooms : Intent()
    data class OpenRoom(val room: RoomSummary) : Intent()
    data object Back : Intent()
    data class MarkRoomRead(val roomId: String) : Intent()

    // timeline
    data class ChangeInput(val v: String) : Intent()
    data object Send : Intent()
    data object SyncNow : Intent()
    data class StartReply(val event: MessageEvent) : Intent()
    data object CancelReply : Intent()
    data class StartEdit(val event: MessageEvent) : Intent()
    data object CancelEdit : Intent()
    data object ConfirmEdit : Intent()
    data class React(val event: MessageEvent, val emoji: String) : Intent()

    // pagination
    data object PaginateBack : Intent()
    data object PaginateForward : Intent()

    // security
    data object OpenSecurity : Intent()
    data object CloseSecurity : Intent()
    data object RefreshSecurity : Intent()
    data class ToggleTrust(val deviceId: String, val verified: Boolean) : Intent()
    data class StartSelfVerify(val deviceId: String) : Intent()
    data object AcceptSas : Intent()
    data object ConfirmSas : Intent()
    data object CancelSas : Intent()
    data class MarkReadHere(val event: MessageEvent) : Intent()
    data class DeleteMessage(val event: MessageEvent, val reason: String? = null) : Intent()

    // logout
    data object Logout : Intent()

    // security - recovery
    data object OpenRecoveryDialog : Intent()
    data object CloseRecoveryDialog : Intent()
    data class SetRecoveryKey(val v: String) : Intent()
    data object SubmitRecoveryKey : Intent()
}

data class AppState(
    val screen: Screen = Screen.Login,
    val homeserver: String = "https://matrix.org",
    val user: String = "@user:matrix.org",
    val pass: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,

    // Rooms + unread
    val rooms: List<RoomSummary> = emptyList(),
    val unread: Map<String, Int> = emptyMap(),

    // Timeline
    val events: List<MessageEvent> = emptyList(),
    val input: String = "",

    // Extras
    val drafts: Map<String, String> = emptyMap(),
    val replyingTo: MessageEvent? = null,
    val editing: MessageEvent? = null,
    val typing: Map<String, Set<String>> = emptyMap(),

    // Pagination flags
    val isPaginatingBack: Boolean = false,
    val isPaginatingForward: Boolean = false,
    val hitStart: Boolean = false,

    // Security screen
    val devices: List<org.mlm.frair.matrix.DeviceSummary> = emptyList(),
    val isLoadingDevices: Boolean = false,

    // SAS modal
    val sasFlowId: String? = null,
    val sasPhase: org.mlm.frair.matrix.SasPhase? = null,
    val sasEmojis: List<String> = emptyList(),
    val sasOtherUser: String? = null,
    val sasOtherDevice: String? = null,
    val sasError: String? = null,

    val showRecoveryDialog: Boolean = false,
    val recoveryKeyInput: String = "",
)

class AppStore(
    val matrix: MatrixService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var timelineJob: Job? = null
    private var typingJob: Job? = null
    private var typingObsJob: Job? = null
    private var lastTypingRoom: String? = null

    init { bootstrap() }

    private fun bootstrap() = scope.launch {
        if (matrix.isLoggedIn()) {
            matrixPortStartSupervised()
            matrix.startSync()
            val rooms = runCatching { matrix.listRooms() }.getOrDefault(emptyList())
            set { copy(screen = Screen.Rooms, rooms = rooms, error = null) }
        }
    }


    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.ChangeHomeserver -> set { copy(homeserver = intent.v) }
            is Intent.ChangeUser -> set { copy(user = intent.v) }
            is Intent.ChangePass -> set { copy(pass = intent.v) }
            Intent.SubmitLogin -> login()

            Intent.RefreshRooms -> refreshRooms()
            is Intent.OpenRoom -> openRoom(intent.room)
            Intent.Back -> goBack()
            is Intent.MarkRoomRead -> markRead(intent.roomId)

            is Intent.ChangeInput -> onInputChanged(intent.v)
            Intent.Send -> send()

            is Intent.StartReply -> set { copy(replyingTo = intent.event) }
            Intent.CancelReply -> set { copy(replyingTo = null) }

            is Intent.StartEdit -> set { copy(editing = intent.event, input = intent.event.body) }
            Intent.CancelEdit -> set { copy(editing = null, input = "") }
            Intent.ConfirmEdit -> send()

            is Intent.React -> react(intent.event, intent.emoji)
            Intent.SyncNow -> reloadCurrentRoom()

            Intent.PaginateBack -> paginateBack()
            Intent.PaginateForward -> paginateForward()

            Intent.OpenSecurity -> openSecurity()
            Intent.CloseSecurity -> set { copy(screen = Screen.Rooms, sasFlowId = null, sasPhase = null, sasEmojis = emptyList(), sasError = null) }
            Intent.RefreshSecurity -> refreshDevices()
            is Intent.ToggleTrust -> toggleTrust(intent.deviceId, intent.verified)
            is Intent.StartSelfVerify -> startSelfVerify(intent.deviceId)
            Intent.AcceptSas -> acceptSas()
            Intent.ConfirmSas -> confirmSas()
            Intent.CancelSas -> cancelSas()

            is Intent.MarkReadHere -> markReadHere(intent.event)
            is Intent.DeleteMessage -> deleteMessage(intent.event, intent.reason)

            Intent.Logout -> logout()

            Intent.OpenRecoveryDialog -> set { copy(showRecoveryDialog = true, recoveryKeyInput = "") }
            Intent.CloseRecoveryDialog -> set { copy(showRecoveryDialog = false, recoveryKeyInput = "") }
            is Intent.SetRecoveryKey -> set { copy(recoveryKeyInput = intent.v) }
            Intent.SubmitRecoveryKey -> recoverNow()
        }
    }

    private fun login() = launchBusy {
        val s = _state.value
        try {
            matrix.init(s.homeserver)
            val res = matrix.login(s.user, s.pass)
            res.getOrThrow() // throws with the SDK's message
        } catch (t: Throwable) {
            val msg = t.message?.let(::humanizeLoginError)
                ?: "Could not sign in. Check your homeserver, username and password."
            return@launchBusy fail(msg)
        }

        matrix.startSync()

        set { copy(error = null) }
        val rooms = matrix.listRooms()
        set { copy(screen = Screen.Rooms, rooms = rooms) }
    }

    private fun humanizeLoginError(raw: String): String {
        val s = raw.lowercase()
        return when {
            "forbidden" in s || "m_forbidden" in s -> "Invalid username or password."
            "timeout" in s -> "Network timeout — please check your connection and try again."
            "dns" in s || "resolve" in s -> "Could not reach homeserver — is the URL correct?"
            "ssl" in s || "certificate" in s -> "TLS/SSL error contacting homeserver."
            else -> raw
        }
    }

    private fun refreshRooms() = launchBusy {
        val rooms = matrix.listRooms()
        set { copy(rooms = rooms) }
    }

    private fun openRoom(room: RoomSummary) = launchBusy {
        val draft = _state.value.drafts[room.id].orEmpty()
        set {
            copy(
                screen = Screen.Room(room),
                events = emptyList(),
                input = draft,
                replyingTo = null,
                editing = null
            )
        }

        val recent = matrix.loadRecent(room.id, limit = 50)
        set { copy(events = recent) }

        startTimeline(room.id)
        startTypingObserver(room.id)
        markRead(room.id)
    }

    private fun reloadCurrentRoom() = scope.launch {
        val roomId = (state.value.screen as? Screen.Room)?.room?.id ?: return@launch
        val recent = matrix.loadRecent(roomId, limit = 60)
        set { copy(events = recent) }
    }

    private fun goBack() {
        val rid = (state.value.screen as? Screen.Room)?.room?.id
        stopTimeline()
        typingObsJob?.cancel()
        typingObsJob = null
        when (_state.value.screen) {
            is Screen.Room -> set { copy(screen = Screen.Rooms, events = emptyList(), input = "") }
            is Screen.Rooms -> set { copy(screen = Screen.Login) }
            is Screen.Login -> Unit
            is Screen.Security -> set { copy(screen = Screen.Rooms) }
        }
    }

    private fun onInputChanged(v: String) {
        val scr = _state.value.screen
        if (scr is Screen.Room) {
            set { copy(input = v, drafts = drafts + (scr.room.id to v)) }
            handleTyping(scr.room.id, v)
        } else {
            set { copy(input = v) }
        }
    }

    private fun handleTyping(roomId: String, text: String) {
        typingJob?.cancel()
        if (text.isBlank()) {
            lastTypingRoom = null
            return
        }
        lastTypingRoom = roomId
        typingJob = scope.launch {
            delay(800)
        }
    }

    private fun send() = launchBusy {
        val st = _state.value
        val room = (st.screen as? Screen.Room)?.room ?: return@launchBusy
        val text = st.input.trim()
        if (text.isEmpty()) return@launchBusy

        val ok = when {
            st.editing != null -> matrix.edit(room.id, st.editing.eventId, text)
            st.replyingTo != null -> matrix.reply(room.id, st.replyingTo.eventId, text)
            else -> matrix.sendMessage(room.id, text)
        }

        if (!ok) return@launchBusy fail("Send failed")

        set {
            copy(
                input = "",
                drafts = drafts + (room.id to ""),
                replyingTo = null,
                editing = null
            )
        }
        val recent = matrix.loadRecent(room.id, limit = 60)
        set { copy(events = recent) }
    }

    private fun react(event: MessageEvent, emoji: String) {
        val room = (state.value.screen as? Screen.Room)?.room ?: return
        scope.launch { matrix.react(room.id, event.eventId, emoji) }
    }

    private fun startTimeline(roomId: String) {
        stopTimeline()
        timelineJob = scope.launch {
            matrix.timeline(roomId).collect { ev ->
                set {
                    copy(
                        events = (events + ev)
                            .distinctBy { it.eventId }
                            .sortedBy { it.timestamp }
                    )
                }
            }
        }
    }

    private fun stopTimeline() {
        timelineJob?.cancel()
        timelineJob = null
    }

    private fun startTypingObserver(roomId: String) {
        typingObsJob?.cancel()
        typingObsJob = scope.launch {
            matrix.observeTyping(roomId) { names ->
                set { copy(typing = typing + (roomId to names.toSet())) }
            }
        }
    }

    private fun matrixPortStartSupervised() {
        matrix.port.startSupervisedSync(object : MatrixPort.SyncObserver {
            override fun onState(status: MatrixPort.SyncStatus) {
                if (status.phase == MatrixPort.SyncPhase.Error) {
                    set { copy(error = status.message ?: "Sync error") }
                }
            }
        })
    }

    private fun markRead(roomId: String) {
        scope.launch { matrix.markRead(roomId) }
        set { copy(unread = unread - roomId) }
    }

    private fun markReadHere(event: MessageEvent) = scope.launch {
        matrix.markReadAt(event.roomId, event.eventId)
        set { copy(unread = unread - event.roomId) }
    }

    private fun deleteMessage(event: MessageEvent, reason: String?) = scope.launch {
        matrix.redact(event.roomId, event.eventId, reason)
        val recent = matrix.loadRecent(event.roomId, limit = state.value.events.size)
        set { copy(events = recent) }
    }

    private fun paginateBack() = scope.launch {
        val room = (state.value.screen as? Screen.Room)?.room ?: return@launch
        if (state.value.isPaginatingBack) return@launch
        set { copy(isPaginatingBack = true) }
        try {
            val hitStart = matrix.paginateBack(room.id, 40)
            val recent = matrix.loadRecent(room.id, limit = state.value.events.size + 40)
            set { copy(events = recent, hitStart = hitStart) }
        } finally {
            set { copy(isPaginatingBack = false) }
        }
    }

    private fun paginateForward() = scope.launch {
        val room = (state.value.screen as? Screen.Room)?.room ?: return@launch
        if (state.value.isPaginatingForward) return@launch
        set { copy(isPaginatingForward = true) }
        try {
            val _hitEnd = matrix.paginateForward(room.id, 40)
            val recent = matrix.loadRecent(room.id, limit = state.value.events.size + 40)
            set { copy(events = recent) }
        } finally {
            set { copy(isPaginatingForward = false) }
        }
    }

    private fun openSecurity() = launchBusy {
        set { copy(screen = Screen.Security) }
        refreshDevices()
    }

    private fun refreshDevices() = scope.launch {
        set { copy(isLoadingDevices = true) }
        val devs = runCatching { matrix.listMyDevices() }.getOrDefault(emptyList())
        set { copy(devices = devs, isLoadingDevices = false) }
    }

    private fun toggleTrust(deviceId: String, verified: Boolean) = scope.launch {
        val ok = matrix.setLocalTrust(deviceId, verified)
        if (ok) refreshDevices()
    }

    private fun startSelfVerify(deviceId: String) = scope.launch {
        val obs = object : VerificationObserver {
            override fun onPhase(flowId: String, phase: org.mlm.frair.matrix.SasPhase) {
                set { copy(sasFlowId = flowId, sasPhase = phase, sasError = null) }
            }
            override fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>) {
                set { copy(sasFlowId = flowId, sasOtherUser = otherUser, sasOtherDevice = otherDevice, sasEmojis = emojis) }
            }
            override fun onError(flowId: String, message: String) {
                set { copy(sasFlowId = flowId, sasError = message) }
            }
        }
        val flowId = matrix.startSelfSas(deviceId, obs)
        if (flowId.isBlank()) set { copy(sasError = "Failed to start verification") }
    }

    private fun acceptSas() = scope.launch {
        val id = state.value.sasFlowId ?: return@launch
        if (!matrix.acceptVerification(id)) set { copy(sasError = "Accept failed") }
    }

    private fun confirmSas() = scope.launch {
        val id = state.value.sasFlowId ?: return@launch
        if (!matrix.confirmVerification(id)) set { copy(sasError = "Confirm failed") }
    }

    private fun cancelSas() = scope.launch {
        val id = state.value.sasFlowId ?: return@launch
        if (!matrix.cancelVerification(id)) set { copy(sasError = "Cancel failed") }
    }

    private fun logout() = launchBusy {
        val rid = (state.value.screen as? Screen.Room)?.room?.id
        val _ok = matrix.logout()
        set { AppState() }
    }

    private fun recoverNow() = launchBusy {
        val key = state.value.recoveryKeyInput.trim()
        if (key.isEmpty()) return@launchBusy fail("Enter a recovery key")
        val ok = matrix.recoverWithKey(key)
        if (!ok) return@launchBusy fail("Recovery failed")
        set { copy(showRecoveryDialog = false, recoveryKeyInput = "", error = "Recovery successful") }
    }

    private fun set(mutator: AppState.() -> AppState) {
        _state.value = _state.value.mutator()
    }

    private fun fail(msg: String) {
        set { copy(error = msg) }
    }

    private fun launchBusy(block: suspend () -> Unit) = scope.launch {
        try {
            set { copy(isBusy = true, error = null) }
            block()
        } catch (t: Throwable) {
            fail(t.message ?: "Unexpected error")
        } finally {
            set { copy(isBusy = false) }
        }
    }
}