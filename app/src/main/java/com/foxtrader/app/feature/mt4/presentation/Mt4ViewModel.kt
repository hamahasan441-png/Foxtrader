package com.foxtrader.app.feature.mt4.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.repository.Mt4Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the MT4 connection screens (login + account).
 *
 * Manages the MT4 connection lifecycle: collecting credentials, connecting
 * via MetaApi, displaying account info and positions, and disconnecting.
 */
@HiltViewModel
class Mt4ViewModel @Inject constructor(
    private val mt4Repository: Mt4Repository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<Mt4UiState>(Mt4UiState.Disconnected())
    val uiState: StateFlow<Mt4UiState> = _uiState.asStateFlow()

    // --- Form input (Disconnected state only) ---

    fun onLoginChange(value: String) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(login = value.trim(), error = null) }
        }
    }

    fun onPasswordChange(value: String) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(password = value, error = null) }
        }
    }

    fun onServerChange(value: String) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(server = value.trim(), error = null) }
        }
    }

    // --- Connection ---

    fun connect() {
        val current = _uiState.value
        if (current !is Mt4UiState.Disconnected || !current.canSubmit) return

        val loginInt = current.login.toIntOrNull()
        if (loginInt == null) {
            _uiState.value = current.copy(error = "Login must be a number")
            return
        }

        val credentials = Mt4Credentials(
            login = loginInt,
            password = current.password,
            server = current.server,
        )

        _uiState.value = Mt4UiState.Connecting

        viewModelScope.launch {
            mt4Repository.connect(credentials)
                .onSuccess { accountInfo ->
                    val positions = mt4Repository.getPositions().getOrDefault(emptyList())
                    _uiState.value = Mt4UiState.Connected(
                        accountInfo = accountInfo,
                        positions = positions,
                    )
                }
                .onFailure { e ->
                    _uiState.value = Mt4UiState.Disconnected(
                        login = current.login,
                        password = current.password,
                        server = current.server,
                        error = e.message ?: "Connection failed. Please check your credentials.",
                    )
                }
        }
    }

    // --- Connected state actions ---

    fun refreshPositions() {
        val current = _uiState.value
        if (current !is Mt4UiState.Connected) return

        _uiState.value = current.copy(isRefreshing = true)

        viewModelScope.launch {
            mt4Repository.getPositions()
                .onSuccess { positions ->
                    val latestState = _uiState.value
                    if (latestState is Mt4UiState.Connected) {
                        _uiState.value = latestState.copy(
                            positions = positions,
                            isRefreshing = false,
                        )
                    }
                }
                .onFailure {
                    val latestState = _uiState.value
                    if (latestState is Mt4UiState.Connected) {
                        _uiState.value = latestState.copy(isRefreshing = false)
                    }
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            mt4Repository.disconnect()
            _uiState.value = Mt4UiState.Disconnected()
        }
    }
}
