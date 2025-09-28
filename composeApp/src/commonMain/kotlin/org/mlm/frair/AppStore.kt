package org.mlm.frair

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds

sealed class Screen {
    data object Login : Screen()
    data object Rooms : Screen()
    data class Room(val room: RoomSummary) : Screen()
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
    data object PaginateBack : Intent()
    data object PaginateForward : Intent()
    data class StartEdit(val event: MessageEvent) : Intent()
    data object CancelEdit : Intent()
    data object ConfirmEdit : Intent() // Send acts as confirm in edit mode
    data class React(val event: MessageEvent, val emoji: String) : Intent()
    data object Logout : Intent()
    data class MarkReadHere(val event: MessageEvent) : Intent() // Mark read to here
    data class DeleteMessage(val event: MessageEvent, val reason: String? = null) : Intent()
}

data class AppState(
    val screen: Screen = Screen.Login,
    val homeserver: String = "https://matrix.org",
    val user: String = "@example:matrix.org",
    val pass: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,

    // Rooms + unread
    val rooms: List<RoomSummary> = emptyList(),
    val unread: Map<String, Int> = emptyMap(), // roomId -> count

    // Timeline
    val events: List<MessageEvent> = emptyList(),
    val input: String = "",

    // Extras
    val drafts: Map<String, String> = emptyMap(), // roomId -> draft
    val replyingTo: MessageEvent? = null,
    val editing: MessageEvent? = null,
    val typing: Map<String, Set<String>> = emptyMap(), // roomId -> who

    val isPaginatingBack: Boolean = false,
    val isPaginatingForward: Boolean = false,
    val hitStart: Boolean = false,

)

class AppStore(
    private val matrix: MatrixService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var timelineJob: Job? = null
    private var typingJob: Job? = null
    private var lastTypingRoom: String? = null


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

            is Intent.PaginateBack -> paginateBack()
            Intent.PaginateForward -> paginateForward()

            Intent.Logout -> logout()


            is Intent.StartReply -> set { copy(replyingTo = intent.event) }
            Intent.CancelReply -> set { copy(replyingTo = null) }

            is Intent.StartEdit -> set { copy(editing = intent.event, input = intent.event.body) }
            Intent.CancelEdit -> set { copy(editing = null, input = "") }
            Intent.ConfirmEdit -> send()

            is Intent.React -> react(intent.event, intent.emoji)
            Intent.SyncNow -> reloadCurrentRoom()

            is Intent.MarkReadHere -> markReadHere(intent.event)
            is Intent.DeleteMessage -> deleteMessage(intent.event, intent.reason)

        }
    }

    init {
        // Try to show rooms if a session was restored by Rust Client::new()
        scope.launch {
            val hs = _state.value.homeserver
            matrix.init(hs)
            val rooms = runCatching { matrix.listRooms() }.getOrDefault(emptyList())
            if (rooms.isNotEmpty()) {
                matrix.startSync()
                set { copy(screen = Screen.Rooms, rooms = rooms, error = null) }
            } else {
                set { copy(screen = Screen.Login) }
            }
        }
    }

    private fun login() = launchBusy {
        val s = _state.value
        val ok = matrix.init(s.homeserver).let { matrix.login(s.user, s.pass) }
        if (!ok) return@launchBusy fail("Login failed")

        // After login, Rust did one sync_once; fetch rooms and start continuous sync
        val rooms = matrix.listRooms()
        matrix.startSync()
        set { copy(screen = Screen.Rooms, rooms = rooms, error = null) }
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

        // Start background client sync (noop if already running)
        // For MVP we just rely on observe + recent
        val recent = matrix.loadRecent(room.id, limit = 50)
        set { copy(events = recent) }

        startTimeline(room.id)
        markRead(room.id)
    }

    private fun reloadCurrentRoom() = scope.launch {
        val roomId = (state.value.screen as? Screen.Room)?.room?.id ?: return@launch
        val recent = matrix.loadRecent(roomId, limit = 60)
        set { copy(events = recent) }
    }

    private fun goBack() {
        stopTimeline()
        when (_state.value.screen) {
            is Screen.Room -> set { copy(screen = Screen.Rooms, events = emptyList(), input = "") }
            is Screen.Rooms -> set { copy(screen = Screen.Login) }
            is Screen.Login -> Unit
        }
        val rid = (state.value.screen as? Screen.Room)?.room?.id
        if (rid != null) scope.launch { matrix.stopTyping(rid) }
    }


    private fun onInputChanged(v: String) {
        val scr = _state.value.screen
        if (scr is Screen.Room) {
            set { copy(input = v, drafts = drafts + (scr.room.id to v)) }
        } else set { copy(input = v) }
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

    private fun react(event: MessageEvent, emoji: String) = scope.launch {
        val room = (state.value.screen as? Screen.Room)?.room ?: return@launch
        matrix.react(room.id, event.eventId, emoji)
    }

    private fun startTimeline(roomId: String) {
        stopTimeline()
        timelineJob = scope.launch {
            matrix.timeline(roomId).collect { ev ->
                set {
                    copy(events = (events + ev).distinctBy { it.eventId }.sortedBy { it.timestamp })
                }
            }
        }
    }


    private fun logout() = launchBusy {
        // stop typing in current room (if any)
        val rid = (state.value.screen as? Screen.Room)?.room?.id
        if (rid != null) matrix.stopTyping(rid)
        // server logout + clear session file
        val ok = matrix.port.logout()
        // reset to login screen regardless of ok (we dropped local session)
        set { AppState() }
    }

    private fun stopTimeline() {
        timelineJob?.cancel()
        timelineJob = null
    }

    private fun markRead(roomId: String) = scope.launch {
        matrix.markRead(roomId)
        set { copy(unread = unread - roomId) }
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

    private fun set(mutator: AppState.() -> AppState) {
        _state.value = _state.value.mutator()
    }

    private fun fail(msg: String) {
        set { copy(error = msg) }
    }

    private fun paginateBack() = scope.launch {
        val room = (state.value.screen as? Screen.Room)?.room ?: return@launch
        if (state.value.isPaginatingBack) return@launch
        set { copy(isPaginatingBack = true) }
        try {
            val hitStart = matrix.paginateBack(room.id, 40)
            // Optional refresh; your observer should deliver diffs anyway
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

    private fun markReadHere(event: MessageEvent) = scope.launch {
        matrix.markReadAt(event.roomId, event.eventId)
        set { copy(unread = unread - event.roomId) }
    }


    private fun deleteMessage(event: MessageEvent, reason: String?) = scope.launch {
        matrix.redact(event.roomId, event.eventId, reason)
        val recent = matrix.loadRecent(event.roomId, limit = state.value.events.size)
        set { copy(events = recent) }
    }
}