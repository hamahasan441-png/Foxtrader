package com.foxtrader.app.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the login / register screen.
 * Exposes a single immutable [AuthUiState] and handles form input + submission.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // Mirror repository auth state into the UI.
        authRepository.authState
            .onEach { state -> _uiState.update { it.copy(authState = state) } }
            .launchIn(viewModelScope)
    }

    // --- Form input ---

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value.trim(), error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value, error = null) }

    fun toggleMode() = _uiState.update {
        it.copy(
            mode = if (it.mode == AuthMode.LOGIN) AuthMode.REGISTER else AuthMode.LOGIN,
            error = null,
        )
    }

    // --- Submission ---

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            val result = when (state.mode) {
                AuthMode.LOGIN -> authRepository.login(state.email, state.password)
                AuthMode.REGISTER -> authRepository.register(
                    email = state.email,
                    password = state.password,
                    displayName = state.displayName,
                )
            }

            result.onSuccess { user ->
                _uiState.update {
                    it.copy(isSubmitting = false, user = user, success = true, error = null)
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = e.message ?: "Authentication failed. Please try again.",
                    )
                }
            }
        }
    }

    /** Reset the transient success flag after the UI has navigated. */
    fun consumeSuccess() = _uiState.update { it.copy(success = false) }
}
