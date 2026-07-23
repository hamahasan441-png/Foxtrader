package com.foxtrader.app.feature.auth.presentation

import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.UserProfile

/** Which form the auth screen is showing. */
enum class AuthMode { LOGIN, REGISTER }

/**
 * Immutable UI state for the auth (login/register) screen.
 */
data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val authState: AuthState = AuthState.UNAUTHENTICATED,
    val user: UserProfile? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Set true after a successful login/register so the UI can navigate away. */
    val success: Boolean = false,
) {
    /** Basic client-side validation before enabling the submit button. */
    val canSubmit: Boolean
        get() = email.contains("@") && password.length >= MIN_PASSWORD_LENGTH &&
            (mode == AuthMode.LOGIN || displayName.isNotBlank()) && !isSubmitting

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
