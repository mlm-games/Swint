package org.mlm.mages

import androidx.compose.runtime.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.mlm.mages.nav.*
import org.mlm.mages.ui.MainTheme
import org.mlm.mages.ui.controller.*
import org.mlm.mages.ui.screens.*

@Composable
fun App(
    dataStore: DataStore<Preferences>,
    deepLinks: Flow<String>? = null
) {
    MainTheme {
        val nav = rememberNavigator(initial = Route.Login)
        BindDeepLinks(nav, deepLinks)

        val service = remember { MatrixService(port = org.mlm.mages.matrix.createMatrixPort("https://matrix.org")) }

        BindLifecycle(service)

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
                    onSubmit = controller::submit
                )
            }
            Route.Rooms -> {
                val controller = remember {
                    RoomsController(
                        service = service,
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
                    onOpenSecurity = { nav.push(Route.Security) }
                )
            }
            is Route.Room -> {
                val controller = remember(r.roomId) { RoomController(service, dataStore, r.roomId, r.name) }
                val ui by controller.state.collectAsState()
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
                )
            }
            Route.Security -> {
                var selectedTab by remember { mutableIntStateOf(0) }
                val controller = remember {
                    SecurityController(
                        service = service,
                        onOpenMediaCache = { nav.push(Route.MediaCache) },
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
                    onOpenMediaCache = { nav.push(Route.MediaCache) },
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
            Route.MediaCache -> {
                val controller = remember { MediaCacheController(service) }
                val ui by controller.state.collectAsState()
                MediaCacheScreen(
                    state = ui,
                    onBack = { nav.pop() },
                    onRefresh = controller::refresh,
                    onClearKeep = controller::clearKeep,
                    onClearAll = controller::clearAll
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
