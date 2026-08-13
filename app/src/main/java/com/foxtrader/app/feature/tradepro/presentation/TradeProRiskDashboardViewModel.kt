package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AlertSeverity
import com.foxtrader.app.domain.model.tradepro.DailyPerformance
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.PortfolioRiskState
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.tradepro.TradeProRiskManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for the TRADEPRO Risk Dashboard.
 *
 * Aggregates portfolio risk data from [TradeProRiskManager] and exposes it through
 * an immutable [TradeProRiskDashboardUiState] for the Compose screen. Collects journal
 * entries to derive open positions, daily performance, and risk metrics.
 *
 * Known limitations:
 * - No ViewModel unit tests: the journal-to-state pipeline (filtering, mapping, isToday logic)
 *   is untested because this project does not use MockK and Android ViewModel dependencies
 *   (viewModelScope, Hilt injection) cannot be easily faked in JUnit4. The core risk logic
 *   is exercised via [TradeProRiskManagerTest] instead.
 * - Open positions use `currentPrice = entryPrice` since no live price feed is available.
 *   The UI exposes a `priceDataIsStale` flag so the dashboard can display a staleness indicator.
 */
@HiltViewModel
class TradeProRiskDashboardViewModel @Inject constructor(
    private val riskManager: TradeProRiskManager,
    private val appPreferences: AppPreferences,
    private val journalRepository: JournalRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TradeProRiskDashboardUiState(isLoading = true))
    val uiState: StateFlow<TradeProRiskDashboardUiState> = _uiState.asStateFlow()

    init {
        loadRiskData()
    }

    // -------------------------------------------------------------------
    // Public actions
    // -------------------------------------------------------------------

    /** Recalculate position size for the currently selected symbol and direction. */
    fun calculatePositionSize() {
        viewModelScope.launch {
            val state = _uiState.value
            val config = appPreferences.tradeProConfig.value
            try {
                val portfolioState = buildPortfolioState()
                val result = withContext(defaultDispatcher) {
                    riskManager.assessPosition(
                        symbol = state.positionSizerSymbol,
                        direction = state.positionSizerDirection,
                        config = config,
                        currentState = portfolioState,
                    )
                }
                _uiState.update { it.copy(positionSizerResult = result) }
            } catch (cancel: CancellationException) {
                // Never swallow cancellation: doing so breaks structured
                // concurrency and surfaces a routine screen-close or
                // re-run as a spurious on-screen error.
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Position sizing failed.") }
            }
        }
    }

    /** Update the symbol in the position sizer and recalculate. */
    fun setPositionSizerSymbol(symbol: String) {
        if (symbol == _uiState.value.positionSizerSymbol) return
        _uiState.update { it.copy(positionSizerSymbol = symbol, positionSizerResult = null) }
        calculatePositionSize()
    }

    /** Update the direction in the position sizer and recalculate. */
    fun setPositionSizerDirection(direction: Direction) {
        if (direction == _uiState.value.positionSizerDirection) return
        _uiState.update { it.copy(positionSizerDirection = direction, positionSizerResult = null) }
        calculatePositionSize()
    }

    // -------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------

    private fun loadRiskData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val config = appPreferences.tradeProConfig.value
                val (openPositions, dailyPerformance, hasOpenPositions) =
                    derivePortfolioData()

                // Compute portfolio state, alerts, and correlations.
                val portfolioState = withContext(defaultDispatcher) {
                    riskManager.getPortfolioState(openPositions, config, dailyPerformance)
                }
                val alerts = withContext(defaultDispatcher) {
                    riskManager.checkRiskAlerts(portfolioState)
                }
                val correlationGroups = withContext(defaultDispatcher) {
                    riskManager.calculateCorrelationExposure(openPositions)
                }

                // Compute initial position size.
                val positionSizeResult = withContext(defaultDispatcher) {
                    riskManager.assessPosition(
                        symbol = _uiState.value.positionSizerSymbol,
                        direction = _uiState.value.positionSizerDirection,
                        config = config,
                        currentState = portfolioState,
                    )
                }

                // Sort alerts: CRITICAL first, then WARNING, then INFO.
                val sortedAlerts = alerts.sortedByDescending { alert ->
                    when (alert.severity) {
                        AlertSeverity.CRITICAL -> 2
                        AlertSeverity.WARNING -> 1
                        AlertSeverity.INFO -> 0
                    }
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        priceDataIsStale = hasOpenPositions,
                        riskUtilization = (portfolioState.riskUtilizationPercent / 100.0)
                            .toFloat()
                            .coerceIn(0f, 1f),
                        dailyPnl = dailyPerformance.netPoints,
                        tradesTaken = dailyPerformance.tradesTaken,
                        wins = dailyPerformance.wins,
                        losses = dailyPerformance.losses,
                        netPoints = dailyPerformance.netPoints,
                        positionHeat = portfolioState.positionHeat.toImmutableList(),
                        alerts = sortedAlerts.toImmutableList(),
                        correlationGroups = correlationGroups.toImmutableList(),
                        positionSizerResult = positionSizeResult,
                        error = null,
                    )
                }
            } catch (cancel: CancellationException) {
                // Never swallow cancellation: doing so breaks structured
                // concurrency and surfaces a routine screen-close or
                // re-run as a spurious on-screen error.
                throw cancel
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load risk data.")
                }
            }
        }
    }

    /**
     * Build the current [PortfolioRiskState] from journal data.
     * Used by [calculatePositionSize] to get a fresh snapshot for the sizer.
     */
    private suspend fun buildPortfolioState(): PortfolioRiskState {
        val config = appPreferences.tradeProConfig.value
        val (openPositions, dailyPerformance, _) = derivePortfolioData()
        return riskManager.getPortfolioState(openPositions, config, dailyPerformance)
    }

    // -------------------------------------------------------------------
    // Shared portfolio derivation (single source of truth for both
    // loadRiskData and buildPortfolioState).
    // -------------------------------------------------------------------

    /**
     * Derives open positions and daily performance from journal entries.
     *
     * NOTE: `currentPrice` is set to `entryPrice` for all open positions because no live
     * price feed is currently integrated. This means unrealized P&L, heat calculations,
     * and utilization reflect risk *at entry* rather than current market risk. The UI
     * indicates this via [TradeProRiskDashboardUiState.priceDataIsStale].
     *
     * @return Triple of (open positions, daily performance, whether open positions exist).
     */
    private suspend fun derivePortfolioData(): PortfolioDataSnapshot {
        val entries = withContext(defaultDispatcher) {
            journalRepository.getAllEntries()
        }
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Derive open positions as ManagedTrade approximations from journal entries.
        val openPositions = entries
            .filter { it.isOpen }
            .map { entry ->
                ManagedTrade(
                    id = entry.id,
                    symbol = entry.symbol,
                    direction = entry.direction,
                    entryPrice = entry.entryPrice,
                    entryTimestamp = entry.entryTime,
                    contracts = entry.volume.toInt().coerceAtLeast(1),
                    stopPrice = entry.stopLoss,
                    t1Price = entry.takeProfit,
                    t2Price = entry.takeProfit * 1.5,
                    runnerTarget = entry.takeProfit * 2.0,
                    // No live price feed available; using entry price as approximation.
                    // Heat bars show risk based on stop distance (correct), but unrealized
                    // P&L will be zero for all open positions.
                    currentPrice = entry.entryPrice,
                    state = ManagedTradeState.ACTIVE,
                )
            }

        // Derive daily performance from today's closed trades.
        val todayEntries = entries.filter { entry ->
            !entry.isOpen && entry.exitTime != null &&
                isToday(entry.exitTime)
        }
        val dailyPerformance = DailyPerformance(
            date = today,
            tradesTaken = todayEntries.size,
            wins = todayEntries.count { it.isWin },
            losses = todayEntries.count { !it.isWin },
            netPoints = todayEntries.sumOf { it.pnl ?: 0.0 },
            cumulativeEquity = entries.sumOf { it.pnl ?: 0.0 },
            peakEquity = entries.runningFold(0.0) { acc, e -> acc + (e.pnl ?: 0.0) }
                .maxOrNull() ?: 0.0,
            drawdown = 0.0,
            complianceScore = 100.0,
        )

        return PortfolioDataSnapshot(
            openPositions = openPositions,
            dailyPerformance = dailyPerformance,
            hasOpenPositions = openPositions.isNotEmpty(),
        )
    }

    private fun isToday(epochMs: Long): Boolean {
        val today = LocalDate.now()
        val tradeDate = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return tradeDate == today
    }

    // -------------------------------------------------------------------
    // Internal data holder for the shared derivation method.
    // -------------------------------------------------------------------

    private data class PortfolioDataSnapshot(
        val openPositions: List<ManagedTrade>,
        val dailyPerformance: DailyPerformance,
        val hasOpenPositions: Boolean,
    )
}
