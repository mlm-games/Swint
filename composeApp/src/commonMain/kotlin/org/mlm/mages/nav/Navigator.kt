package org.mlm.mages.nav

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

sealed interface Route {
    data object Login : Route
    data object Rooms : Route
    data class Room(val roomId: String, val name: String) : Route

    data object Security : Route
    data object Discover : Route
    data object Invites : Route
}

class Navigator(initial: Route) {
    private val backStack = mutableStateListOf<Route>().apply { add(initial) }
    val current: Route get() = backStack.last()

    fun push(route: Route) { backStack += route }
    fun replace(route: Route) { if (backStack.isEmpty()) { backStack += route } else { backStack[backStack.lastIndex] = route } }
    fun pop(): Boolean = if (backStack.size > 1) { backStack.removeAt(backStack.lastIndex); true } else false
}

@Composable
fun rememberNavigator(initial: Route = Route.Login): Navigator = remember { Navigator(initial) }

/**
 * collects deep-links like "mages://room?id=..." and push to Room route.
 */
@Composable
fun BindDeepLinks(nav: Navigator, deepLinks: Flow<String>?) {
    LaunchedEffect(deepLinks) {
        deepLinks?.collectLatest { roomId ->
            nav.push(Route.Room(roomId = roomId, name = roomId)) // name can be resolved after listing rooms
        }
    }
}