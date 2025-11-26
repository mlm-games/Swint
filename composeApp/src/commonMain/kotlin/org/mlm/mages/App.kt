package org.mlm.mages

import androidx.compose.runtime.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.nav.*
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberOpenBrowser
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.controller.*
import org.mlm.mages.ui.screens.*
import org.mlm.mages.ui.theme.MainTheme

@Composable
fun App(
    dataStore: DataStore<Preferences>,
    service: MatrixService,
    deepLinks: Flow<String>? = null
) {
    MainTheme {
        val nav = rememberNavigator(initial = Route.Login)
        var showCreateRoom by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        BindDeepLinks(nav, deepLinks)

        BindLifecycle(service)

        LaunchedEffect(service) {
                service.startSupervisedSync(object : MatrixPort.SyncObserver {
                    override fun onState(status: MatrixPort.SyncStatus) { /* no-op */ }
                }) // For desktop            
        }

        val openUrl = rememberOpenBrowser()

        when (val r = nav.current) {
            Route.Login -> {
                val controller = remember {
                    LoginController(
                        service = service,
                        dataStore = dataStore,
                        onLoggedIn = { nav.replace(Route.Rooms) }
                    )
                }
                val ui by controller.state.collectAsState()
                LoginScreen(
                    state = ui,
                    onChangeHomeserver = controller::setHomeserver,
                    onChangeUser = controller::setUser,
                    onChangePass = controller::setPass,
                    onSubmit = controller::submit,
                    onSso = { controller.startSso(openUrl) }
                )
            }
            Route.Rooms -> {
                val controller = remember {
                    RoomsController(
                        service,
                        dataStore = dataStore,
                        onOpenRoom = { room -> nav.push(Route.Room(room.id, room.name)) }
                    )
                }
                val ui by controller.state.collectAsState()
                RoomsScreen(
                    state = ui,
                    onRefresh = controller::refreshRooms,
                    onSearch = controller::setSearchQuery,
                    onOpen = { controller.open(it) },
                    onOpenSecurity = { nav.push(Route.Security) },
                    onToggleUnreadOnly = {controller.toggleUnreadOnly()},
                    onOpenDiscover = { nav.push(Route.Discover) },
                    onOpenInvites = {nav.push(Route.Invites)},
                    onOpenCreateRoom = { showCreateRoom = true },
                )
                if (showCreateRoom) {
                    CreateRoomSheet(
                        onCreate = { name, topic, invitees ->
                            scope.launch {
                                val roomId = service.port.createRoom(name, topic, invitees) // suspend
                                if (roomId != null) {
                                    showCreateRoom = false
                                    nav.push(Route.Room(roomId, name ?: roomId))
                                } else {
                                // TODO: Snackbar
                                }
                            }
                                   },
                        onDismiss = { showCreateRoom = false }
                    )
                }
            }
            is Route.Room -> {
                val controller = remember(r.roomId) { RoomController(service, dataStore, r.roomId, r.name) }
                val ui by controller.state.collectAsState()
                val openExternal = rememberFileOpener()
                RoomScreen(
                    state = ui,
                    onBack = { nav.pop() },
                    onSetInput = controller::setInput,
                    onSend = controller::send,
                    onReply = controller::startReply,
                    onCancelReply = controller::cancelReply,
                    onEdit = controller::startEdit,
                    onCancelEdit = controller::cancelEdit,
                    onConfirmEdit = controller::confirmEdit,
                    onReact = controller::react,
                    onPaginateBack = controller::paginateBack,
                    onMarkReadHere = controller::markReadHere,
                    onSendAttachment = controller::sendAttachment,
                    onCancelUpload = controller::cancelAttachmentUpload,
                    onDelete = controller::delete,
                    onRetry = controller::retry,
                    onOpenAttachment = { event ->
                        controller.openAttachment(event) { path, mime ->
                            openExternal(path, mime) // platform-resolved
                        }
                    },
                    onOpenInfo = { nav.push(Route.RoomInfo(r.roomId)) },
                    onOpenThread = { nav.push(Route.Thread(r.roomId, it.eventId, ui.roomName)) }
                )
            }
            Route.Security -> {
                var selectedTab by remember { mutableIntStateOf(0) }
                val controller = remember {
                    SecurityController(
                        service,
                    )
                }
                val ui by controller.state.collectAsState()
                val scope = rememberCoroutineScope()
                SecurityScreen(
                    state = ui,
                    onBack = { nav.pop() },
                    onRefreshDevices = controller::refreshDevices,
                    onToggleTrust = controller::toggleTrust,
                    onStartSelfVerify = controller::startSelfVerify,
                    onStartUserVerify = controller::startUserVerify,
                    onAcceptSas = controller::acceptSas,
                    onConfirmSas = controller::confirmSas,
                    onCancelSas = controller::cancelSas,
                    onOpenRecovery = controller::openRecoveryDialog,
                    onCloseRecovery = controller::closeRecoveryDialog,
                    onChangeRecoveryKey = controller::setRecoveryKey,
                    onSubmitRecoveryKey = controller::submitRecoveryKey,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    onLogout = {
                        scope.launch {
                            val ok = service.logout()
                            service.port.close()
                            if (ok) { nav.replace(Route.Login)}
                        }
                    }
                )
            }
            Route.Discover -> {
                val controller = remember { DiscoverController(service) }
                val ui by controller.state.collectAsState()
                DiscoverScreen(
                    state = ui,
                    onQuery = controller::setQuery,
                    onClose = { nav.pop() },
                    onOpenUser = { u ->
                        val rid = controller.ensureDm(u.userId)
                        if (rid != null) nav.push(Route.Room(rid, u.displayName ?: u.userId))
                    },
                    onOpenRoom = { room ->
                        val rid = controller.joinOrOpen(room.alias ?: room.roomId)
                        if (rid != null) nav.push(Route.Room(rid, room.name ?: room.alias ?: room.roomId))
                    }
                )
            }
            is Route.Invites -> {
                val controller = remember { InvitesController(service.port) }
                val ui by controller.state.collectAsState()
                InvitesScreen(
                    invites = ui.invites,
                    busy = ui.busy,
                    error = ui.error,
                    onBack = { nav.pop() },
                    onRefresh = controller::refresh,
                    onAccept = { controller.accept(it) },
                    onDecline = { controller.decline(it) },
                    onOpenRoom = { roomId, title -> nav.push(Route.Room(roomId, title)) }
                )
            }

            is Route.RoomInfo -> {
                val controller = remember(r.roomId) { RoomInfoController(service, r.roomId) }
                val state by controller.state.collectAsState()

                RoomInfoScreen(
                    state = state,
                    onBack = { nav.pop() },
                    onRefresh = controller::refresh,
                    onNameChange = controller::updateName,
                    onTopicChange = controller::updateTopic,
                    onSaveName = { controller.saveName() },
                    onSaveTopic = { controller.saveTopic() },
                    onLeave = { controller.leave() },
                    onLeaveSuccess = {
                        // Pop back to rooms list
                        nav.popUntil { it is Route.Rooms }
                    }
                )
            }
            is Route.Thread -> {
                val controller = remember(r.roomId) { ThreadController(service, r.roomId, r.rootEventId) }
                val ui by controller.state.collectAsState()

                ThreadScreen(
                    state = ui,
                    myUserId = service.port.whoami(),
                    reactionChips = { eid -> runBlocking { service.port.reactions(r.roomId, eid) } }, // TODO: rememberCoroutineScope and state
                    onReact = controller::react,
                    onBack = { nav.pop() },
                    onLoadMore = controller::loadMore,
                    onSendThread = { text, replyToId ->
                        val ok = service.port.sendThreadText(r.roomId, r.rootEventId, text, replyToId)
                        if (ok) controller.refresh()
                        ok
                    }
                )
            }
        }
    }
}

@Composable
private fun BindLifecycle(service: MatrixService) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { service.port.enterForeground() }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { service.port.enterBackground() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
}
