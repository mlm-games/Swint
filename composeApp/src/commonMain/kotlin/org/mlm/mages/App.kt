package org.mlm.mages

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.nav.*
import org.mlm.mages.platform.BindLifecycle
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberOpenBrowser
import org.mlm.mages.platform.rememberQuitApp
import org.mlm.mages.ui.animation.forwardTransition
import org.mlm.mages.ui.animation.popTransition
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

        val initialRoute = remember {
            if (service.isLoggedIn()) Route.Rooms else Route.Login
        }

        val backStack: NavBackStack<NavKey> =
            rememberNavBackStack(navSavedStateConfiguration, initialRoute)

        val snackbar = rememberSnackbarController()
        val scope = rememberCoroutineScope()

        var showCreateRoom by remember { mutableStateOf(false) }
        var sessionEpoch by remember { mutableIntStateOf(0) }

        BindDeepLinks(backStack, deepLinks)
        BindLifecycle(service)

        LaunchedEffect(service) {
            service.startSupervisedSync(object : MatrixPort.SyncObserver {
                override fun onState(status: MatrixPort.SyncStatus) { /* no-op */ }
            })
        }

        val roomsController = remember(service, dataStore, sessionEpoch) {
            RoomsController(
                service = service,
                dataStore = dataStore,
                onOpenRoom = { room -> backStack.add(Route.Room(room.id, room.name)) }
            )
        }

        val openUrl = rememberOpenBrowser()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar.hostState) }
        ) { _ ->


            NavDisplay(
                backStack = backStack,

                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),

                transitionSpec = forwardTransition,
                popTransitionSpec = popTransition,
                predictivePopTransitionSpec = { _ -> popTransition.invoke(this) },

                onBack = {
                    val top = backStack.lastOrNull()
                    val blockBack = top == Route.Login || top == Route.Rooms
                    if (!blockBack && backStack.size > 1) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                },

                entryProvider = entryProvider {

                    entry<Route.Login>(metadata = loginEntryFadeMetadata()) {
                        val controller = remember {
                            LoginController(
                                service = service,
                                dataStore = dataStore,
                                onLoggedIn = {
                                    sessionEpoch++
                                    backStack.replaceTop(Route.Rooms)
                                }
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


                    entry<Route.Rooms> {
                        val ui by roomsController.state.collectAsState()
                        RoomsScreen(
                            state = ui,
                            onRefresh = { /* Remove later */ },
                            onSearch = roomsController::setSearchQuery,
                            onOpen = { roomsController.open(it)},
                            onOpenSecurity = { backStack.add(Route.Security) },
                            onToggleUnreadOnly = { roomsController.toggleUnreadOnly() },
                            onOpenDiscover = { backStack.add(Route.Discover) },
                            onOpenInvites = { backStack.add(Route.Invites) },
                            onOpenCreateRoom = { showCreateRoom = true },
                            onOpenSpaces = { backStack.add(Route.Spaces) },
                        )
                        if (showCreateRoom) {
                            CreateRoomSheet(
                                onCreate = { name, topic, invitees ->
                                    scope.launch {
                                        val roomId = service.port.createRoom(name, topic, invitees)
                                        if (roomId != null) {
                                            showCreateRoom = false
                                            backStack.add(Route.Room(roomId, name ?: roomId))
                                        } else {
                                            snackbar.showError("Failed to create room")
                                        }
                                    }
                                },
                                onDismiss = { showCreateRoom = false }
                            )
                        }
                    }


                    entry<Route.Room> { key ->
                        val controller = remember(key.roomId) {
                            RoomController(service, dataStore, key.roomId, key.name)
                        }
                        DisposableEffect(key.roomId) {
                            onDispose {
                                controller.onCleared()
                            }
                        }
                        val ui by controller.state.collectAsState()
                        val openExternal = rememberFileOpener()
                        RoomScreen(
                            state = ui,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
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
                            onOpenInfo = { backStack.add(Route.RoomInfo(key.roomId)) },
                            onOpenThread = { ev ->
                                backStack.add(Route.Thread(key.roomId, ev.eventId, ui.roomName))
                            }
                        )
                    }


                    entry<Route.Security> {
                        val quitApp = rememberQuitApp()
                        var selectedTab by remember { mutableIntStateOf(0) }
                        val controller = remember { SecurityController(service) }
                        val ui by controller.state.collectAsState()
                        SecurityScreen(
                            state = ui,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onRefreshDevices = controller::refreshDevices,
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
                                    if (ok) {
                                        sessionEpoch++
                                        backStack.replaceTop(Route.Login)
                                        quitApp()
                                    }
                                }
                            }
                        )
                    }


                    entry<Route.Discover> {
                        val controller = remember { DiscoverController(service) }
                        val ui by controller.state.collectAsState()
                        DiscoverScreen(
                            state = ui,
                            onQuery = controller::setQuery,
                            onClose = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onOpenUser = { u ->
                                val rid = controller.ensureDm(u.userId)
                                if (rid != null) backStack.add(Route.Room(rid, u.displayName ?: u.userId))
                            },
                            onOpenRoom = { room ->
                                val rid = controller.joinOrOpen(room.alias ?: room.roomId)
                                if (rid != null) backStack.add(
                                    Route.Room(rid, room.name ?: room.alias ?: room.roomId)
                                )
                            }
                        )
                    }


                    entry<Route.Invites> {
                        val controller = remember { InvitesController(service.port) }
                        val ui by controller.state.collectAsState()
                        InvitesScreen(
                            invites = ui.invites,
                            busy = ui.busy,
                            error = ui.error,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onRefresh = controller::refresh,
                            onAccept = { controller.accept(it) },
                            onDecline = { controller.decline(it) },
                            onOpenRoom = { roomId, title -> backStack.add(Route.Room(roomId, title)) }
                        )
                    }


                    entry<Route.RoomInfo> { key ->
                        val controller = remember(key.roomId) { RoomInfoController(service, key.roomId) }
                        val state by controller.state.collectAsState()
                        RoomInfoScreen(
                            state = state,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onRefresh = controller::refresh,
                            onNameChange = controller::updateName,
                            onTopicChange = controller::updateTopic,
                            onToggleFavourite = controller::toggleFavourite,
                            onToggleLowPriority = controller::toggleLowPriority,
                            onSaveName = { controller.saveName() },
                            onSaveTopic = { controller.saveTopic() },
                            onLeave = { controller.leave() },
                            onLeaveSuccess = {
                                backStack.popUntil { it is Route.Rooms }
                            }
                        )
                    }


                    entry<Route.Thread> { key ->
                        val controller = remember(key.roomId, key.rootEventId) {
                            ThreadController(service, key.roomId, key.rootEventId)
                        }
                        val ui by controller.state.collectAsState()
                        ThreadScreen(
                            state = ui,
                            myUserId = service.port.whoami(),
                            onReact = controller::react,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onLoadMore = controller::loadMore,
                            onSendThread = { text, replyToId ->
                                val ok = service.port.sendThreadText(
                                    key.roomId,
                                    key.rootEventId,
                                    text,
                                    replyToId
                                )
                                if (ok) controller.refresh()
                                ok
                            },
                            onEdit = controller::startEdit,
                            onDelete = controller::delete,
                            onRetry = controller::retry,
                            snackbarController = snackbar
                        )
                    }


                    entry<Route.Spaces> {
                        val controller = remember {
                            SpacesController(
                                service = service,
                                onOpenRoom = { roomId, name -> backStack.add(Route.Room(roomId, name)) },
                                onOpenSpace = { space -> backStack.add(Route.SpaceDetail(space.roomId, space.name)) }
                            )
                        }
                        val ui by controller.state.collectAsState()
                        var showCreateSpace by remember { mutableStateOf(false) }

                        if (showCreateSpace) {
                            val createController = remember {
                                CreateSpaceController(
                                    service = service,
                                    onCreated = {
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
                                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                                onRefresh = controller::refresh,
                                onSearch = controller::setSearchQuery,
                                onSelectSpace = { space -> controller.openSpace(space) },
                                onCreateSpace = { showCreateSpace = true }
                            )
                        }
                    }


                    entry<Route.SpaceDetail> { key ->
                        val controller = remember(key.spaceId) {
                            SpacesController(
                                service = service,
                                onOpenRoom = { roomId, name -> backStack.add(Route.Room(roomId, name)) },
                                onOpenSpace = { space -> backStack.add(Route.SpaceDetail(space.roomId, space.name)) }
                            )
                        }
                        val ui by controller.state.collectAsState()

                        LaunchedEffect(key.spaceId) {
                            controller.loadHierarchy(key.spaceId)
                        }

                        val displaySpace = ui.selectedSpace ?: SpaceInfo(
                            roomId = key.spaceId,
                            name = key.spaceName,
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
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onRefresh = { controller.loadHierarchy(key.spaceId) },
                            onLoadMore = controller::loadMoreHierarchy,
                            onOpenChild = controller::openChild,
                            onOpenSettings = { backStack.add(Route.SpaceSettings(key.spaceId)) }
                        )
                    }


                    entry<Route.SpaceSettings> { key ->
                        val controller = remember(key.spaceId) {
                            SpaceSettingsController(service, key.spaceId)
                        }
                        val ui by controller.state.collectAsState()
                        SpaceSettingsScreen(
                            state = ui,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onRefresh = controller::refresh,
                            onAddChild = controller::addChild,
                            onRemoveChild = controller::removeChild,
                            onInviteUser = controller::inviteUser
                        )
                    }
                }
            )
        }
    }
}