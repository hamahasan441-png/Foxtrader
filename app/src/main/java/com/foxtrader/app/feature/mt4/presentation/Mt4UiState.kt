package com.foxtrader.app.feature.mt4.presentation

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4Quote

/**
 * UI state for the MT4 connection feature.
 *
 * Represents the full lifecycle: disconnected (login form), connecting (loading),
 * connected (account info + positions + live trading), and error states.
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
        /** Current broker-search query (for the search field). */
        val brokerQuery: String = "",
        /** Broker search results matching [brokerQuery]. */
        val brokerResults: List<Mt4Broker> = emptyList(),
    ) : Mt4UiState {
        val canSubmit: Boolean
            get() = login.isNotBlank() && password.isNotBlank() && server.isNotBlank()
    }

    /**
     * Connection in progress.
     */
    data object Connecting : Mt4UiState

    /**
     * Successfully connected; showing account info, positions, and live trading.
     */
    data class Connected(
        val accountInfo: Mt4AccountInfo,
        val positions: List<Mt4Position> = emptyList(),
        val isRefreshing: Boolean = false,
        // --- Live trading controls ---
        val liveModeEnabled: Boolean = false,
        val killSwitchEngaged: Boolean = false,
        /** Latest quote for [tradeSymbol], used for the live price + stale gate. */
        val quote: Mt4Quote? = null,
        /** Symbol being traded (prefilled from the current chart symbol). */
        val tradeSymbol: String = "EURUSD",
        val tradeDirection: Direction = Direction.BULLISH,
        val lotsInput: String = "0.01",
        val slInput: String = "",
        val tpInput: String = "",
        val isPlacing: Boolean = false,
        /** Non-null while a two-step confirmation dialog is showing. */
        val pendingOrder: PendingTrade? = null,
        /** One-shot message for the user (success/error/info). */
        val notice: String? = null,
    ) : Mt4UiState {
        val lastPrice: Double? get() = quote?.let { (it.bid + it.ask) / 2.0 }
        val lots: Double? get() = lotsInput.toDoubleOrNull()
    }

    /**
     * A trade awaiting final confirmation (the two-step flow). The second step
     * carries [confirmationTimestamp] captured at the moment the user confirms,
     * which the safety layer checks for freshness.
     *
     * [isVolumeEstimated] is true when the volume bounds used for validation
     * fell back to hardcoded defaults because the broker spec fetch failed.
     * The UI should surface this so the user knows validation isn't broker-
     * authoritative.
     */
    data class PendingTrade(
        val symbol: String,
        val direction: Direction,
        val lots: Double,
        val entryPrice: Double,
        val stopLoss: Double?,
        val takeProfit: Double?,
        val confirmationTimestamp: Long = System.currentTimeMillis(),
        val isVolumeEstimated: Boolean = false,
        val volumeBoundsNote: String? = null,
    )

    /**
     * A fatal error that requires user action (e.g. re-entering credentials).
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : Mt4UiState
}
