package org.mlm.mages.ui.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mlm.mages.MatrixService
import org.mlm.mages.ui.LoginUiState
import kotlin.time.ExperimentalTime

class LoginController(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>,
    private val onLoggedIn: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    init {
        scope.launch {
            runCatching { service.init(_state.value.homeserver) }
            if (service.isLoggedIn()) {
                withContext(Dispatchers.Main) { onLoggedIn() }
            }
        }
    }

    fun setHomeserver(v: String) { _state.update { it.copy(homeserver = v) } }
    fun setUser(v: String) { _state.update { it.copy(user = v) } }
    fun setPass(v: String) { _state.update { it.copy(pass = v) } }

    @OptIn(ExperimentalTime::class)
    fun submit() {
        val s = _state.value
        if (s.isBusy || s.user.isBlank() || s.pass.isBlank()) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                service.init(s.homeserver)
                service.login(s.user, s.pass, "Mages")
            }.onSuccess {
                // Persist HS for receivers/services
                withContext(Dispatchers.Default) {
                    org.mlm.mages.storage.saveString(dataStore, "homeserver", _state.value.homeserver)
                    org.mlm.mages.storage.saveLong(dataStore, "notif:baseline_ms", kotlin.time.Clock.System.now().toEpochMilliseconds())
                }
                _state.update { it.copy(isBusy = false) }
                withContext(Dispatchers.Main) { onLoggedIn() }
            }.onFailure { t ->
                _state.update { it.copy(isBusy = false, error = t.message ?: "Login failed") }
            }
        }
    }
}