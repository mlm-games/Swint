package org.mlm.mages.nav

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
    data class RoomInfo(val roomId: String) : Route
    data class Thread(val roomId: String, val rootEventId: String, val roomName: String) : Route
}

/**
 * Route hierarchy for determining transition direction.
 */
private val Route.depth: Int get() = when (this) {
    Route.Login -> 0
    Route.Rooms -> 1
    Route.Security, Route.Discover, Route.Invites -> 2
    is Route.Room -> 2
    is Route.RoomInfo -> 3
    is Route.Thread -> 3
}

class Navigator(initial: Route) {
    private val backStack = mutableStateListOf<Route>().apply { add(initial) }
    val current: Route get() = backStack.last()

    private var _previousRoute: Route? = null
    val previousRoute: Route? get() = _previousRoute

    fun push(route: Route) {
        _previousRoute = current
        backStack += route
    }

    fun replace(route: Route) {
        _previousRoute = current
        if (backStack.isEmpty()) {
            backStack += route
        } else {
            backStack[backStack.lastIndex] = route
        }
    }

    fun pop(): Boolean {
        if (backStack.size > 1) {
            _previousRoute = current
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        return false
    }

    fun popUntil(predicate: (Route) -> Boolean) {
        while (backStack.size > 1 && !predicate(backStack.last())) {
            _previousRoute = current
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /**
     * Determines if navigation is forward (pushing) or backward (popping).
     */
    fun isForwardNavigation(): Boolean {
        val prev = _previousRoute ?: return true
        return current.depth >= prev.depth
    }
}

@Composable
fun rememberNavigator(initial: Route = Route.Login): Navigator = remember { Navigator(initial) }

/**
 * Collects deep-links and navigates to Room route.
 */
@Composable
fun BindDeepLinks(nav: Navigator, deepLinks: Flow<String>?) {
    LaunchedEffect(deepLinks) {
        deepLinks?.collectLatest { roomId ->
            nav.push(Route.Room(roomId = roomId, name = roomId))
        }
    }
}

/**
 * Screen transition specifications for consistent animations.
 */
object ScreenTransitions {
    private const val DURATION = 300

    val slideInFromRight: EnterTransition = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(DURATION)
    ) + fadeIn(tween(DURATION))

    val slideOutToLeft: ExitTransition = slideOutHorizontally(
        targetOffsetX = { -it / 3 },
        animationSpec = tween(DURATION)
    ) + fadeOut(tween(DURATION))

    val slideInFromLeft: EnterTransition = slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = tween(DURATION)
    ) + fadeIn(tween(DURATION))

    val slideOutToRight: ExitTransition = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(DURATION)
    ) + fadeOut(tween(DURATION))

    val fadeIn: EnterTransition = fadeIn(tween(DURATION))
    val fadeOut: ExitTransition = fadeOut(tween(DURATION))

    fun forwardTransition(): ContentTransform = slideInFromRight togetherWith slideOutToLeft
    fun backwardTransition(): ContentTransform = slideInFromLeft togetherWith slideOutToRight
    fun fadeTransition(): ContentTransform = fadeIn togetherWith fadeOut
}