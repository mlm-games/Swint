package org.mlm.mages

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.nav.*
import org.mlm.mages.platform.BackHandler
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberOpenBrowser
import org.mlm.mages.ui.base.rememberSnackbarController
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.controller.*
import org.mlm.mages.ui.screens.*
import org.mlm.mages.ui.theme.MainTheme

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App(
    dataStore: DataStore<Preferences>,
    service: MatrixService,
    deepLinks: Flow<String>? = null
) {
    MainTheme {
        val nav = rememberNavigator(initial = Route.Login)

        BackHandler(enabled = nav.current != Route.Login && nav.current != Route.Rooms) {
            nav.pop()
        }

        val snackbar = rememberSnackbarController()
        var showCreateRoom by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        BindDeepLinks(nav, deepLinks)
        BindLifecycle(service)

        LaunchedEffect(service) {
            service.startSupervisedSync(object : MatrixPort.SyncObserver {
                override fun onState(status: MatrixPort.SyncStatus) { /* no-op */ }
            })
        }

        val roomsController = remember(service, dataStore) {
            RoomsController(
                service = service,
                dataStore = dataStore,
                onOpenRoom = { room -> nav.push(Route.Room(room.id, room.name)) }
            )
        }

        val openUrl = rememberOpenBrowser()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar.hostState) }
        ) { _ ->
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = {
                    when {
                        initialState is Route.Login || targetState is Route.Login ->
                            ScreenTransitions.fadeTransition()
                        nav.isForwardNavigation() ->
                            ScreenTransitions.forwardTransition()
                        else ->
                            ScreenTransitions.backwardTransition()
                    }
                },
                label = "screen_transition"
            ) { route ->
                when (route) {
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
                        val ui by roomsController.state.collectAsState()
                        RoomsScreen(
                            state = ui,
                            onRefresh = roomsController::refreshRooms,
                            onSearch = roomsController::setSearchQuery,
                            onOpen = { roomsController.open(it) },
                            onOpenSecurity = { nav.push(Route.Security) },
                            onToggleUnreadOnly = { roomsController.toggleUnreadOnly() },
                            onOpenDiscover = { nav.push(Route.Discover) },
                            onOpenInvites = { nav.push(Route.Invites) },
                            onOpenCreateRoom = { showCreateRoom = true },
                            onOpenSpaces = { nav.push(Route.Spaces) },
                        )
                        if (showCreateRoom) {
                            CreateRoomSheet(
                                onCreate = { name, topic, invitees ->
                                    scope.launch {
                                        val roomId = service.port.createRoom(name, topic, invitees)
                                        if (roomId != null) {
                                            showCreateRoom = false
                                            nav.push(Route.Room(roomId, name ?: roomId))
                                        } else {
                                            snackbar.showError("Failed to create room")
                                        }
                                    }
                                },
                                onDismiss = { showCreateRoom = false }
                            )
                        }
                    }

                    is Route.Room -> {
                        val controller = remember(route.roomId) {
                            RoomController(service, dataStore, route.roomId, route.name)
                        }
                        DisposableEffect(route.roomId) {
                            onDispose {
                                controller.onCleared()
                            }
                        }

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
                                    openExternal(path, mime)
                                }
                            },
                            onOpenInfo = { nav.push(Route.RoomInfo(route.roomId)) },
                            onOpenThread = { nav.push(Route.Thread(route.roomId, it.eventId, ui.roomName)) }
                        )
                    }

                    Route.Security -> {
                        var selectedTab by remember { mutableIntStateOf(0) }
                        val controller = remember { SecurityController(service) }
                        val ui by controller.state.collectAsState()
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
                                    if (ok) { nav.replace(Route.Login) }
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

                    Route.Invites -> {
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
                        val controller = remember(route.roomId) { RoomInfoController(service, route.roomId) }
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
                            onLeaveSuccess = { nav.popUntil { it is Route.Rooms } }
                        )
                    }

                    is Route.Thread -> {
                        val controller = remember(route.roomId, route.rootEventId) {
                            ThreadController(service, route.roomId, route.rootEventId)
                        }
                        val ui by controller.state.collectAsState()

                        ThreadScreen(
                            state = ui,
                            myUserId = service.port.whoami(),
                            onReact = controller::react,
                            onBack = { nav.pop() },
                            onLoadMore = controller::loadMore,
                            onSendThread = { text, replyToId ->
                                val ok = service.port.sendThreadText(route.roomId, route.rootEventId, text, replyToId)
                                if (ok) controller.refresh()
                                ok
                            },
                            onEdit = { event -> controller.startEdit(event) },
                            onDelete = { event -> controller.delete(event) },
                            onRetry = { event -> controller.retry(event) },
                            snackbarController = snackbar
                        )
                    }
                    Route.Spaces -> {
                        val controller = remember {
                            SpacesController(
                                service = service,
                                onOpenRoom = { roomId, name -> nav.push(Route.Room(roomId, name)) },
                                onOpenSpace = { space -> nav.push(Route.SpaceDetail(space.roomId, space.name)) }
                            )
                        }
                        val ui by controller.state.collectAsState()
                        var showCreateSpace by remember { mutableStateOf(false) }

                        if (showCreateSpace) {
                            val createController = remember {
                                CreateSpaceController(
                                    service = service,
                                    onCreated = { spaceId ->
                                        showCreateSpace = false
                                        controller.refresh()
                                    }
                                )
                            }
                            val createState by createController.state.collectAsState()

                            CreateSpaceScreen(
                                state = createState,
                                onBack = { showCreateSpace = false },
                                onNameChange = createController::setName,
                                onTopicChange = createController::setTopic,
                                onPublicChange = createController::setPublic,
                                onAddInvitee = createController::addInvitee,
                                onRemoveInvitee = createController::removeInvitee,
                                onCreate = createController::create
                            )
                        } else {
                            SpacesScreen(
                                state = ui,
                                onBack = { nav.pop() },
                                onRefresh = controller::refresh,
                                onSearch = controller::setSearchQuery,
                                onSelectSpace = { space -> controller.openSpace(space) },  // This now navigates
                                onCreateSpace = { showCreateSpace = true }
                            )
                        }
                    }

                    is Route.SpaceDetail -> {
                        val controller = remember(route.spaceId) {
                            SpacesController(
                                service = service,
                                onOpenRoom = { roomId, name -> nav.push(Route.Room(roomId, name)) },
                                onOpenSpace = { space -> nav.push(Route.SpaceDetail(space.roomId, space.name)) }
                            )
                        }
                        val ui by controller.state.collectAsState()

                        // Load the space hierarchy on first composition
                        LaunchedEffect(route.spaceId) {
                            controller.loadHierarchy(route.spaceId)
                        }

                        // Use selected space from state, or create a minimal one from route params
                        val displaySpace = ui.selectedSpace ?: SpaceInfo(
                            roomId = route.spaceId,
                            name = route.spaceName,
                            topic = null,
                            memberCount = 0,
                            isEncrypted = false,
                            isPublic = false
                        )

                        SpaceDetailScreen(
                            space = displaySpace,
                            hierarchy = ui.hierarchy,
                            isLoading = ui.isLoadingHierarchy,
                            hasMore = ui.hierarchyNextBatch != null,
                            error = ui.error,
                            onBack = { nav.pop() },
                            onRefresh = { controller.loadHierarchy(route.spaceId) },
                            onLoadMore = controller::loadMoreHierarchy,
                            onOpenChild = controller::openChild,
                            onOpenSettings = { nav.push(Route.SpaceSettings(route.spaceId)) }
                        )
                    }

                    is Route.SpaceSettings -> {
                        val controller = remember(route.spaceId) {
                            SpaceSettingsController(service, route.spaceId)
                        }
                        val ui by controller.state.collectAsState()

                        SpaceSettingsScreen(
                            state = ui,
                            onBack = { nav.pop() },
                            onRefresh = controller::refresh,
                            onAddChild = controller::addChild,
                            onRemoveChild = controller::removeChild,
                            onInviteUser = controller::inviteUser
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BindLifecycle(service: MatrixService) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                service.port.enterForeground()
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                service.port.enterBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
}