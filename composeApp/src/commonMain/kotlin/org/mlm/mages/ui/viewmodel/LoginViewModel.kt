package org.mlm.mages.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.mlm.mages.MatrixService
import org.mlm.mages.storage.saveLong
import org.mlm.mages.storage.saveString
import org.mlm.mages.ui.LoginUiState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class LoginViewModel(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>
) : BaseViewModel<LoginUiState>(LoginUiState()) {

    // One-time events
    sealed class Event {
        data object LoginSuccess : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Initialize Matrix service with default homeserver
        launch {
            runSafe { service.init(currentState.homeserver) }
        }
    }

    //  Public Actions 

    fun setHomeserver(value: String) {
        updateState { copy(homeserver = value) }
    }

    fun setUser(value: String) {
        updateState { copy(user = value) }
    }

    fun setPass(value: String) {
        updateState { copy(pass = value) }
    }

    @OptIn(ExperimentalTime::class)
    fun submit() {
        val s = currentState
        if (s.isBusy || s.user.isBlank() || s.pass.isBlank()) return

        launch(
            onError = { t ->
                updateState { copy(isBusy = false, error = t.message ?: "Login failed") }
            }
        ) {
            updateState { copy(isBusy = true, error = null) }

            // Initialize with homeserver
            service.init(s.homeserver)

            // Perform login
            service.login(s.user, s.pass, "Mages")

            // Persist homeserver for receivers/services
            withContext(Dispatchers.Default) {
                saveString(dataStore, "homeserver", s.homeserver)
                saveLong(dataStore, "notif:baseline_ms", Clock.System.now().toEpochMilliseconds())
            }

            updateState { copy(isBusy = false, error = null) }
            _events.send(Event.LoginSuccess)
        }
    }

    @OptIn(ExperimentalTime::class)
    fun startSso(openUrl: (String) -> Boolean) {
        if (currentState.isBusy) return

        launch(
            onError = { t ->
                updateState { copy(isBusy = false, error = t.message ?: "SSO failed") }
            }
        ) {
            updateState { copy(isBusy = true, error = null) }

            // Initialize with homeserver first
            service.init(currentState.homeserver)

            // Start SSO flow
            service.port.loginSsoLoopback(openUrl, deviceName = "Mages")

            // Persist homeserver
            withContext(Dispatchers.Default) {
                saveString(dataStore, "homeserver", currentState.homeserver)
                saveLong(dataStore, "notif:baseline_ms", Clock.System.now().toEpochMilliseconds())
            }

            updateState { copy(isBusy = false, error = null) }
            _events.send(Event.LoginSuccess)
        }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }
}