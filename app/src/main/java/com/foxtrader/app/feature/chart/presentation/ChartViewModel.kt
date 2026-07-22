package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
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
    val drawingEngine: DrawingEngine,
    val replayEngine: ReplayEngine,
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
                    val current = _uiState.value.candles.toMutableList()
                    val lastTs = current.lastOrNull()?.timestamp
                    when {
                        // Same bar (by open time): replace the forming/closing candle in place.
                        lastTs != null && lastTs == tick.candle.timestamp -> {
                            current[current.lastIndex] = tick.candle
                        }
                        // New bar (later timestamp): append it.
                        lastTs == null || tick.candle.timestamp > lastTs -> {
                            current.add(tick.candle)
                        }
                        // Stale/out-of-order tick (older than last bar): ignore.
                        else -> return@onEach
                    }
                    processCandles(current)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Central candle processing pipeline.
     * Runs all analysis engines and updates the UI state atomically.
     */
    private fun processCandles(candles: List<com.foxtrader.app.domain.model.Candle>) {
        val structure = analyzeStructure(candles)
        val emaShort = if (candles.size >= 20) TechnicalIndicators.calculateEMA(candles, 20) else null
        val emaLong = if (candles.size >= 50) TechnicalIndicators.calculateEMA(candles, 50) else null

        // SMC analysis
        val orderBlocks = smcDetector.detectOrderBlocks(candles)
        val fairValueGaps = smcDetector.detectFairValueGaps(candles)
        val liquidityPools = smcDetector.detectLiquidity(candles)
        val volumeProfile = if (candles.size >= 20) smcDetector.computeVolumeProfile(candles) else null

        // Session detection
        val sessions = sessionDetector.detectSessions(candles)

        _uiState.value = _uiState.value.copy(
            candles = candles,
            bias = structure.bias,
            structureBreaks = structure.breaks,
            emaShort = emaShort,
            emaLong = emaLong,
            orderBlocks = orderBlocks,
            fairValueGaps = fairValueGaps,
            liquidityPools = liquidityPools,
            volumeProfile = volumeProfile,
            sessions = sessions,
            isLoading = candles.isEmpty() && _uiState.value.error == null,
        )
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
        _uiState.value = _uiState.value.copy(symbol = symbol)
        refresh()
        // Switch WebSocket subscription
        viewModelScope.launch {
            webSocket.disconnectAll()
            webSocket.subscribe(symbol, timeframeFlow.value)
        }
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        timeframeFlow.value = timeframe
        _uiState.value = _uiState.value.copy(timeframe = timeframe)
        refresh()
        // Switch WebSocket subscription
        viewModelScope.launch {
            webSocket.disconnectAll()
            webSocket.subscribe(symbolFlow.value, timeframe)
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
