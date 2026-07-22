package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.SuperTrend
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.replay.ReplayEngine
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
 * Exposes a single immutable [ChartUiState].
 */
@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val webSocket: MarketWebSocket,
    private val smcDetector: SmcDetector,
    private val sessionDetector: SessionDetector,
    private val bollingerBands: BollingerBands,
    private val superTrend: SuperTrend,
    private val parabolicSar: ParabolicSar,
    val drawingEngine: DrawingEngine,
    val replayEngine: ReplayEngine,
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
    private val mtfContextProvider: MtfContextProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChartUiState())
    val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

    /** Replay state exposed separately for the overlay composable. */
    val replayState: StateFlow<ReplayState> = replayEngine.state

    /** WebSocket connection state for the UI indicator. */
    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState

    private val symbolFlow = MutableStateFlow(ChartUiState().symbol)
    private val timeframeFlow = MutableStateFlow(ChartUiState().timeframe)

    init {
        observeMarket()
        observeWebSocketTicks()
        // Mirror WebSocket connection state into the UI state for the LIVE badge.
        webSocket.connectionState
            .onEach { cs -> _uiState.value = _uiState.value.copy(connectionState = cs) }
            .launchIn(viewModelScope)
        refresh()
    }

    // ========================================================================
    // MARKET DATA OBSERVATION
    // ========================================================================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMarket() {
        combine(symbolFlow, timeframeFlow) { symbol, tf -> symbol to tf }
            .flatMapLatest { (symbol, tf) -> repository.observeCandles(symbol, tf) }
            .onEach { candles ->
                processCandles(candles)
            }
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
     * Runs all analysis engines and updates the UI state atomically.
     */
    private fun processCandles(candles: List<com.foxtrader.app.domain.model.Candle>) {
        val ind = _uiState.value.indicators
        val structure = analyzeStructure(candles)

        // --- Line indicators (computed only when enabled) ---
        val emaShort = if (ind.ema && candles.size >= 20) TechnicalIndicators.calculateEMA(candles, 20) else null
        val emaLong = if (ind.ema && candles.size >= 50) TechnicalIndicators.calculateEMA(candles, 50) else null
        val vwap = if (ind.vwap && candles.isNotEmpty()) TechnicalIndicators.calculateVWAP(candles) else null

        val boll = if (ind.bollinger && candles.size >= 20) bollingerBands.calculate(candles) else null
        val st = if (ind.superTrend && candles.size >= 15) superTrend.calculate(candles) else null
        val psar = if (ind.parabolicSar && candles.size >= 2) parabolicSar.calculate(candles).sar else null

        // --- SMC analysis (computed only when its overlay is enabled) ---
        val orderBlocks = if (ind.orderBlocks) smcDetector.detectOrderBlocks(candles) else emptyList()
        val fairValueGaps = if (ind.fairValueGaps) smcDetector.detectFairValueGaps(candles) else emptyList()
        val liquidityPools = if (ind.liquidity) smcDetector.detectLiquidity(candles) else emptyList()
        val volumeProfile = if (ind.volumeProfile && candles.size >= 20) smcDetector.computeVolumeProfile(candles) else null
        val sessions = if (ind.sessions) sessionDetector.detectSessions(candles) else emptyList()

        _uiState.value = _uiState.value.copy(
            candles = candles,
            bias = structure.bias,
            structureBreaks = if (ind.structure) structure.breaks else emptyList(),
            emaShort = emaShort,
            emaLong = emaLong,
            bollingerUpper = boll?.upper,
            bollingerMiddle = boll?.middle,
            bollingerLower = boll?.lower,
            superTrendValues = st?.values,
            superTrendDir = st?.direction,
            parabolicSar = psar,
            vwap = vwap,
            orderBlocks = orderBlocks,
            fairValueGaps = fairValueGaps,
            liquidityPools = liquidityPools,
            volumeProfile = volumeProfile,
            sessions = sessions,
            isLoading = candles.isEmpty() && _uiState.value.error == null,
        )

        // --- AI Decision Engine (run after analysis is ready) ---
        runAiDecision(candles)
    }

    // ========================================================================
    // AI DECISION ENGINE
    // ========================================================================

    /**
     * Run the multi-agent reasoning pipeline and update the UI with the result.
     * Only runs when there's sufficient data (>=50 candles) and throttled to
     * avoid burning CPU on every tick.
     */
    private fun runAiDecision(candles: List<com.foxtrader.app.domain.model.Candle>) {
        if (candles.size < 50) {
            _uiState.value = _uiState.value.copy(aiDecision = null)
            return
        }
        // Fetch HTF context asynchronously; the AI pipeline runs off-main.
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
            val orchestratorResult = orchestrator.analyze(context)
            val decision = decisionEngine.evaluate(orchestratorResult)
            _uiState.value = _uiState.value.copy(aiDecision = decision)
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
        drawingEngine.placePoint(point)
        _uiState.value = _uiState.value.copy(
            drawingMode = drawingEngine.mode,
            drawings = drawingEngine.getVisibleDrawings(),
        )
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
        _uiState.value = _uiState.value.copy(drawings = emptyList())
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
