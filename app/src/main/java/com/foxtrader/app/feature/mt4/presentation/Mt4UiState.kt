package com.foxtrader.app.feature.mt4.presentation

import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Position

/**
 * UI state for the MT4 connection feature.
 *
 * Represents the full lifecycle: disconnected (login form), connecting (loading),
 * connected (account info + positions), and error states.
 */
sealed interface Mt4UiState {

    /**
     * Not connected; the login form is displayed.
     *
     * @param login MT4 account login input.
     * @param password MT4 account password input.
     * @param server MT4 broker server name input.
     * @param error Error message to display (null if none).
     */
    data class Disconnected(
        val login: String = "",
        val password: String = "",
        val server: String = "",
        val error: String? = null,
    ) : Mt4UiState {
        val canSubmit: Boolean
            get() = login.isNotBlank() && password.isNotBlank() && server.isNotBlank()
    }

    /**
     * Connection in progress.
     */
    data object Connecting : Mt4UiState

    /**
     * Successfully connected; showing account info and positions.
     *
     * @param accountInfo The connected account information.
     * @param positions Currently open positions.
     * @param isRefreshing True while data is being refreshed.
     */
    data class Connected(
        val accountInfo: Mt4AccountInfo,
        val positions: List<Mt4Position> = emptyList(),
        val isRefreshing: Boolean = false,
    ) : Mt4UiState

    /**
     * A fatal error that requires user action (e.g. re-entering credentials).
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : Mt4UiState
}
