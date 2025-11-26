package org.mlm.mages.ui.base

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Base controller providing common patterns for state management and coroutine handling.
 */
abstract class BaseController<S>(initialState: S) {
    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state

    protected val currentState: S get() = _state.value

    protected fun updateState(transform: S.() -> S) {
        _state.update { it.transform() }
    }

    protected fun launch(
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke(e)
        }
    }

    protected suspend fun <T> runSafe(
        onError: ((Throwable) -> T?)? = null,
        block: suspend () -> T
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onError?.invoke(e)
    }

    open fun onCleared() {
        scope.cancel()
    }
}

/**
 * Common interface for states that have loading and error properties.
 */
interface LoadableState {
    val isLoading: Boolean
    val error: String?
}

/**
 * Extension to create a loading state copy.
 */
inline fun <reified S> S.withLoading(loading: Boolean): S where S : LoadableState {
    return when (this) {
        is org.mlm.mages.ui.RoomsUiState -> copy(isBusy = loading, error = null) as S
        is org.mlm.mages.ui.SecurityUiState -> copy(isLoadingDevices = loading, error = null) as S
        is org.mlm.mages.ui.controller.RoomInfoUiState -> copy(isLoading = loading, error = null) as S
        is org.mlm.mages.ui.controller.ThreadUi -> copy(isLoading = loading, error = null) as S
        is org.mlm.mages.ui.controller.DiscoverUi -> copy(isBusy = loading, error = null) as S
        is org.mlm.mages.ui.controller.InvitesUi -> copy(busy = loading, error = null) as S
        else -> this
    }
}