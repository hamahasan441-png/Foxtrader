package com.foxtrader.app.feature.mt4.presentation

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4AccountProfile
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4PendingOrder
import com.foxtrader.app.domain.model.Mt4PendingExpirationType
import com.foxtrader.app.domain.model.Mt4Quote

/**
 * UI state for the MT4 connection feature.
 *
 * Represents the full lifecycle: disconnected (login form), connecting (loading),
 * connected (account info + positions + live trading), and error states.
 */
enum class BrokerExecutionMode { DEMO, LIVE, UNKNOWN }

enum class BrokerOrderEntryKind { MARKET, LIMIT, STOP }

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
        val platform: String = "mt4",
        val savedAccounts: List<Mt4AccountProfile> = emptyList(),
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
        val platform: String = "mt4",
        val executionMode: BrokerExecutionMode = when {
            accountInfo.server.contains("demo", ignoreCase = true) ||
                accountInfo.server.contains("practice", ignoreCase = true) -> BrokerExecutionMode.DEMO
            accountInfo.server.contains("real", ignoreCase = true) ||
                accountInfo.server.contains("live", ignoreCase = true) -> BrokerExecutionMode.LIVE
            else -> BrokerExecutionMode.UNKNOWN
        },
        val positions: List<Mt4Position> = emptyList(),
        val pendingOrders: List<Mt4PendingOrder> = emptyList(),
        val isRefreshing: Boolean = false,
        // --- Live trading controls ---
        val liveModeEnabled: Boolean = false,
        val killSwitchEngaged: Boolean = false,
        /** Latest quote for [tradeSymbol], used for the live price + stale gate. */
        val quote: Mt4Quote? = null,
        /** Symbol being traded (prefilled from the current chart symbol). */
        val tradeSymbol: String = "EURUSD",
        val tradeDirection: Direction = Direction.BULLISH,
        val orderEntryKind: BrokerOrderEntryKind = BrokerOrderEntryKind.MARKET,
        val pendingPriceInput: String = "",
        val pendingExpirationType: Mt4PendingExpirationType = Mt4PendingExpirationType.GTC,
        /** Local time in yyyy-MM-dd HH:mm for SPECIFIED/SPECIFIED_DAY. */
        val pendingExpirationInput: String = "",
        val lotsInput: String = "0.01",
        val slInput: String = "",
        val tpInput: String = "",
        val isPlacing: Boolean = false,
        /** Non-null while a two-step order confirmation dialog is showing. */
        val pendingOrder: PendingTrade? = null,
        /** Non-null while a two-step position-close confirmation is showing. */
        val pendingClose: PendingClose? = null,
        /** Interactive position-management editor; Apply buttons are explicit confirmations. */
        val positionManager: PositionManagerDraft? = null,
        /** Interactive pending-order edit/cancel editor. */
        val pendingOrderManager: PendingOrderManagerDraft? = null,
        /** One-shot message for the user (success/error/info). */
        val notice: String? = null,
    ) : Mt4UiState {
        val lastPrice: Double? get() = quote?.let { (it.bid + it.ask) / 2.0 }
        val lots: Double? get() = lotsInput.toDoubleOrNull()
    }

    /**
     * A trade awaiting final confirmation (the two-step flow).
     * [confirmationTimestamp] is captured when the review dialog is created;
     * the safety layer rejects stale reviews instead of allowing an old dialog
     * to mint a fresh authorization at the moment of submission.
     *
     * [isVolumeEstimated] is true when the volume bounds used for validation
     * fell back to hardcoded defaults because the broker spec fetch failed.
     * The UI should surface this so the user knows validation isn't broker-
     * authoritative.
     */
    data class PendingTrade(
        val symbol: String,
        val direction: Direction,
        val orderType: Mt4OrderType,
        val lots: Double,
        val entryPrice: Double,
        val stopLoss: Double?,
        val takeProfit: Double?,
        /** Adaptive review-to-submit price drift guard, in broker points. */
        val maxSlippagePoints: Double? = null,
        val expirationType: Mt4PendingExpirationType = Mt4PendingExpirationType.GTC,
        val expirationTime: Long? = null,
        val confirmationTimestamp: Long = System.currentTimeMillis(),
        val isVolumeEstimated: Boolean = false,
        val volumeBoundsNote: String? = null,
    )

    data class PositionManagerDraft(
        val ticket: Long,
        val symbol: String,
        val side: Mt4OrderType,
        val openPrice: Double,
        val lots: Double,
        val originalStopLoss: Double,
        val originalTakeProfit: Double,
        val stopLossInput: String,
        val takeProfitInput: String,
        val trailingPointsInput: String = "",
        val partialLotsInput: String = "",
        val reviewTimestamp: Long = System.currentTimeMillis(),
    )

    data class PendingOrderManagerDraft(
        val ticket: Long,
        val symbol: String,
        val type: Mt4OrderType,
        val lots: Double,
        val originalOpenPrice: Double,
        val originalStopLoss: Double,
        val originalTakeProfit: Double,
        val openPriceInput: String,
        val stopLossInput: String,
        val takeProfitInput: String,
        val reviewTimestamp: Long = System.currentTimeMillis(),
    )

    data class PendingClose(
        val ticket: Long,
        val symbol: String,
        val lots: Double,
        val profit: Double,
        val confirmationTimestamp: Long = System.currentTimeMillis(),
    )

    /**
     * A fatal error that requires user action (e.g. re-entering credentials).
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : Mt4UiState
}
