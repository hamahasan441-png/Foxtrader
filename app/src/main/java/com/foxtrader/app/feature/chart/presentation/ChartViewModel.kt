package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.DrawingRepository
import com.foxtrader.app.domain.repository.AlertRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.repository.WatchlistRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MarketExplanationEngine
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.chart.ChartLayout
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import com.foxtrader.app.domain.usecase.chart.ComputeIndicatorsUseCase
import com.foxtrader.app.domain.usecase.chart.MultiChartManager
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.domain.usecase.performance.AdaptiveQualityController
import com.foxtrader.app.domain.usecase.performance.PerformanceProfiler
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.replay.ReplayEngine
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.strategies.StrategyLibrary
import com.foxtrader.app.feature.chart.presentation.components.ChartPerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Chart screen ViewModel - thin orchestrator delegating to focused controllers.
 */
@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val computeIndicators: ComputeIndicatorsUseCase,
    private val webSocket: MarketWebSocket,
    private val multiChartManager: MultiChartManager,
    val drawingEngine: DrawingEngine,
    val replayEngine: ReplayEngine,
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val marketExplanationEngine: MarketExplanationEngine,
    private val confluenceEngine: ConfluenceEngine,
    private val aiAlertService: AiAlertService,
    private val alertDispatcher: AlertDispatcher,
    private val alertRepository: AlertRepository,
    private val watchlistRepository: WatchlistRepository,
    private val drawingRepository: DrawingRepository,
    private val appPreferences: AppPreferences,
    private val strategyLibrary: StrategyLibrary,
    private val backtestEngine: BacktestEngine,
    profiler: PerformanceProfiler,
    qualityController: AdaptiveQualityController,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val performanceMonitor = ChartPerformanceMonitor(profiler, qualityController)

    private val _uiState = MutableStateFlow(
        ChartUiState(timeframe = appPreferences.defaultTimeframe.value)
    )
    val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

    val multiChartState: StateFlow<MultiChartUiState> get() = multiChartController.multiChartState

    val replayState: StateFlow<ReplayState> = replayEngine.state
    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState
    val unreadAlertCount: StateFlow<Int> = alertRepository.observeUnacknowledgedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Observable primary-chart viewport. Emitted on every pan/zoom/fling frame
     * so oscillator sub-panels (RSI, MACD) can track the main chart's visible
     * window in real time. Only the sub-panel container collects this, so the
     * main chart's own render loop is unaffected.
     */
    private val _primaryViewport = MutableStateFlow<ChartViewportState?>(null)
    val primaryViewport: StateFlow<ChartViewportState?> = _primaryViewport.asStateFlow()

    // --- Controllers (plain classes, NOT @Inject) ---
    private val dataController = ChartDataController(
        repository = repository,
        webSocket = webSocket,
        scope = viewModelScope,
        onMergedCandlesChanged = { source, preferIncremental ->
            processCandles(source, preferIncremental)
        },
        onUpsertTick = { symbol, timeframe, candle ->
            repository.upsertCandle(symbol, timeframe, candle)
        },
    )

    private val indicatorCoordinator = ChartIndicatorCoordinator(
        analyzeStructure = analyzeStructure,
        computeIndicators = computeIndicators,
        marketExplanationEngine = marketExplanationEngine,
        defaultDispatcher = defaultDispatcher,
    )

    private val aiCoordinator = ChartAiCoordinator(
        orchestrator = orchestrator,
        decisionEngine = decisionEngine,
        mtfContextProvider = mtfContextProvider,
        marketExplanationEngine = marketExplanationEngine,
        confluenceEngine = confluenceEngine,
        aiAlertService = aiAlertService,
        alertDispatcher = alertDispatcher,
        defaultDispatcher = defaultDispatcher,
        scope = viewModelScope,
    )

    private val multiChartController = ChartMultiChartController(
        multiChartManager = multiChartManager,
        repository = repository,
        analyzeStructure = analyzeStructure,
        appPreferences = appPreferences,
        defaultDispatcher = defaultDispatcher,
        scope = viewModelScope,
        symbolFlow = { dataController.symbolFlow.value },
        timeframeFlow = { dataController.timeframeFlow.value },
        onUiSyncedCrosshairChange = { ts ->
            _uiState.value = _uiState.value.copy(syncedCrosshairTimestamp = ts)
        },
    )

    private val drawingController = ChartDrawingController(
        drawingEngine = drawingEngine,
        drawingRepository = drawingRepository,
        scope = viewModelScope,
        symbolAccessor = { dataController.symbolFlow.value },
        timeframeAccessor = { dataController.timeframeFlow.value },
    )

    private val strategyController = ChartStrategyController(
        strategyLibrary = strategyLibrary,
        backtestEngine = backtestEngine,
        scope = viewModelScope,
        defaultDispatcher = defaultDispatcher,
        onComputing = { computing ->
            _uiState.value = _uiState.value.copy(strategyComputing = computing)
        },
        onResult = { result ->
            _uiState.value = _uiState.value.copy(
                strategyTrades = result.trades.toPersistentList(),
                strategyMetrics = result.metrics,
                liveSignal = result.liveSignal,
                strategyNote = result.note,
            )
        },
    )

    init {
        dataController.symbolFlow.value = _uiState.value.symbol
        dataController.timeframeFlow.value = _uiState.value.timeframe
        observeWatchlist()
        dataController.observeMarket()
        observeDrawings()
        dataController.observeWebSocketTicks()
        multiChartController.observePersistedMultiChartPreferences()
        multiChartController.syncMultiChartPanelsToPrimary()
        webSocket.connectionState
            .onEach { cs -> _uiState.value = _uiState.value.copy(connectionState = cs) }
            .launchIn(viewModelScope)
        refresh()
    }

    // ========================================================================
    // INTERNAL PIPELINE WIRING
    // ========================================================================
    private suspend fun processCandles(source: CandleSource, preferIncremental: Boolean) {
        val candles = dataController.mergedVisibleCandles
        if (candles.isEmpty()) return // Safety: skip processing when data is being cleared
        val ind = _uiState.value.indicators
        val symbol = dataController.symbolFlow.value
        val timeframe = dataController.timeframeFlow.value

        val c = indicatorCoordinator.processCandles(
            candles = candles, source = source, toggles = ind,
            symbol = symbol, timeframe = timeframe, preferIncremental = preferIncremental,
        )

        _uiState.value = _uiState.value.copy(
            candles = candles.asCandleSeries(), dataSource = source, bias = c.bias,
            structureBreaks = if (ind.structure) c.structureBreaks.toPersistentList() else persistentListOf(),
            emaShort = c.overlays.emaShort.asImmutableDoubleSeries(),
            emaLong = c.overlays.emaLong.asImmutableDoubleSeries(),
            bollingerUpper = c.overlays.bollingerUpper.asImmutableDoubleSeries(),
            bollingerMiddle = c.overlays.bollingerMiddle.asImmutableDoubleSeries(),
            bollingerLower = c.overlays.bollingerLower.asImmutableDoubleSeries(),
            superTrendValues = c.overlays.superTrendValues.asImmutableDoubleSeries(),
            superTrendDir = c.overlays.superTrendDir.asImmutableIntSeries(),
            parabolicSar = c.overlays.parabolicSar.asImmutableDoubleSeries(),
            vwap = c.overlays.vwap.asImmutableDoubleSeries(),
            ichimokuTenkan = c.overlays.ichimokuTenkan.asImmutableDoubleSeries(),
            ichimokuKijun = c.overlays.ichimokuKijun.asImmutableDoubleSeries(),
            ichimokuSenkouA = c.overlays.ichimokuSenkouA.asImmutableDoubleSeries(),
            ichimokuSenkouB = c.overlays.ichimokuSenkouB.asImmutableDoubleSeries(),
            ichimokuChikou = c.overlays.ichimokuChikou.asImmutableDoubleSeries(),
            rsiValues = c.overlays.rsi.asImmutableDoubleSeries(),
            macdLine = c.overlays.macdLine.asImmutableDoubleSeries(),
            macdSignal = c.overlays.macdSignal.asImmutableDoubleSeries(),
            macdHistogram = c.overlays.macdHistogram.asImmutableDoubleSeries(),
            orderBlocks = c.overlays.orderBlocks.toPersistentList(),
            fairValueGaps = c.overlays.fairValueGaps.toPersistentList(),
            liquidityPools = c.overlays.liquidityPools.toPersistentList(),
            volumeProfile = c.overlays.volumeProfile,
            marketProfile = c.overlays.marketProfile,
            supportResistanceZones = c.overlays.supportResistanceZones.toPersistentList(),
            autoFibLevels = c.overlays.autoFibLevels.toPersistentList(),
            autoFibDirection = c.overlays.autoFibDirection,
            autoFibSwingHigh = c.overlays.autoFibSwingHigh,
            autoFibSwingLow = c.overlays.autoFibSwingLow,
            sessions = c.overlays.sessions.toPersistentList(),
            marketExplanation = c.marketExplanation,
            confluence = if (ind.confluence) _uiState.value.confluence else null,
            isLoading = candles.isEmpty() && _uiState.value.error == null,
        )

        aiCoordinator.runAiDecision(
            candles = candles, dataSource = source, symbol = symbol, timeframe = timeframe,
            confluenceEnabled = ind.confluence,
            symbolFlow = { dataController.symbolFlow.value },
            timeframeFlow = { dataController.timeframeFlow.value },
        ) { result ->
            _uiState.value = _uiState.value.copy(
                aiDecision = result.decision,
                marketExplanation = result.marketExplanation ?: _uiState.value.marketExplanation,
                confluence = result.confluence,
            )
        }

        // Recompute the strategy backtest/signal overlay when active. The
        // controller is internally debounced (fingerprinted), so this is a
        // no-op unless a bar actually opened/closed.
        if (_uiState.value.signalsEnabled) {
            strategyController.compute(
                candles = candles,
                symbol = symbol,
                timeframe = timeframe,
                strategy = _uiState.value.selectedStrategy,
            )
        }
    }

    // ========================================================================
    // SIMPLE OBSERVATION / UI ACTIONS
    // ========================================================================
    private fun observeWatchlist() {
        viewModelScope.launch { watchlistRepository.ensureSeeded() }
        watchlistRepository.observeWatchlists()
            .onEach { lists ->
                val active = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    availableSymbols = active?.symbolNames.orEmpty().toPersistentList(),
                    activeWatchlistId = active?.id,
                )
            }
            .launchIn(viewModelScope)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeDrawings() {
        combine(dataController.symbolFlow, dataController.timeframeFlow) { s, tf -> s to tf }
            .flatMapLatest { (symbol, tf) -> drawingRepository.observe(symbol, tf) }
            .onEach { drawings -> _uiState.value = _uiState.value.copy(drawings = drawings.toPersistentList()) }
            .launchIn(viewModelScope)
    }

    fun openCalculator() { _uiState.value = _uiState.value.copy(showCalculator = true) }
    fun closeCalculator() { _uiState.value = _uiState.value.copy(showCalculator = false) }

    fun addSymbolToWatchlist(symbol: String) {
        val listId = _uiState.value.activeWatchlistId ?: return
        viewModelScope.launch { watchlistRepository.addSymbol(listId, symbol) }
    }

    fun removeSymbolFromWatchlist(symbol: String) {
        val listId = _uiState.value.activeWatchlistId ?: return
        viewModelScope.launch { watchlistRepository.removeSymbol(listId, symbol) }
    }

    fun openSymbolPicker() { _uiState.value = _uiState.value.copy(showSymbolPicker = true) }
    fun closeSymbolPicker() { _uiState.value = _uiState.value.copy(showSymbolPicker = false) }

    fun toggleIndicatorPanel() {
        _uiState.value = _uiState.value.copy(showIndicatorPanel = !_uiState.value.showIndicatorPanel)
    }

    // ========================================================================
    // STRATEGY SIGNALS + BACKTEST-ON-CHART
    // ========================================================================
    fun toggleSignals() {
        val enabled = !_uiState.value.signalsEnabled
        _uiState.value = _uiState.value.copy(signalsEnabled = enabled)
        if (enabled) {
            recomputeStrategy(force = true)
        } else {
            // Clear the overlay and cancel any in-flight computation.
            strategyController.cancel()
            strategyController.invalidate()
            _uiState.value = _uiState.value.copy(
                strategyTrades = persistentListOf(),
                strategyMetrics = null,
                liveSignal = null,
                strategyNote = null,
                strategyComputing = false,
            )
        }
    }

    fun toggleStrategyPicker() {
        _uiState.value = _uiState.value.copy(showStrategyPicker = !_uiState.value.showStrategyPicker)
    }

    fun selectStrategy(type: StrategyType) {
        if (type == _uiState.value.selectedStrategy && _uiState.value.strategyMetrics != null) {
            _uiState.value = _uiState.value.copy(showStrategyPicker = false)
            return
        }
        _uiState.value = _uiState.value.copy(
            selectedStrategy = type,
            showStrategyPicker = false,
            signalsEnabled = true,
        )
        recomputeStrategy(force = true)
    }

    private fun recomputeStrategy(force: Boolean) {
        val candles = dataController.mergedVisibleCandles
        if (candles.isEmpty()) return
        strategyController.compute(
            candles = candles,
            symbol = dataController.symbolFlow.value,
            timeframe = dataController.timeframeFlow.value,
            strategy = _uiState.value.selectedStrategy,
            force = force,
        )
    }

    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
        val current = _uiState.value.indicators
        val updated = transform(current)
        if (current.confluence != updated.confluence) { aiCoordinator.lastAiCandlesHash = 0L }
        _uiState.value = _uiState.value.copy(
            indicators = updated,
            confluence = if (updated.confluence) _uiState.value.confluence else null,
        )
        viewModelScope.launch {
            try {
                dataController.processMergedCandles(preferIncremental = false)
            } catch (_: Exception) {
                // Swallow concurrent modification exceptions during indicator toggle.
                // The next data emission will trigger a successful recompute.
            }
        }
    }

    // ========================================================================
    // DELEGATED PUBLIC API
    // ========================================================================
    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        dataController.refresh { e -> _uiState.value = _uiState.value.copy(isLoading = false, error = e) }
        multiChartController.refreshMultiChartPanels()
    }

    fun onSymbolChange(symbol: String) {
        dataController.onSymbolChange(symbol)
        resetChartContext(symbol = symbol, clearSymbolPicker = true)
        aiCoordinator.resetCooldowns()
        multiChartController.syncMultiChartPanelsToPrimary()
        refresh()
        if (_uiState.value.liveEnabled) dataController.resubscribeLive()
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        dataController.onTimeframeChange(timeframe)
        resetChartContext(timeframe = timeframe)
        multiChartController.syncMultiChartPanelsToPrimary()
        refresh()
        if (_uiState.value.liveEnabled) dataController.resubscribeLive()
    }

    private fun resetChartContext(
        symbol: String = dataController.symbolFlow.value,
        timeframe: Timeframe = dataController.timeframeFlow.value,
        clearSymbolPicker: Boolean = false,
    ) {
        aiCoordinator.lastAiCandlesHash = 0L
        dataController.resetPrimaryChartContext()
        multiChartController.resetPrimaryViewportState()
        _primaryViewport.value = null
        // Drop stale strategy overlay from the previous symbol/timeframe.
        strategyController.cancel()
        strategyController.invalidate()
        _uiState.value = _uiState.value.copy(
            strategyTrades = persistentListOf(),
            strategyMetrics = null,
            liveSignal = null,
            strategyNote = null,
            strategyComputing = false,
        )
        _uiState.value = _uiState.value.copy(
            symbol = symbol, timeframe = timeframe,
            candles = CandleSeries.EMPTY, dataSource = CandleSource.CACHED,
            showSymbolPicker = if (clearSymbolPicker) false else _uiState.value.showSymbolPicker,
            aiDecision = null, confluence = null,
            isLoading = true, isLoadingOlder = false, historyEndReached = false,
        )
    }

    fun loadOlderHistory() {
        dataController.loadOlderHistory { loading, endReached, error ->
            _uiState.value = _uiState.value.copy(
                isLoadingOlder = loading,
                historyEndReached = endReached,
                error = error,
            )
        }
    }

    fun toggleLive() {
        val enabled = !_uiState.value.liveEnabled
        _uiState.value = _uiState.value.copy(liveEnabled = enabled)
        dataController.toggleLive(!enabled)
    }

    fun connectLive() = dataController.connectLive()
    fun disconnectLive() = dataController.disconnectLive()

    // --- Multi-chart delegates ---
    fun setMultiChartLayout(layout: ChartLayout) = multiChartController.setMultiChartLayout(layout)
    fun addMultiChartPanel() = multiChartController.addMultiChartPanel()
    fun removeMultiChartPanel(panelId: String) = multiChartController.removeMultiChartPanel(panelId)
    fun moveMultiChartPanelToIndex(panelId: String, targetIndex: Int) = multiChartController.moveMultiChartPanelToIndex(panelId, targetIndex)
    fun toggleMultiChartLinking() = multiChartController.toggleMultiChartLinking()
    fun toggleMultiChartSymbolLink() = multiChartController.toggleMultiChartSymbolLink()
    fun toggleMultiChartTimeframeLink() = multiChartController.toggleMultiChartTimeframeLink()
    fun toggleMultiChartCrosshairSync() = multiChartController.toggleMultiChartCrosshairSync()
    fun setActiveMultiChartPanel(panelId: String) = multiChartController.setActiveMultiChartPanel(panelId)
    fun setMultiChartPanelSymbol(panelId: String, symbol: String) = multiChartController.setMultiChartPanelSymbol(panelId, symbol)
    fun setMultiChartPanelTimeframe(panelId: String, timeframe: Timeframe) = multiChartController.setMultiChartPanelTimeframe(panelId, timeframe)
    fun resetMultiChartPanelToPrimary(panelId: String) = multiChartController.resetMultiChartPanelToPrimary(panelId)
    fun onPrimaryCrosshairTimestampChange(timestamp: Long?) = multiChartController.onPrimaryCrosshairTimestampChange(timestamp)
    fun onMultiChartPanelCrosshairTimestampChange(panelId: String, timestamp: Long?) = multiChartController.onMultiChartPanelCrosshairTimestampChange(panelId, timestamp)
    fun currentPrimaryViewportState(): ChartViewportState? = multiChartController.currentPrimaryViewportState()
    fun currentMultiChartPanelViewportState(panelId: String): ChartViewportState? = multiChartController.currentMultiChartPanelViewportState(panelId)
    fun onPrimaryViewportStateChange(state: ChartViewportState) {
        _primaryViewport.value = state
        multiChartController.onPrimaryViewportStateChange(state)
    }
    fun onMultiChartPanelViewportStateChange(panelId: String, state: ChartViewportState) = multiChartController.onMultiChartPanelViewportStateChange(panelId, state)

    // --- Drawing delegates ---
    fun startDrawing(type: DrawingToolType) {
        val s = drawingController.startDrawing(type)
        _uiState.value = _uiState.value.copy(drawingMode = s.drawingMode, activeTool = s.activeTool, showDrawingToolbar = s.showDrawingToolbar)
    }

    fun placeDrawingPoint(index: Float, price: Double) {
        val s = drawingController.placeDrawingPoint(index, price)
        _uiState.value = _uiState.value.copy(drawingMode = s.drawingMode, drawings = s.drawings)
    }

    fun cancelDrawing() {
        val s = drawingController.cancelDrawing()
        _uiState.value = _uiState.value.copy(drawingMode = s.drawingMode, activeTool = s.activeTool)
    }

    fun clearAllDrawings() = drawingController.clearAllDrawings()

    fun toggleDrawingToolbar() {
        val s = drawingController.toggleDrawingToolbar(_uiState.value.showDrawingToolbar)
        _uiState.value = _uiState.value.copy(drawingMode = s.drawingMode, activeTool = s.activeTool, showDrawingToolbar = s.showDrawingToolbar)
    }

    // --- Replay delegates ---
    fun startReplay(startAt: Int = 50) = replayEngine.start(_uiState.value.candles, startAt)
    fun stopReplay() = replayEngine.stop()
    fun toggleReplayPlayPause() = replayEngine.togglePlayPause()
    fun replayStepForward() = replayEngine.stepForward()
    fun replayStepBackward() = replayEngine.stepBackward()
    fun replayCycleSpeed() = replayEngine.cycleSpeed()

    // ========================================================================
    // LIFECYCLE
    // ========================================================================
    override fun onCleared() {
        super.onCleared()
        multiChartController.cancelAllPanelJobs()
        strategyController.cancel()
        // BUGFIX: viewModelScope is already cancelled when onCleared() runs,
        // so launching into it would never execute. Use runBlocking for the
        // short, non-blocking disconnect call to prevent the websocket from
        // leaking the Service context (SystemJobService leak in crash report).
        kotlinx.coroutines.runBlocking {
            try { webSocket.disconnectAll() } catch (_: Exception) { }
        }
        replayEngine.stop()
    }
}
