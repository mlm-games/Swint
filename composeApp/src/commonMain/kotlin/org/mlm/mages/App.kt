package org.mlm.mages

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.mlm.mages.di.KoinApp
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.nav.*
import org.mlm.mages.platform.*
import org.mlm.mages.ui.animation.*
import org.mlm.mages.ui.base.*
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.screens.*
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.viewmodel.*

@Composable
fun App(
    dataStore: DataStore<Preferences>,
    service: MatrixService,
    deepLinks: Flow<String>? = null
) {
    KoinApp(service = service, dataStore = dataStore) {
        AppContent(deepLinks = deepLinks)
    }
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun AppContent(
    deepLinks: Flow<String>?
) {
    val service: MatrixService = koinInject()
    val dataStore: DataStore<Preferences> = koinInject()

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
        BindNotifications(service, dataStore)

        LaunchedEffect(Unit) {
            if (service.isLoggedIn()) {
                service.startSupervisedSync()
            }
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
                        val viewModel: LoginViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    LoginViewModel.Event.LoginSuccess -> {
                                        sessionEpoch++
                                        backStack.replaceTop(Route.Rooms)
                                    }
                                }
                            }
                        }

                        LoginScreen(
                            viewModel = viewModel,
                            onSso = { viewModel.startSso(openUrl) }
                        )
                    }

                    entry<Route.Rooms> {
                        val viewModel: RoomsViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is RoomsViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is RoomsViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        RoomsScreen(
                            viewModel = viewModel,
                            onOpenSecurity = { backStack.add(Route.Security) },
                            onOpenDiscover = { backStack.add(Route.Discover) },
                            onOpenInvites = { backStack.add(Route.Invites) },
                            onOpenCreateRoom = { showCreateRoom = true },
                            onOpenSpaces = { backStack.add(Route.Spaces) }
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
                        val viewModel: RoomViewModel = koinViewModel(
                            parameters = { parametersOf(key.roomId, key.name) }
                        )

                        RoomScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onOpenInfo = { backStack.add(Route.RoomInfo(key.roomId)) },
                            onNavigateToRoom = { roomId, name -> backStack.add(Route.Room(roomId, name)) },
                            onNavigateToThread = { roomId, eventId, roomName ->
                                backStack.add(Route.Thread(roomId, eventId, roomName))
                            }
                        )
                    }

                    entry<Route.Security> {
                        val quitApp = rememberQuitApp()
                        val viewModel: SecurityViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is SecurityViewModel.Event.LogoutSuccess -> {
                                        sessionEpoch++
                                        backStack.replaceTop(Route.Login)
                                        quitApp()
                                    }
                                    is SecurityViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                    is SecurityViewModel.Event.ShowSuccess -> {
                                        snackbar.show(event.message)
                                    }
                                }
                            }
                        }

                        SecurityScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                        )
                    }

                    entry<Route.Discover> {
                        val viewModel: DiscoverViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is DiscoverViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is DiscoverViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        DiscoverRoute(
                            viewModel = viewModel,
                            onClose = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                        )
                    }

                    entry<Route.Invites> {
                        val viewModel: InvitesViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is InvitesViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is InvitesViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        InvitesRoute(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                        )
                    }

                    entry<Route.RoomInfo> { key ->
                        val viewModel: RoomInfoViewModel = koinViewModel(
                            parameters = { parametersOf(key.roomId) }
                        )

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is RoomInfoViewModel.Event.LeaveSuccess -> {
                                        backStack.popUntil { it is Route.Rooms }
                                    }
                                    is RoomInfoViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is RoomInfoViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                    is RoomInfoViewModel.Event.ShowSuccess -> {
                                        snackbar.show(event.message)
                                    }
                                }
                            }
                        }

                        RoomInfoRoute(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onLeaveSuccess = { backStack.popUntil { it is Route.Rooms } }
                        )
                    }

                    entry<Route.Thread> { key ->
                        val viewModel: ThreadViewModel = koinViewModel(
                            parameters = { parametersOf(key.roomId, key.rootEventId) }
                        )

                        ThreadRoute(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            snackbarController = snackbar
                        )
                    }

                    entry<Route.Spaces> {
                        val viewModel: SpacesViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is SpacesViewModel.Event.OpenSpace -> {
                                        backStack.add(Route.SpaceDetail(event.spaceId, event.name))
                                    }
                                    is SpacesViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is SpacesViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }

                                    else -> {}
                                }
                            }
                        }

                        SpacesScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                        )
                    }

                    entry<Route.SpaceDetail> { key ->
                        val viewModel: SpaceDetailViewModel = koinViewModel(
                            parameters = { parametersOf(key.spaceId, key.spaceName) }
                        )

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is SpaceDetailViewModel.Event.OpenSpace -> {
                                        backStack.add(Route.SpaceDetail(event.spaceId, event.name))
                                    }
                                    is SpaceDetailViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is SpaceDetailViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        SpaceDetailScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onOpenSettings = { backStack.add(Route.SpaceSettings(key.spaceId)) }
                        )
                    }

                    entry<Route.SpaceSettings> { key ->
                        val viewModel: SpaceSettingsViewModel = koinViewModel(
                            parameters = { parametersOf(key.spaceId) }
                        )

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is SpaceSettingsViewModel.Event.ShowError -> {
                                        snackbar.showError(event.message)
                                    }
                                    is SpaceSettingsViewModel.Event.ShowSuccess -> {
                                        snackbar.show(event.message)
                                    }
                                }
                            }
                        }

                        SpaceSettingsScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                        )
                    }
                }
            )
        }
    }
}