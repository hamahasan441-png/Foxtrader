package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.DrawingRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.chart.ComputeIndicatorsUseCase
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
import com.foxtrader.app.domain.usecase.replay.ReplayEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Chart screen ViewModel (MVVM).
 *
 * Integrates ALL chart features:
 * - Market data (offline-first via repository + real-time via WebSocket)
 * - Technical analysis (market structure, EMA indicators)
 * - SMC concepts (order blocks, FVGs, liquidity pools)
 * - Trading sessions (London/NY/Tokyo/Sydney)
 * - Drawing tools (trend lines, fibs, etc.)
 * - Replay mode (bar-by-bar playback)
 * - Connection state (WebSocket live feed indicator)
 *
 * Heavy CPU work (indicators, SMC, structure analysis) is offloaded to the
 * [DefaultDispatcher] so the main thread is never blocked.
 *
 * Exposes a single immutable [ChartUiState].
 */
@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val computeIndicators: ComputeIndicatorsUseCase,
    private val webSocket: MarketWebSocket,
    val drawingEngine: DrawingEngine,
    val replayEngine: ReplayEngine,
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val aiAlertService: AiAlertService,
    private val alertDispatcher: AlertDispatcher,
    private val drawingRepository: DrawingRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChartUiState())
    val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

    /** Replay state exposed separately for the overlay composable. */
    val replayState: StateFlow<ReplayState> = replayEngine.state

    /** WebSocket connection state for the UI indicator. */
    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState

    private val symbolFlow = MutableStateFlow(ChartUiState().symbol)
    private val timeframeFlow = MutableStateFlow(ChartUiState().timeframe)

    /**
     * Fingerprint of the last candle series passed to the AI pipeline.
     * Used to skip re-running the expensive multi-agent analysis when the
     * data has not changed (e.g. rapid indicator-toggle recomputations).
     */
    private var lastAiCandlesHash: Int = 0

    init {
        observeMarket()
        observeDrawings()
        observeWebSocketTicks()
        // Mirror WebSocket connection state into the UI state for the LIVE badge.
        webSocket.connectionState
            .onEach { cs -> _uiState.value = _uiState.value.copy(connectionState = cs) }
            .launchIn(viewModelScope)
        refresh()
    }

    /** Observe persisted drawings for the current symbol/timeframe. */
    private fun observeDrawings() {
        combine(symbolFlow, timeframeFlow) { s, tf -> s to tf }
            .flatMapLatest { (symbol, tf) -> drawingRepository.observe(symbol, tf) }
            .onEach { drawings -> _uiState.value = _uiState.value.copy(drawings = drawings) }
            .launchIn(viewModelScope)
    }

    // ========================================================================
    // MARKET DATA OBSERVATION
    // ========================================================================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMarket() {
        combine(symbolFlow, timeframeFlow) { symbol, tf -> symbol to tf }
            .flatMapLatest { (symbol, tf) -> repository.observeCandles(symbol, tf) }
            .onEach { candles -> processCandles(candles) }
            .launchIn(viewModelScope)
    }

    private fun observeWebSocketTicks() {
        webSocket.ticks
            .onEach { tick ->
                // Only apply ticks for the currently displayed symbol/timeframe.
                if (tick.symbol == symbolFlow.value && tick.timeframe == timeframeFlow.value) {
                    // Persist into Room (SSOT) so the DB remains the authority.
                    // The DB Flow observer will pick up the change and trigger
                    // processCandles, so we DON'T duplicate the update here.
                    viewModelScope.launch {
                        repository.upsertCandle(tick.symbol, tick.timeframe, tick.candle)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Central candle processing pipeline.
     *
     * All CPU-bound work (structure analysis + indicator computation) is
     * dispatched to [defaultDispatcher] so the main thread stays responsive.
     * The resulting [ChartUiState] is applied atomically back on the main thread
     * via the StateFlow collector (Kotlin coroutines ensure safe value delivery).
     */
    private fun processCandles(candles: List<Candle>) {
        viewModelScope.launch {
            val ind = _uiState.value.indicators

            // Offload heavy computation to the Default (CPU) dispatcher.
            val (structure, overlays) = withContext(defaultDispatcher) {
                val s = analyzeStructure(candles)
                val o = computeIndicators(candles, ind)
                s to o
            }

            _uiState.value = _uiState.value.copy(
                candles = candles,
                bias = structure.bias,
                structureBreaks = if (ind.structure) structure.breaks else emptyList(),
                emaShort = overlays.emaShort,
                emaLong = overlays.emaLong,
                bollingerUpper = overlays.bollingerUpper,
                bollingerMiddle = overlays.bollingerMiddle,
                bollingerLower = overlays.bollingerLower,
                superTrendValues = overlays.superTrendValues,
                superTrendDir = overlays.superTrendDir,
                parabolicSar = overlays.parabolicSar,
                vwap = overlays.vwap,
                ichimokuTenkan = overlays.ichimokuTenkan,
                ichimokuKijun = overlays.ichimokuKijun,
                ichimokuSenkouA = overlays.ichimokuSenkouA,
                ichimokuSenkouB = overlays.ichimokuSenkouB,
                ichimokuChikou = overlays.ichimokuChikou,
                orderBlocks = overlays.orderBlocks,
                fairValueGaps = overlays.fairValueGaps,
                liquidityPools = overlays.liquidityPools,
                volumeProfile = overlays.volumeProfile,
                sessions = overlays.sessions,
                isLoading = candles.isEmpty() && _uiState.value.error == null,
            )

            // --- AI Decision Engine (run after analysis is ready) ---
            runAiDecision(candles)
        }
    }

    // ========================================================================
    // AI DECISION ENGINE
    // ========================================================================

    /**
     * Run the multi-agent reasoning pipeline and update the UI with the result.
     *
     * Guards:
     * - Requires ≥50 candles (insufficient data → clear decision).
     * - Skips re-running if the candle series has not changed since the last
     *   analysis (change detected via a lightweight hash of size + last close).
     * - The orchestrator runs on [defaultDispatcher] to avoid blocking the UI.
     */
    private fun runAiDecision(candles: List<Candle>) {
        if (candles.size < 50) {
            _uiState.value = _uiState.value.copy(aiDecision = null)
            lastAiCandlesHash = 0
            return
        }
        // At this point candles.size >= 50, so candles is non-empty and
        // candles.last() is safe. The guard above covers both empty and
        // insufficient-data cases.

        // Lightweight fingerprint: candle count + last close price bits.
        val lastClose = candles.last().close
        val hash = (candles.size * 31L + lastClose.toBits()).toInt()
        if (hash == lastAiCandlesHash) return
        lastAiCandlesHash = hash

        viewModelScope.launch {
            val mtfCandles = mtfContextProvider.getHtfContext(
                symbol = symbolFlow.value,
                executionTimeframe = timeframeFlow.value,
            )
            val context = AgentContext(
                symbol = symbolFlow.value,
                timeframe = timeframeFlow.value,
                candles = candles,
                mtfCandles = mtfCandles,
            )

            // Multi-agent analysis is CPU-bound; run it off the main thread.
            val orchestratorResult = withContext(defaultDispatcher) {
                orchestrator.analyze(context)
            }
            val decision = decisionEngine.evaluate(orchestratorResult)
            _uiState.value = _uiState.value.copy(aiDecision = decision)

            // Fire a push alert if the AI approves a signal (cooldown-gated).
            val alert = aiAlertService.evaluate(decision, symbolFlow.value)
            if (alert != null) {
                alertDispatcher.dispatch(alert)
            }
        }
    }

    // ========================================================================
    // REFRESH / SYMBOL / TIMEFRAME
    // ========================================================================

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.refreshCandles(symbolFlow.value, timeframeFlow.value)
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load market data",
                    )
                }
        }
    }

    fun onSymbolChange(symbol: String) {
        symbolFlow.value = symbol
        _uiState.value = _uiState.value.copy(symbol = symbol, showSymbolPicker = false)
        aiAlertService.resetCooldowns()
        refresh()
        // Re-subscribe live feed to the new symbol only if live is enabled.
        if (_uiState.value.liveEnabled) {
            viewModelScope.launch {
                webSocket.disconnectAll()
                webSocket.subscribe(symbol, timeframeFlow.value)
            }
        }
    }

    // ========================================================================
    // INDICATOR / SYMBOL PICKER / LIVE UI ACTIONS
    // ========================================================================

    fun toggleIndicatorPanel() {
        _uiState.value = _uiState.value.copy(showIndicatorPanel = !_uiState.value.showIndicatorPanel)
    }

    /** Update indicator toggles and immediately recompute against current candles. */
    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
        _uiState.value = _uiState.value.copy(indicators = transform(_uiState.value.indicators))
        processCandles(_uiState.value.candles)
    }

    fun openSymbolPicker() {
        _uiState.value = _uiState.value.copy(showSymbolPicker = true)
    }

    fun closeSymbolPicker() {
        _uiState.value = _uiState.value.copy(showSymbolPicker = false)
    }

    /** Toggle the real-time WebSocket feed on/off. */
    fun toggleLive() {
        val enabled = !_uiState.value.liveEnabled
        _uiState.value = _uiState.value.copy(liveEnabled = enabled)
        if (enabled) connectLive() else disconnectLive()
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        timeframeFlow.value = timeframe
        _uiState.value = _uiState.value.copy(timeframe = timeframe)
        refresh()
        // Re-subscribe live feed to the new timeframe only if live is enabled.
        if (_uiState.value.liveEnabled) {
            viewModelScope.launch {
                webSocket.disconnectAll()
                webSocket.subscribe(symbolFlow.value, timeframe)
            }
        }
    }

    // ========================================================================
    // WEBSOCKET CONTROLS
    // ========================================================================

    fun connectLive() {
        viewModelScope.launch {
            webSocket.subscribe(symbolFlow.value, timeframeFlow.value)
        }
    }

    fun disconnectLive() {
        viewModelScope.launch {
            webSocket.disconnectAll()
        }
    }

    // ========================================================================
    // DRAWING TOOL ACTIONS
    // ========================================================================

    fun startDrawing(type: DrawingToolType) {
        drawingEngine.startPlacing(type)
        _uiState.value = _uiState.value.copy(
            drawingMode = drawingEngine.mode,
            activeTool = type,
            showDrawingToolbar = true,
        )
    }

    fun placeDrawingPoint(index: Float, price: Double) {
        val point = ChartPoint(index = index, price = price)
        val completed = drawingEngine.placePoint(point)
        _uiState.value = _uiState.value.copy(
            drawingMode = drawingEngine.mode,
            drawings = drawingEngine.getVisibleDrawings(),
        )
        // Persist the completed drawing to Room.
        if (completed != null) {
            viewModelScope.launch {
                drawingRepository.upsert(completed, symbolFlow.value, timeframeFlow.value)
            }
        }
    }

    fun cancelDrawing() {
        drawingEngine.cancelPlacement()
        _uiState.value = _uiState.value.copy(
            drawingMode = drawingEngine.mode,
            activeTool = null,
        )
    }

    fun clearAllDrawings() {
        drawingEngine.clearAll()
        viewModelScope.launch {
            drawingRepository.clearForChart(symbolFlow.value, timeframeFlow.value)
        }
    }

    fun toggleDrawingToolbar() {
        val show = !_uiState.value.showDrawingToolbar
        _uiState.value = _uiState.value.copy(showDrawingToolbar = show)
        if (!show) cancelDrawing()
    }

    // ========================================================================
    // REPLAY CONTROLS
    // ========================================================================

    fun startReplay(startAt: Int = 50) {
        replayEngine.start(_uiState.value.candles, startAt)
    }

    fun stopReplay() {
        replayEngine.stop()
    }

    fun toggleReplayPlayPause() {
        replayEngine.togglePlayPause()
    }

    fun replayStepForward() {
        replayEngine.stepForward()
    }

    fun replayStepBackward() {
        replayEngine.stepBackward()
    }

    fun replayCycleSpeed() {
        replayEngine.cycleSpeed()
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            webSocket.disconnectAll()
        }
        replayEngine.stop()
    }
}
