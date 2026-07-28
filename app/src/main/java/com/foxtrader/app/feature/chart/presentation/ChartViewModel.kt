package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.domain.model.SourcedCandles
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
import com.foxtrader.app.domain.usecase.preferences.PersistedCrosshairSource
import com.foxtrader.app.domain.usecase.preferences.PersistedMultiChartPanel
import com.foxtrader.app.domain.usecase.preferences.PersistedMultiChartState
import com.foxtrader.app.domain.usecase.replay.ReplayEngine
import com.foxtrader.app.feature.chart.presentation.components.ChartPerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.collections.AbstractList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
    profiler: PerformanceProfiler,
    qualityController: AdaptiveQualityController,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * Render-loop instrumentation for the chart (DEVELOPMENT.md §4.14).
     *
     * Owned by the ViewModel so it survives configuration changes: rotating the
     * device keeps the accumulated frame history and the current adaptive
     * quality level instead of resetting the chart to ULTRA mid-interaction.
     */
    val performanceMonitor = ChartPerformanceMonitor(profiler, qualityController)

    private val _uiState = MutableStateFlow(
        ChartUiState(timeframe = appPreferences.defaultTimeframe.value)
    )
    val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

    private val _multiChartState = MutableStateFlow(MultiChartUiState())
    val multiChartState: StateFlow<MultiChartUiState> = _multiChartState.asStateFlow()

    /** Replay state exposed separately for the overlay composable. */
    val replayState: StateFlow<ReplayState> = replayEngine.state

    /** WebSocket connection state for the UI indicator. */
    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState

    /** Unread alert count for the chart's alerts-bell badge. */
    val unreadAlertCount: StateFlow<Int> = alertRepository.observeUnacknowledgedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val symbolFlow = MutableStateFlow(_uiState.value.symbol)
    private val timeframeFlow = MutableStateFlow(_uiState.value.timeframe)

    /**
     * Fingerprint of the last candle series passed to the AI pipeline.
     * Used to skip re-running the expensive multi-agent analysis when the
     * data has not changed (e.g. rapid indicator-toggle recomputations).
     */
    private var lastAiCandlesHash: Long = 0L

    /** Latest hot-cache series observed from Room for the active chart. */
    private var currentObservedCandles: SourcedCandles = SourcedCandles.EMPTY

    /** Older pages kept only in-memory so the Room hot cache can stay bounded. */
    private val prependedHistory = mutableListOf<Candle>()
    private var prependedHistorySnapshot: List<Candle> = emptyList()
    private var prependedHistorySource: CandleSource = CandleSource.CACHED
    private var mergedVisibleCandles: List<Candle> = emptyList()

    private val multiChartPanelJobs = linkedMapOf<String, Job>()
    private val multiChartPanels = linkedMapOf<String, MultiChartPanelUiState>()
    private var multiChartLinkedToPrimary: Boolean = true
    private var multiChartSymbolLinkEnabled: Boolean = true
    private var multiChartTimeframeLinkEnabled: Boolean = true
    private var primarySyncedCrosshairTimestamp: Long? = null
    private val panelSyncedCrosshairTimestamps = linkedMapOf<String, Long?>()
    private val panelPublishedFingerprints = linkedMapOf<String, String>()
    private var primaryViewportState: ChartViewportState? = null
    private val panelViewportStates = linkedMapOf<String, ChartViewportState?>()
    private var lastCrosshairSource: PersistedCrosshairSource = PersistedCrosshairSource.NONE
    private var lastCrosshairSourcePanelId: String? = null
    private var persistMultiChartJob: Job? = null
    private var lastProcessedSnapshot: ProcessedSnapshot? = null
    private var hasRestoredMultiChartPreferences: Boolean = false

    init {
        observeWatchlist()
        observeMarket()
        observeDrawings()
        observeWebSocketTicks()
        observePersistedMultiChartPreferences()
        syncMultiChartPanelsToPrimary()
        // Mirror WebSocket connection state into the UI state for the LIVE badge.
        webSocket.connectionState
            .onEach { cs -> _uiState.value = _uiState.value.copy(connectionState = cs) }
            .launchIn(viewModelScope)
        refresh()
    }

    /**
     * Track the default watchlist so the symbol picker reflects the user's own
     * instruments rather than a compiled-in list.
     */
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

    fun openCalculator() {
        _uiState.value = _uiState.value.copy(showCalculator = true)
    }

    fun closeCalculator() {
        _uiState.value = _uiState.value.copy(showCalculator = false)
    }

    /** Add a symbol to the active watchlist (normalised by the repository). */
    fun addSymbolToWatchlist(symbol: String) {
        val listId = _uiState.value.activeWatchlistId ?: return
        viewModelScope.launch { watchlistRepository.addSymbol(listId, symbol) }
    }

    fun removeSymbolFromWatchlist(symbol: String) {
        val listId = _uiState.value.activeWatchlistId ?: return
        viewModelScope.launch { watchlistRepository.removeSymbol(listId, symbol) }
    }

    /** Observe persisted drawings for the current symbol/timeframe. */
    private fun observeDrawings() {
        combine(symbolFlow, timeframeFlow) { s, tf -> s to tf }
            .flatMapLatest { (symbol, tf) -> drawingRepository.observe(symbol, tf) }
            .onEach { drawings -> _uiState.value = _uiState.value.copy(drawings = drawings.toPersistentList()) }
            .launchIn(viewModelScope)
    }

    private fun observePersistedMultiChartPreferences() {
        appPreferences.multiChartPreferences
            .onEach { persisted ->
                if (persisted == null || hasRestoredMultiChartPreferences) return@onEach
                restorePersistedMultiChartPreferences(persisted)
                hasRestoredMultiChartPreferences = true
            }
            .launchIn(viewModelScope)
    }

    private fun restorePersistedMultiChartPreferences(state: PersistedMultiChartState) {
        multiChartLinkedToPrimary = state.linkedToPrimary
        multiChartSymbolLinkEnabled = state.symbolLinkEnabled
        multiChartTimeframeLinkEnabled = state.timeframeLinkEnabled
        primaryViewportState = state.primaryViewport
        panelViewportStates.clear()
        multiChartManager.restoreState(
            layout = state.layout,
            crosshairSync = state.crosshairSyncEnabled,
            panels = state.panelSeeds(),
            activePanelIndex = state.activePanelIndex,
        )
        multiChartManager.getPanels().forEachIndexed { index, panel ->
            panelViewportStates[panel.id] = state.panels.getOrNull(index)?.viewport
        }
        clearSyncedCrosshairs()
        restorePersistedCrosshairState(state)
        if (multiChartLinkedToPrimary) {
            syncMultiChartPanelsToPrimary()
        } else {
            refreshMultiChartPanels()
        }
    }

    private fun restorePersistedCrosshairState(state: PersistedMultiChartState) {
        val timestamp = state.syncedCrosshairTimestamp ?: return
        if (!state.crosshairSyncEnabled) return
        when (state.syncedCrosshairSource) {
            PersistedCrosshairSource.PRIMARY -> {
                lastCrosshairSource = PersistedCrosshairSource.PRIMARY
                multiChartManager.getPanels().forEach { panel ->
                    panelSyncedCrosshairTimestamps[panel.id] = timestamp
                }
            }
            PersistedCrosshairSource.PANEL -> {
                val sourcePanel = multiChartManager.getPanels().getOrNull(
                    state.syncedCrosshairPanelIndex ?: return
                ) ?: return
                lastCrosshairSource = PersistedCrosshairSource.PANEL
                lastCrosshairSourcePanelId = sourcePanel.id
                primarySyncedCrosshairTimestamp = timestamp
                panelSyncedCrosshairTimestamps.putAll(
                    multiChartManager.syncCrosshairTime(sourcePanel.id, timestamp)
                )
            }
            PersistedCrosshairSource.NONE -> Unit
        }
    }

    private fun persistMultiChartPreferences() {
        persistMultiChartJob?.cancel()
        persistMultiChartJob = viewModelScope.launch {
            delay(PERSIST_MULTI_CHART_DEBOUNCE_MS)
            val panels = multiChartManager.getPanels()
            val activeIndex = panels.indexOfFirst { it.isActive }.coerceAtLeast(0)
            val persistedCrosshairTimestamp = when (lastCrosshairSource) {
                PersistedCrosshairSource.PRIMARY -> panelSyncedCrosshairTimestamps.values.firstOrNull()
                PersistedCrosshairSource.PANEL -> primarySyncedCrosshairTimestamp
                PersistedCrosshairSource.NONE -> null
            }
            val persistedCrosshairPanelIndex = if (lastCrosshairSource == PersistedCrosshairSource.PANEL) {
                panels.indexOfFirst { it.id == lastCrosshairSourcePanelId }.takeIf { it >= 0 }
            } else null
            appPreferences.setMultiChartPreferences(
                PersistedMultiChartState(
                    layout = multiChartManager.getLayout(),
                    linkedToPrimary = multiChartLinkedToPrimary,
                    symbolLinkEnabled = multiChartSymbolLinkEnabled,
                    timeframeLinkEnabled = multiChartTimeframeLinkEnabled,
                    crosshairSyncEnabled = multiChartManager.isCrosshairSynced(),
                    activePanelIndex = activeIndex,
                    primaryViewport = primaryViewportState,
                    syncedCrosshairTimestamp = persistedCrosshairTimestamp,
                    syncedCrosshairSource = lastCrosshairSource,
                    syncedCrosshairPanelIndex = persistedCrosshairPanelIndex,
                    panels = panels.map { panel ->
                        PersistedMultiChartPanel(
                            symbol = panel.symbol,
                            timeframe = panel.timeframe,
                            viewport = panelViewportStates[panel.id],
                        )
                    },
                )
            )
        }
    }

    fun currentPrimaryViewportState(): ChartViewportState? = primaryViewportState

    fun currentMultiChartPanelViewportState(panelId: String): ChartViewportState? =
        panelViewportStates[panelId]

    fun onPrimaryViewportStateChange(state: ChartViewportState) {
        primaryViewportState = state
        persistMultiChartPreferences()
    }

    fun onMultiChartPanelViewportStateChange(panelId: String, state: ChartViewportState) {
        panelViewportStates[panelId] = state
        persistMultiChartPreferences()
    }

    // ========================================================================
    // MARKET DATA OBSERVATION
    // ========================================================================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMarket() {
        combine(symbolFlow, timeframeFlow) { symbol, tf -> symbol to tf }
            .flatMapLatest { (symbol, tf) -> repository.observeSourcedCandles(symbol, tf) }
            // Deduplicate: suppress reanalysis only when neither the bar count nor
            // the latest bar's full OHLCV payload changed. Live feeds may update
            // high/low/volume without changing close, and those changes still need
            // a chart/SMC recalculation. Provenance is part of the key so a
            // synthetic->real transition always re-runs the pipeline.
            .distinctUntilChangedBy { sourced ->
                val list = sourced.candles
                val last = list.lastOrNull()
                "${sourced.source}:${list.size}:${last?.timestamp}:${last?.open}:" +
                    "${last?.high}:${last?.low}:${last?.close}:${last?.volume}"
            }
            .onEach { sourced ->
                currentObservedCandles = sourced
                rebuildMergedVisibleCandles()
                viewModelScope.launch {
                    processMergedCandles(
                        sourceHint = sourced.source,
                        preferIncremental = true,
                    )
                }
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

    private suspend fun processMergedCandles(
        sourceHint: CandleSource = currentObservedCandles.source,
        preferIncremental: Boolean = false,
    ) {
        val mergedSource = CandleSource.worstOf(
            buildList {
                add(sourceHint)
                if (prependedHistorySnapshot.isNotEmpty()) add(prependedHistorySource)
            }
        )
        processCandles(mergedVisibleCandles, mergedSource, preferIncremental)
    }

    private fun rebuildMergedVisibleCandles() {
        val observed = currentObservedCandles.candles
        mergedVisibleCandles = when {
            prependedHistorySnapshot.isEmpty() -> observed
            observed.isEmpty() -> prependedHistorySnapshot
            else -> ConcatenatedCandleList(prependedHistorySnapshot, observed)
        }
    }

    private fun clearPrependedHistory() {
        prependedHistory.clear()
        prependedHistorySnapshot = emptyList()
        prependedHistorySource = CandleSource.CACHED
        lastProcessedSnapshot = null
        rebuildMergedVisibleCandles()
        _uiState.value = _uiState.value.copy(
            isLoadingOlder = false,
            historyEndReached = false,
            error = null,
        )
    }

    private fun clearSyncedCrosshairs() {
        primarySyncedCrosshairTimestamp = null
        panelSyncedCrosshairTimestamps.clear()
        lastCrosshairSource = PersistedCrosshairSource.NONE
        lastCrosshairSourcePanelId = null
        _uiState.value = _uiState.value.copy(syncedCrosshairTimestamp = null)
    }

    private fun clearPanelViewportIfContextChanged(
        panelId: String,
        previousSymbol: String,
        previousTimeframe: Timeframe,
        newSymbol: String,
        newTimeframe: Timeframe,
    ) {
        if (previousSymbol != newSymbol || previousTimeframe != newTimeframe) {
            panelViewportStates[panelId] = null
        }
    }

    fun loadOlderHistory() {
        val beforeTimestamp = prependedHistory.firstOrNull()?.timestamp
            ?: currentObservedCandles.candles.firstOrNull()?.timestamp
            ?: return
        if (_uiState.value.isLoadingOlder || _uiState.value.historyEndReached) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOlder = true, error = null)
            repository.loadOlderCandles(
                symbol = symbolFlow.value,
                timeframe = timeframeFlow.value,
                beforeTimestamp = beforeTimestamp,
                limit = HISTORY_PAGE_SIZE,
            ).onSuccess { page ->
                val existingTimestamps = HashSet<Long>(prependedHistory.size + currentObservedCandles.candles.size).apply {
                    prependedHistory.forEach { add(it.timestamp) }
                    currentObservedCandles.candles.forEach { add(it.timestamp) }
                }
                val newCandles = page.candles.filter { candle ->
                    candle.timestamp < beforeTimestamp && existingTimestamps.add(candle.timestamp)
                }
                if (newCandles.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingOlder = false,
                        historyEndReached = true,
                    )
                } else {
                    prependedHistory.addAll(0, newCandles)
                    prependedHistorySnapshot = prependedHistory.toList()
                    prependedHistorySource = CandleSource.worstOf(
                        listOf(prependedHistorySource, page.source)
                    )
                    rebuildMergedVisibleCandles()
                    _uiState.value = _uiState.value.copy(
                        isLoadingOlder = false,
                        historyEndReached = false,
                    )
                    processMergedCandles(preferIncremental = false)
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingOlder = false,
                    error = error.message ?: "Failed to load older history",
                )
            }
        }
    }

    /**
     * Central candle processing pipeline.
     *
     * All CPU-bound work (structure analysis + indicator/SMC/session computation)
     * is dispatched to [defaultDispatcher] so the main thread stays responsive.
     * Must be called from within a coroutine.
     */
    private suspend fun processCandles(
        candles: List<Candle>,
        source: CandleSource = _uiState.value.dataSource,
        preferIncremental: Boolean = false,
    ) {
        val stableCandles = candles.asCandleSeries()
        val ind = _uiState.value.indicators
        val computation = computeFrame(
            candles = stableCandles,
            toggles = ind,
            preferIncremental = preferIncremental,
        )

        _uiState.value = _uiState.value.copy(
            candles = stableCandles,
            dataSource = source,
            bias = computation.bias,
            structureBreaks = if (ind.structure) computation.structureBreaks.toPersistentList() else persistentListOf(),
            emaShort = computation.overlays.emaShort.asImmutableDoubleSeries(),
            emaLong = computation.overlays.emaLong.asImmutableDoubleSeries(),
            bollingerUpper = computation.overlays.bollingerUpper.asImmutableDoubleSeries(),
            bollingerMiddle = computation.overlays.bollingerMiddle.asImmutableDoubleSeries(),
            bollingerLower = computation.overlays.bollingerLower.asImmutableDoubleSeries(),
            superTrendValues = computation.overlays.superTrendValues.asImmutableDoubleSeries(),
            superTrendDir = computation.overlays.superTrendDir.asImmutableIntSeries(),
            parabolicSar = computation.overlays.parabolicSar.asImmutableDoubleSeries(),
            vwap = computation.overlays.vwap.asImmutableDoubleSeries(),
            ichimokuTenkan = computation.overlays.ichimokuTenkan.asImmutableDoubleSeries(),
            ichimokuKijun = computation.overlays.ichimokuKijun.asImmutableDoubleSeries(),
            ichimokuSenkouA = computation.overlays.ichimokuSenkouA.asImmutableDoubleSeries(),
            ichimokuSenkouB = computation.overlays.ichimokuSenkouB.asImmutableDoubleSeries(),
            ichimokuChikou = computation.overlays.ichimokuChikou.asImmutableDoubleSeries(),
            orderBlocks = computation.overlays.orderBlocks.toPersistentList(),
            fairValueGaps = computation.overlays.fairValueGaps.toPersistentList(),
            liquidityPools = computation.overlays.liquidityPools.toPersistentList(),
            volumeProfile = computation.overlays.volumeProfile,
            marketProfile = computation.overlays.marketProfile,
            supportResistanceZones = computation.overlays.supportResistanceZones.toPersistentList(),
            autoFibLevels = computation.overlays.autoFibLevels.toPersistentList(),
            autoFibDirection = computation.overlays.autoFibDirection,
            autoFibSwingHigh = computation.overlays.autoFibSwingHigh,
            autoFibSwingLow = computation.overlays.autoFibSwingLow,
            sessions = computation.overlays.sessions.toPersistentList(),
            marketExplanation = computation.marketExplanation,
            confluence = if (ind.confluence) _uiState.value.confluence else null,
            isLoading = candles.isEmpty() && _uiState.value.error == null,
        )

        lastProcessedSnapshot = ProcessedSnapshot(
            symbol = symbolFlow.value,
            timeframe = timeframeFlow.value,
            toggles = ind,
            candlesSize = candles.size,
            firstTimestamp = candles.firstOrNull()?.timestamp,
            lastTimestamp = candles.lastOrNull()?.timestamp,
            bias = computation.bias,
            structureBreaks = computation.structureBreaks,
            overlays = computation.overlays,
        )

        // --- AI Decision Engine (run after analysis is ready) ---
        runAiDecision(candles, source)
    }

    private suspend fun computeFrame(
        candles: List<Candle>,
        toggles: IndicatorToggles,
        preferIncremental: Boolean,
    ): ChartComputation = withContext(defaultDispatcher) {
        val incremental = if (preferIncremental) {
            computeIncrementalFrame(candles, toggles)
        } else null
        incremental ?: computeFullFrame(candles, toggles)
    }

    private fun computeFullFrame(
        candles: List<Candle>,
        toggles: IndicatorToggles,
    ): ChartComputation {
        val structure = analyzeStructure(candles)
        val overlays = computeIndicators(candles, toggles)
        val explanation = if (candles.size >= 50) {
            marketExplanationEngine.explain(
                symbol = symbolFlow.value,
                timeframe = timeframeFlow.value,
                candles = candles.asCandleSeries(),
            )
        } else null
        return ChartComputation(
            bias = structure.bias,
            structureBreaks = structure.breaks,
            overlays = overlays,
            marketExplanation = explanation,
        )
    }

    private fun computeIncrementalFrame(
        candles: List<Candle>,
        toggles: IndicatorToggles,
    ): ChartComputation? {
        val previous = lastProcessedSnapshot ?: return null
        if (previous.symbol != symbolFlow.value || previous.timeframe != timeframeFlow.value) return null
        if (previous.toggles != toggles) return null
        if (previous.firstTimestamp != candles.firstOrNull()?.timestamp) return null
        if (toggles.volumeProfile || toggles.marketProfile) return null

        val size = candles.size
        val previousSize = previous.candlesSize
        val isLastBarUpdate = size == previousSize && previous.lastTimestamp == candles.lastOrNull()?.timestamp
        val isAppend = size == previousSize + 1 && previous.lastTimestamp == candles.getOrNull(candles.lastIndex - 1)?.timestamp
        if (!isLastBarUpdate && !isAppend) return null

        val windowStart = (size - INCREMENTAL_ANALYSIS_WINDOW).coerceAtLeast(0)
        val windowCandles = candles.subList(windowStart, size)
        val structure = analyzeStructure(windowCandles)
        val overlays = computeIndicators(windowCandles, toggles)
        val explanation = if (candles.size >= 50) {
            marketExplanationEngine.explain(
                symbol = symbolFlow.value,
                timeframe = timeframeFlow.value,
                candles = candles.asCandleSeries(),
            )
        } else null

        return ChartComputation(
            bias = structure.bias,
            structureBreaks = previous.structureBreaks.filter { it.breakIndex < windowStart } +
                structure.breaks.map { it.shift(windowStart) },
            overlays = mergeOverlayWindow(candles, previous.overlays, overlays, toggles, windowStart, size),
            marketExplanation = explanation,
        )
    }

    private fun mergeOverlayWindow(
        candles: List<Candle>,
        previous: ComputeIndicatorsUseCase.Result,
        window: ComputeIndicatorsUseCase.Result,
        toggles: IndicatorToggles,
        windowStart: Int,
        totalSize: Int,
    ): ComputeIndicatorsUseCase.Result {
        val visuals = computeIndicators.computeIncrementalVisuals(
            candles = candles.asCandleSeries(),
            toggles = toggles,
            previous = previous,
            recomputeFrom = windowStart,
        )
        return ComputeIndicatorsUseCase.Result(
            emaShort = visuals.emaShort,
            emaLong = visuals.emaLong,
            bollingerUpper = visuals.bollingerUpper,
            bollingerMiddle = visuals.bollingerMiddle,
            bollingerLower = visuals.bollingerLower,
            superTrendValues = visuals.superTrendValues,
            superTrendDir = visuals.superTrendDir,
            superTrendFinalUpper = visuals.superTrendFinalUpper,
            superTrendFinalLower = visuals.superTrendFinalLower,
            parabolicSar = visuals.parabolicSar,
            vwap = visuals.vwap,
            ichimokuTenkan = visuals.ichimokuTenkan,
            ichimokuKijun = visuals.ichimokuKijun,
            ichimokuSenkouA = visuals.ichimokuSenkouA,
            ichimokuSenkouB = visuals.ichimokuSenkouB,
            ichimokuChikou = visuals.ichimokuChikou,
            orderBlocks = previous.orderBlocks.filter { it.endIndex < windowStart } +
                window.orderBlocks.map { it.shift(windowStart) },
            fairValueGaps = previous.fairValueGaps.filter { it.index < windowStart } +
                window.fairValueGaps.map { it.shift(windowStart) },
            liquidityPools = previous.liquidityPools.filter { it.endIndex < windowStart } +
                window.liquidityPools.map { it.shift(windowStart) },
            volumeProfile = window.volumeProfile,
            marketProfile = window.marketProfile,
            supportResistanceZones = window.supportResistanceZones,
            autoFibLevels = window.autoFibLevels,
            autoFibDirection = window.autoFibDirection,
            autoFibSwingHigh = window.autoFibSwingHigh,
            autoFibSwingLow = window.autoFibSwingLow,
            sessions = previous.sessions.filter { it.endIndex < windowStart } +
                window.sessions.map { it.shift(windowStart) },
        )
    }

    private fun com.foxtrader.app.domain.model.StructureBreak.shift(offset: Int) = copy(
        breakIndex = breakIndex + offset,
    )

    private fun com.foxtrader.app.domain.model.OrderBlock.shift(offset: Int) = copy(
        startIndex = startIndex + offset,
        endIndex = endIndex + offset,
    )

    private fun com.foxtrader.app.domain.model.FairValueGap.shift(offset: Int) = copy(
        index = index + offset,
    )

    private fun com.foxtrader.app.domain.model.LiquidityPool.shift(offset: Int) = copy(
        startIndex = startIndex + offset,
        endIndex = endIndex + offset,
        sweepIndex = sweepIndex?.plus(offset),
    )

    private fun com.foxtrader.app.domain.model.SessionRange.shift(offset: Int) = copy(
        startIndex = startIndex + offset,
        endIndex = endIndex + offset,
    )

    // ========================================================================
    // AI DECISION ENGINE
    // ========================================================================

    /**
     * Run the multi-agent reasoning pipeline and update the UI with the result.
     *
     * Guards:
     * - Requires ≥50 candles (insufficient data → clear decision).
     * - Skips re-running if the candle series has not changed since the last
     *   analysis (change detected via a lightweight O(1) fingerprint).
     * - The orchestrator and decision engine run on [defaultDispatcher] to avoid
     *   blocking the UI.
     */
    private fun runAiDecision(candles: List<Candle>, dataSource: CandleSource) {
        if (candles.size < 50) {
            _uiState.value = _uiState.value.copy(aiDecision = null, confluence = null)
            lastAiCandlesHash = 0L
            return
        }
        // Lightweight content fingerprint combining spread-out context with the
        // full latest-bar OHLCV payload. This keeps the AI pipeline O(1) while
        // still reacting to live high/low/volume updates where close is unchanged.
        val midIndex = candles.size / 2
        val last = candles.last()
        val hash = run {
            var h = candles.size.toLong()
            h = h * 31L + candles.first().timestamp
            h = h * 31L + candles.first().open.toBits()
            h = h * 31L + candles[midIndex].timestamp
            h = h * 31L + candles[midIndex].high.toBits()
            h = h * 31L + last.timestamp
            h = h * 31L + last.open.toBits()
            h = h * 31L + last.high.toBits()
            h = h * 31L + last.low.toBits()
            h = h * 31L + last.close.toBits()
            h = h * 31L + last.volume.toBits()
            h
        }
        if (hash == lastAiCandlesHash) return
        lastAiCandlesHash = hash

        val analysisSymbol = symbolFlow.value
        val analysisTimeframe = timeframeFlow.value

        viewModelScope.launch {
            val mtfCandles = mtfContextProvider.getHtfContext(
                symbol = analysisSymbol,
                executionTimeframe = analysisTimeframe,
            )
            val correlatedCandles = mtfContextProvider.getCorrelatedContext(
                symbol = analysisSymbol,
                timeframe = analysisTimeframe,
            )
            val context = AgentContext(
                symbol = analysisSymbol,
                timeframe = analysisTimeframe,
                candles = candles.asCandleSeries(),
                mtfCandles = mtfCandles,
                correlatedCandles = correlatedCandles,
            )

            // Multi-agent analysis and decision scoring are CPU-bound; run them
            // together off the main thread.
            val decision = withContext(defaultDispatcher) {
                val orchestratorResult = orchestrator.analyze(context)
                decisionEngine.evaluate(orchestratorResult, dataSource)
            }
            val confluence = if (_uiState.value.indicators.confluence) {
                withContext(defaultDispatcher) {
                    val dataByTimeframe = linkedMapOf(analysisTimeframe to candles).apply { putAll(mtfCandles) }
                    val bullish = confluenceEngine.analyze(dataByTimeframe)
                    val bearish = confluenceEngine.analyze(
                        dataByTimeframe = dataByTimeframe,
                        primaryDirection = com.foxtrader.app.domain.model.Direction.BEARISH,
                    )
                    when {
                        bearish.confluenceScore > bullish.confluenceScore -> bearish
                        bullish.confluenceScore > bearish.confluenceScore -> bullish
                        bullish.overallBias == Bias.BEARISH -> bearish
                        else -> bullish
                    }
                }
            } else null

            // Drop stale AI results if the user changed chart context while this
            // background analysis was running.
            if (symbolFlow.value != analysisSymbol || timeframeFlow.value != analysisTimeframe) return@launch

            val htfExplanation = withContext(defaultDispatcher) {
                marketExplanationEngine.explain(
                    symbol = analysisSymbol,
                    timeframe = analysisTimeframe,
                    candles = candles.asCandleSeries(),
                    htfCandles = mtfCandles,
                )
            }

            _uiState.value = _uiState.value.copy(
                aiDecision = decision,
                marketExplanation = htfExplanation,
                confluence = confluence,
            )

            // Fire a push alert if the AI approves a signal (cooldown-gated).
            val alert = aiAlertService.evaluate(decision, analysisSymbol)
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
        refreshMultiChartPanels()
    }

    fun onSymbolChange(symbol: String) {
        symbolFlow.value = symbol
        resetPrimaryChartContext(symbol = symbol, clearSymbolPicker = true)
        aiAlertService.resetCooldowns()
        syncMultiChartPanelsToPrimary()
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
        val current = _uiState.value.indicators
        val updated = transform(current)
        if (current.confluence != updated.confluence) {
            lastAiCandlesHash = 0L
        }
        _uiState.value = _uiState.value.copy(
            indicators = updated,
            confluence = if (updated.confluence) _uiState.value.confluence else null,
        )
        viewModelScope.launch { processMergedCandles(preferIncremental = false) }
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
        resetPrimaryChartContext(timeframe = timeframe)
        syncMultiChartPanelsToPrimary()
        refresh()
        // Re-subscribe live feed to the new timeframe only if live is enabled.
        if (_uiState.value.liveEnabled) {
            viewModelScope.launch {
                webSocket.disconnectAll()
                webSocket.subscribe(symbolFlow.value, timeframe)
            }
        }
    }

    fun setMultiChartLayout(layout: ChartLayout) {
        multiChartManager.setLayout(layout)
        ensureMultiChartPanelCount(layout)
        syncMultiChartPanelsToPrimary()
        persistMultiChartPreferences()
    }

    private fun resetPrimaryChartContext(
        symbol: String = symbolFlow.value,
        timeframe: Timeframe = timeframeFlow.value,
        clearSymbolPicker: Boolean = false,
    ) {
        lastAiCandlesHash = 0L
        currentObservedCandles = SourcedCandles.EMPTY
        clearPrependedHistory()
        primaryViewportState = null
        clearSyncedCrosshairs()
        _uiState.value = _uiState.value.copy(
            symbol = symbol,
            timeframe = timeframe,
            candles = CandleSeries.EMPTY,
            dataSource = CandleSource.CACHED,
            showSymbolPicker = if (clearSymbolPicker) false else _uiState.value.showSymbolPicker,
            aiDecision = null,
            confluence = null,
            isLoading = true,
            isLoadingOlder = false,
            historyEndReached = false,
        )
    }

    fun addMultiChartPanel() {
        val seed = if (multiChartLinkedToPrimary) {
            val nextTf = linkedPanelTimeframes(timeframeFlow.value, multiChartManager.getPanels().size + 1)
                .getOrElse(multiChartManager.getPanels().size) { timeframeFlow.value }
            symbolFlow.value to nextTf
        } else {
            val active = multiChartManager.getActivePanel()
            (active?.symbol ?: symbolFlow.value) to (active?.timeframe ?: timeframeFlow.value)
        }
        multiChartManager.addPanel(seed.first, seed.second) ?: return
        refreshMultiChartPanels()
        persistMultiChartPreferences()
    }

    fun removeMultiChartPanel(panelId: String) {
        if (!multiChartManager.removePanel(panelId)) return
        clearSyncedCrosshairs()
        panelViewportStates.remove(panelId)
        refreshMultiChartPanels()
        persistMultiChartPreferences()
    }

    fun moveMultiChartPanelToIndex(panelId: String, targetIndex: Int) {
        if (multiChartManager.movePanel(panelId, targetIndex)) {
            refreshMultiChartPanels()
            persistMultiChartPreferences()
        }
    }

    fun toggleMultiChartLinking() {
        multiChartLinkedToPrimary = !multiChartLinkedToPrimary
        if (multiChartLinkedToPrimary) {
            syncMultiChartPanelsToPrimary()
        } else {
            publishMultiChartState()
        }
        persistMultiChartPreferences()
    }

    fun toggleMultiChartSymbolLink() {
        multiChartSymbolLinkEnabled = !multiChartSymbolLinkEnabled
        if (multiChartLinkedToPrimary && multiChartSymbolLinkEnabled) {
            syncMultiChartPanelsToPrimary()
        } else {
            publishMultiChartState()
        }
        persistMultiChartPreferences()
    }

    fun toggleMultiChartTimeframeLink() {
        multiChartTimeframeLinkEnabled = !multiChartTimeframeLinkEnabled
        if (multiChartLinkedToPrimary && multiChartTimeframeLinkEnabled) {
            syncMultiChartPanelsToPrimary()
        } else {
            publishMultiChartState()
        }
        persistMultiChartPreferences()
    }

    fun toggleMultiChartCrosshairSync() {
        multiChartManager.toggleCrosshairSync()
        if (!multiChartManager.isCrosshairSynced()) {
            clearSyncedCrosshairs()
        }
        publishMultiChartState()
        persistMultiChartPreferences()
    }

    fun setActiveMultiChartPanel(panelId: String) {
        multiChartManager.setActivePanel(panelId)
        publishMultiChartState()
        persistMultiChartPreferences()
    }

    fun setMultiChartPanelSymbol(panelId: String, symbol: String) {
        if (multiChartLinkedToPrimary) return
        val panel = multiChartManager.getPanels().firstOrNull { it.id == panelId } ?: return
        clearPanelViewportIfContextChanged(panelId, panel.symbol, panel.timeframe, symbol, panel.timeframe)
        multiChartManager.updatePanel(panelId, symbol = symbol)
        refreshMultiChartPanels()
        persistMultiChartPreferences()
    }

    fun setMultiChartPanelTimeframe(panelId: String, timeframe: Timeframe) {
        if (multiChartLinkedToPrimary) return
        val panel = multiChartManager.getPanels().firstOrNull { it.id == panelId } ?: return
        clearPanelViewportIfContextChanged(panelId, panel.symbol, panel.timeframe, panel.symbol, timeframe)
        multiChartManager.updatePanel(panelId, timeframe = timeframe)
        refreshMultiChartPanels()
        persistMultiChartPreferences()
    }

    fun resetMultiChartPanelToPrimary(panelId: String) {
        val panel = multiChartManager.getPanels().firstOrNull { it.id == panelId } ?: return
        clearPanelViewportIfContextChanged(panelId, panel.symbol, panel.timeframe, symbolFlow.value, timeframeFlow.value)
        multiChartManager.updatePanel(
            id = panelId,
            symbol = symbolFlow.value,
            timeframe = timeframeFlow.value,
        )
        if (multiChartLinkedToPrimary) {
            syncMultiChartPanelsToPrimary()
        } else {
            refreshMultiChartPanels()
        }
        persistMultiChartPreferences()
    }

    fun onPrimaryCrosshairTimestampChange(timestamp: Long?) {
        if (!multiChartManager.isCrosshairSynced()) return
        primarySyncedCrosshairTimestamp = null
        panelSyncedCrosshairTimestamps.clear()
        if (timestamp != null) {
            lastCrosshairSource = PersistedCrosshairSource.PRIMARY
            lastCrosshairSourcePanelId = null
            multiChartManager.getPanels().forEach { panel ->
                panelSyncedCrosshairTimestamps[panel.id] = timestamp
            }
        } else {
            lastCrosshairSource = PersistedCrosshairSource.NONE
            lastCrosshairSourcePanelId = null
        }
        publishMultiChartState(primaryCrosshairTimestamp = null)
        persistMultiChartPreferences()
    }

    fun onMultiChartPanelCrosshairTimestampChange(panelId: String, timestamp: Long?) {
        if (!multiChartManager.isCrosshairSynced()) return
        primarySyncedCrosshairTimestamp = timestamp
        panelSyncedCrosshairTimestamps.clear()
        if (timestamp != null) {
            lastCrosshairSource = PersistedCrosshairSource.PANEL
            lastCrosshairSourcePanelId = panelId
            panelSyncedCrosshairTimestamps.putAll(
                multiChartManager.syncCrosshairTime(panelId, timestamp)
            )
        } else {
            lastCrosshairSource = PersistedCrosshairSource.NONE
            lastCrosshairSourcePanelId = null
        }
        publishMultiChartState(primaryCrosshairTimestamp = timestamp)
        persistMultiChartPreferences()
    }

    private fun ensureMultiChartPanelCount(layout: ChartLayout) {
        val targetCount = when (layout) {
            ChartLayout.SINGLE -> 1
            ChartLayout.HORIZONTAL_SPLIT, ChartLayout.VERTICAL_SPLIT -> 2
            ChartLayout.THREE_TOP -> 3
            ChartLayout.GRID_2X2 -> 4
        }
        while (multiChartManager.getPanels().size < targetCount) {
            multiChartManager.addPanel(symbolFlow.value, timeframeFlow.value)
        }
        while (multiChartManager.getPanels().size > targetCount) {
            multiChartManager.getPanels().lastOrNull()?.id?.let { multiChartManager.removePanel(it) }
        }
    }

    private fun syncMultiChartPanelsToPrimary() {
        ensureMultiChartPanelCount(multiChartManager.getLayout())
        val panels = multiChartManager.getPanels()
        val linkedTimeframes = linkedPanelTimeframes(timeframeFlow.value, panels.size)
        panels.forEachIndexed { index, panel ->
            val target = targetPanelContext(
                index = index,
                panel = panel,
                linkedTimeframes = linkedTimeframes,
            ) ?: return@forEachIndexed
            clearPanelViewportIfContextChanged(
                panelId = panel.id,
                previousSymbol = panel.symbol,
                previousTimeframe = panel.timeframe,
                newSymbol = target.first,
                newTimeframe = target.second,
            )
            multiChartManager.updatePanel(
                id = panel.id,
                symbol = target.first,
                timeframe = target.second,
            )
        }
        refreshMultiChartPanels()
        if (hasRestoredMultiChartPreferences) persistMultiChartPreferences()
    }

    private fun targetPanelContext(
        index: Int,
        panel: com.foxtrader.app.domain.usecase.chart.ChartPanel,
        linkedTimeframes: List<Timeframe>,
    ): Pair<String, Timeframe>? = when {
        index == 0 -> symbolFlow.value to timeframeFlow.value
        !multiChartLinkedToPrimary -> null
        else -> {
            val targetSymbol = if (multiChartSymbolLinkEnabled) symbolFlow.value else panel.symbol
            val targetTimeframe = if (multiChartTimeframeLinkEnabled) {
                linkedTimeframes.getOrElse(index) { timeframeFlow.value }
            } else {
                panel.timeframe
            }
            targetSymbol to targetTimeframe
        }
    }

    private fun refreshMultiChartPanels() {
        multiChartPanelJobs.values.forEach { it.cancel() }
        multiChartPanelJobs.clear()
        multiChartPanels.clear()
        panelPublishedFingerprints.clear()

        val panels = multiChartManager.getPanels()
        panels.forEach { panel ->
            multiChartPanels[panel.id] = MultiChartPanelUiState(
                id = panel.id,
                symbol = panel.symbol,
                timeframe = panel.timeframe,
                isActive = panel.isActive,
                isLoading = true,
            )
            multiChartPanelJobs[panel.id] = repository.observeSourcedCandles(panel.symbol, panel.timeframe)
                .onEach { sourced ->
                    val compactCandles = sourced.candles.takeLast(PANEL_RENDER_BARS)
                    val biasInput = sourced.candles.takeLast(PANEL_BIAS_BARS)
                    val bias = withContext(defaultDispatcher) {
                        if (biasInput.size >= 50) analyzeStructure(biasInput).bias else Bias.NEUTRAL
                    }
                    val last = compactCandles.lastOrNull()
                    val fingerprint = buildString {
                        append(panel.symbol)
                        append(':')
                        append(panel.timeframe.label)
                        append(':')
                        append(sourced.source)
                        append(':')
                        append(compactCandles.size)
                        append(':')
                        append(last?.timestamp)
                        append(':')
                        append(last?.close)
                        append(':')
                        append(bias)
                        append(':')
                        append(panel.isActive)
                    }
                    if (panelPublishedFingerprints[panel.id] == fingerprint) return@onEach
                    panelPublishedFingerprints[panel.id] = fingerprint
                    multiChartPanels[panel.id] = MultiChartPanelUiState(
                        id = panel.id,
                        symbol = panel.symbol,
                        timeframe = panel.timeframe,
                        candles = compactCandles.asCandleSeries(),
                        dataSource = sourced.source,
                        bias = bias,
                        isActive = panel.isActive,
                        syncedCrosshairTimestamp = panelSyncedCrosshairTimestamps[panel.id],
                        isLoading = false,
                        error = null,
                    )
                    publishMultiChartState()
                }
                .launchIn(viewModelScope)

            viewModelScope.launch {
                repository.refreshCandles(
                    symbol = panel.symbol,
                    timeframe = panel.timeframe,
                    limit = PANEL_REFRESH_BARS,
                ).onFailure { error ->
                    val current = multiChartPanels[panel.id] ?: return@onFailure
                    multiChartPanels[panel.id] = current.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load panel",
                    )
                    publishMultiChartState()
                }
            }
        }
        publishMultiChartState()
    }

    private fun publishMultiChartState(primaryCrosshairTimestamp: Long? = primarySyncedCrosshairTimestamp) {
        val ordered = multiChartManager.getPanels().map { panel ->
            (multiChartPanels[panel.id] ?: MultiChartPanelUiState(
                id = panel.id,
                symbol = panel.symbol,
                timeframe = panel.timeframe,
                isActive = panel.isActive,
                isLoading = true,
            )).copy(
                symbol = panel.symbol,
                timeframe = panel.timeframe,
                isActive = panel.isActive,
                syncedCrosshairTimestamp = panelSyncedCrosshairTimestamps[panel.id],
            )
        }
        _multiChartState.value = MultiChartUiState(
            layout = multiChartManager.getLayout(),
            linkedToPrimary = multiChartLinkedToPrimary,
            symbolLinkEnabled = multiChartSymbolLinkEnabled,
            timeframeLinkEnabled = multiChartTimeframeLinkEnabled,
            crosshairSyncEnabled = multiChartManager.isCrosshairSynced(),
            panels = ordered.toPersistentList(),
        )
        _uiState.value = _uiState.value.copy(syncedCrosshairTimestamp = primaryCrosshairTimestamp)
    }

    private fun linkedPanelTimeframes(primary: Timeframe, count: Int): List<Timeframe> {
        val ordered = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
        )
        val startIndex = ordered.indexOf(primary).coerceAtLeast(0)
        return List(count) { offset ->
            ordered.getOrElse(startIndex + offset) { ordered.last() }
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
            drawings = drawingEngine.getVisibleDrawings().toPersistentList(),
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
        multiChartPanelJobs.values.forEach { it.cancel() }
        viewModelScope.launch {
            webSocket.disconnectAll()
        }
        replayEngine.stop()
    }

    private data class ProcessedSnapshot(
        val symbol: String,
        val timeframe: Timeframe,
        val toggles: IndicatorToggles,
        val candlesSize: Int,
        val firstTimestamp: Long?,
        val lastTimestamp: Long?,
        val bias: Bias,
        val structureBreaks: List<com.foxtrader.app.domain.model.StructureBreak>,
        val overlays: ComputeIndicatorsUseCase.Result,
    )

    private data class ChartComputation(
        val bias: Bias,
        val structureBreaks: List<com.foxtrader.app.domain.model.StructureBreak>,
        val overlays: ComputeIndicatorsUseCase.Result,
        val marketExplanation: com.foxtrader.app.domain.usecase.ai.MarketExplanation?,
    )

    private class ConcatenatedCandleList(
        private val older: List<Candle>,
        private val newer: List<Candle>,
    ) : AbstractList<Candle>() {
        override val size: Int = older.size + newer.size

        override fun get(index: Int): Candle =
            if (index < older.size) older[index] else newer[index - older.size]
    }

    private companion object {
        const val HISTORY_PAGE_SIZE = 500
        const val INCREMENTAL_ANALYSIS_WINDOW = 320
        const val PANEL_RENDER_BARS = 240
        const val PANEL_BIAS_BARS = 320
        const val PANEL_REFRESH_BARS = 320
        const val PERSIST_MULTI_CHART_DEBOUNCE_MS = 250L
    }
}
