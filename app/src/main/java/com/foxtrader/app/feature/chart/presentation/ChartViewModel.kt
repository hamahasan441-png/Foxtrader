package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ChartBarMode
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
import com.foxtrader.app.domain.usecase.chart.HeikinAshiTransformer
import com.foxtrader.app.domain.usecase.chart.CandleRenkoBuilder
import com.foxtrader.app.domain.usecase.chart.MultiChartManager
import com.foxtrader.app.domain.usecase.chart.SignalComputer
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.domain.usecase.orders.PaperTradingSession
import com.foxtrader.app.domain.usecase.performance.AdaptiveQualityController
import com.foxtrader.app.domain.usecase.performance.PerformanceProfiler
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.replay.ReplayEngine
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.domain.usecase.strategies.LiveStrategyEngine
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.sdk.script.ScriptEngine
import com.foxtrader.app.domain.sdk.script.Strategy
import com.foxtrader.app.feature.chart.presentation.components.ChartPerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
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
    private val tradeProEngine: TradeProSignalEngine,
    private val litXEngine: LitXEngine,
    private val smtDivergenceDetector: SmtDivergenceDetector,
    private val heikinAshiTransformer: HeikinAshiTransformer,
    private val candleRenkoBuilder: CandleRenkoBuilder,
    private val signalComputer: SignalComputer,
    private val liveStrategyEngine: LiveStrategyEngine,
    private val scriptEngine: ScriptEngine,
    private val paperTradingSession: PaperTradingSession,
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

    /**
     * Cached higher-timeframe candle context for the TRADEPRO MTF read.
     * Refreshed once per new primary bar while TradePro is enabled, never per
     * tick, so a long-running chart cannot retain a stale HTF bias forever.
     */
    private var htfContextKey: String? = null
    private var htfContextCache: Map<Timeframe, List<Candle>> = emptyMap()

    /**
     * Same-timeframe peer cache used by the chart SMT layer. Peer history is
     * refreshed once per primary bar (not once per tick) to keep live rendering
     * responsive while still allowing a newly closed bar to confirm divergence.
     */
    private var smtContextKey: String? = null
    private var smtContextCache: Map<String, List<Candle>> = emptyMap()

    /** Last visual blueprint compiled for live-chart evaluation. */
    private var compiledBlueprintCache: Pair<StrategyBlueprint, Strategy>? = null

    /**
     * Generation counter for the candle-computation pipeline. Rapid indicator
     * toggles and live data emissions spawn concurrent compute coroutines; each
     * captures its generation at start and only the newest may publish its
     * result, so a stale background frame can never overwrite newer UI state.
     */
    private val computationGeneration = AtomicLong(0L)

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

    private val watchlistController = ChartWatchlistController(
        watchlistRepository = watchlistRepository,
        scope = viewModelScope,
        onWatchlistChange = { symbols, activeId ->
            _uiState.value = _uiState.value.copy(
                availableSymbols = symbols.toPersistentList(),
                activeWatchlistId = activeId,
            )
        },
    )

    init {
        dataController.symbolFlow.value = _uiState.value.symbol
        dataController.timeframeFlow.value = _uiState.value.timeframe
        watchlistController.observe()
        dataController.observeMarket()
        observeDrawings()
        dataController.observeWebSocketTicks()
        multiChartController.observePersistedMultiChartPreferences()
        multiChartController.syncMultiChartPanelsToPrimary()
        webSocket.connectionState
            .onEach { cs -> _uiState.value = _uiState.value.copy(connectionState = cs) }
            .launchIn(viewModelScope)
        // Do not let the chart claim LIVE when the selected provider is
        // historical-only or its required credential has not been entered.
        combine(appPreferences.dataProvider, appPreferences.apiKeys) { _, _ ->
            appPreferences.canGoLive()
        }
            .distinctUntilChanged()
            .onEach { available ->
                val wasLive = _uiState.value.liveEnabled
                _uiState.value = _uiState.value.copy(
                    liveAvailable = available,
                    liveEnabled = if (available) _uiState.value.liveEnabled else false,
                )
                if (wasLive && !available) dataController.disconnectLive()
            }
            .launchIn(viewModelScope)
        // Apply the user's chart performance mode (quality ceiling), live.
        appPreferences.performanceMode
            .onEach { mode -> performanceMonitor.setPerformanceMode(mode) }
            .launchIn(viewModelScope)
        appPreferences.smcVisualMode
            .onEach { mode ->
                val current = _uiState.value.indicators
                if (current.smcVisualMode != mode) {
                    _uiState.value = _uiState.value.copy(indicators = current.copy(smcVisualMode = mode))
                }
            }
            .launchIn(viewModelScope)
        appPreferences.strategyBlueprints
            .onEach { blueprints ->
                val previousState = _uiState.value
                val activeId = previousState.indicators.activeBlueprintId
                val previousActive = activeId?.let { id ->
                    previousState.strategyBlueprints.firstOrNull { it.id == id }
                }
                val nextActive = activeId?.let { id -> blueprints.firstOrNull { it.id == id } }
                val activeStillExists = activeId == null || blueprints.any { it.id == activeId }
                val cachedBlueprint = compiledBlueprintCache?.first
                if (cachedBlueprint != null && cachedBlueprint !in blueprints) compiledBlueprintCache = null
                _uiState.value = previousState.copy(
                    strategyBlueprints = blueprints.toPersistentList(),
                    indicators = if (activeStillExists) {
                        previousState.indicators
                    } else {
                        previousState.indicators.copy(activeBlueprintId = null)
                    },
                )
                // Editing or deleting the blueprint currently drawn on a paused
                // chart must update its markers immediately; waiting for the
                // next market tick can leave stale signals on screen forever.
                if (activeId != null && previousActive != nextActive) {
                    try {
                        dataController.processMergedCandles(preferIncremental = false)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // The next data refresh retries; never kill preference collection.
                    }
                }
            }
            .launchIn(viewModelScope)
        refresh()
    }

    // ========================================================================
    // INTERNAL PIPELINE WIRING
    // ========================================================================
    private suspend fun processCandles(source: CandleSource, preferIncremental: Boolean) {
        // Capture this computation's generation. Only the newest generation may
        // publish its frame below (see [computationGeneration]).
        val gen = computationGeneration.incrementAndGet()

        val candles = dataController.mergedVisibleCandles
        if (candles.isEmpty()) return // Safety: skip processing when data is being cleared
        val ind = _uiState.value.indicators
        val symbol = dataController.symbolFlow.value
        val timeframe = dataController.timeframeFlow.value

        // Apply bar-mode transform BEFORE passing to indicators.
        val barMode = _uiState.value.barMode
        val displayCandles = when (barMode) {
            ChartBarMode.TIME -> candles
            ChartBarMode.HEIKIN_ASHI -> heikinAshiTransformer.transform(candles)
            ChartBarMode.RENKO -> {
                val bricks = candleRenkoBuilder.build(candles, _uiState.value.renkoSize)
                if (bricks.isEmpty()) {
                    // The configured brick is wrong for this instrument's scale
                    // (e.g. the 10.0 default on a 1.08-priced forex pair yields
                    // zero bricks and previously froze the chart on stale bars).
                    // Derive a sane brick from recent volatility (ATR-style) and
                    // publish it so the UI reflects the value actually in use.
                    val autoBrick = autoRenkoBrickSize(candles)
                    if (autoBrick != null) {
                        _uiState.value = _uiState.value.copy(renkoSize = autoBrick)
                        candleRenkoBuilder.build(candles, autoBrick)
                    } else bricks
                } else bricks
            }
        }
        if (displayCandles.isEmpty()) return

        // Feed the latest price to the shared paper-trading account so the Paper
        // Trading screen can open one-tap orders at the live charted price and
        // mark open positions to market. (onPrice only marks — it deliberately
        // does NOT replay historical candles through stop/target logic.)
        paperTradingSession.onPrice(symbol, displayCandles.last().close)

        // `CRASH-SAFETY` This pipeline is reached from the flow-driven data path
        // (observeMarket → onMergedCandlesChanged) where an uncaught exception
        // is an unhandled coroutine failure that kills the whole app — the
        // "touch an indicator and it crashes" class of bug. Every engine below
        // is therefore individually contained: one misbehaving engine degrades
        // its own overlay for this frame and the rest of the chart still
        // renders. CancellationException is always rethrown so structured
        // concurrency stays intact.
        val c = try {
            indicatorCoordinator.processCandles(
                candles = displayCandles, source = source, toggles = ind,
                symbol = symbol, timeframe = timeframe, preferIncremental = preferIncremental,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the last good frame on screen; the next data emission (or the
            // next toggle, which forces a full recompute) retries cleanly.
            return
        }

        // Refresh once per primary bar only while TRADEPRO needs HTF context.
        // This avoids per-tick DB work without freezing bias for the whole app
        // session after the first fetch.
        val htfKey = "$symbol|$timeframe|${displayCandles.last().timestamp}"
        if (ind.tradePro && htfKey != htfContextKey) {
            // MtfContextProvider already degrades to empty maps on repository
            // errors, so no extra containment is needed around these calls.
            htfContextCache = mtfContextProvider.getHtfContext(symbol, timeframe)
            htfContextKey = htfKey
        }

        // TradePro analysis is only computed while its overlay is enabled. It is
        // comparatively expensive, so running it on every frame for a hidden
        // overlay would waste the frame budget. All three engines below run on
        // the default dispatcher: they previously executed on the caller's
        // (main) context, where a heavy analysis on an indicator toggle froze
        // the UI — the "touching indicators crashes/hangs the app" report.
        val tradeProAnalysis = if (ind.tradePro) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    tradeProEngine.analyze(
                        symbol, displayCandles, appPreferences.tradeProConfig.value, htfContextCache,
                    )
                }
            }
        } else null

        val litXAnalysis = if (ind.litX) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    litXEngine.analyze(symbol, timeframe, displayCandles, appPreferences.litXConfig.value)
                }
            }
        } else null

        val smtDivergences = if (ind.smt && barMode.preservesTimeAxis) {
            val peerCandles = getSmtContext(symbol, timeframe, displayCandles.last().timestamp)
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    smtDivergenceDetector.detect(symbol, displayCandles, peerCandles)
                }
            }
        } else emptyList()

        // Evaluate the selected strategy (or all strategies) over the visible
        // series. This is the same StrategyFunction the Backtest Lab measures,
        // so plotted markers and backtest results can never diverge. Runs on
        // the default dispatcher because several strategies re-run SMC
        // detection per bar. The all-strategies scan is bounded inside
        // LiveStrategyEngine (180 bars / 12 signals per strategy).
        val activeStrategy = ind.activeStrategy
        val strategySignals = when {
            ind.allStrategies -> withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) { liveStrategyEngine.evaluateAll(displayCandles) }
            }
            activeStrategy != null -> withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    liveStrategyEngine.evaluate(activeStrategy, displayCandles)
                }
            }
            ind.activeBlueprintId != null -> {
                val blueprint = _uiState.value.strategyBlueprints
                    .firstOrNull { it.id == ind.activeBlueprintId }
                val compiled = blueprint?.let(::compileBlueprint)
                if (compiled == null) {
                    emptyList()
                } else {
                    withContext(defaultDispatcher) {
                        containedOrDefault(emptyList()) {
                            liveStrategyEngine.evaluateCustom(
                                strategyId = compiled.id,
                                strategyName = compiled.name,
                                minimumBars = compiled.minBars,
                                function = { series, index -> scriptEngine.evaluate(compiled, series, index) },
                                candles = displayCandles,
                            )
                        }
                    }
                }
            }
            else -> emptyList()
        }

        // Drop stale frames: a newer computation (e.g. from a rapid indicator
        // toggle) has already started, so publishing this older result would
        // clobber the newer UI state.
        if (gen != computationGeneration.get()) return

        _uiState.value = _uiState.value.withComputation(
            candles = displayCandles,
            source = source,
            computation = c,
            toggles = ind,
            tradeProAnalysis = tradeProAnalysis,
            litXAnalysis = litXAnalysis,
            smtDivergences = smtDivergences,
            barMode = barMode,
        ).copy(
            signals = signalComputer.computeSignals(
                litXAnalysis = litXAnalysis,
                tradeProAnalysis = tradeProAnalysis,
                smtDivergences = smtDivergences,
                candles = displayCandles,
                strategySignals = strategySignals,
            ),
        )

        aiCoordinator.runAiDecision(
            candles = displayCandles, dataSource = source, symbol = symbol, timeframe = timeframe,
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
    }

    /**
     * Volatility-derived Renko brick for the charted instrument: half the
     * average true range of the most recent bars, floored to a tiny fraction
     * of price so a flat series still produces bricks. Returns null when the
     * data cannot support any sensible brick (e.g. all-identical prices).
     */
    private fun autoRenkoBrickSize(candles: List<Candle>): Double? {
        if (candles.size < 2) return null
        val window = candles.subList((candles.size - RENKO_AUTO_ATR_WINDOW).coerceAtLeast(0), candles.size)
        var trSum = 0.0
        var count = 0
        for (i in 1 until window.size) {
            val c = window[i]
            val prevClose = window[i - 1].close
            val tr = maxOf(c.high - c.low, kotlin.math.abs(c.high - prevClose), kotlin.math.abs(c.low - prevClose))
            if (tr.isFinite()) {
                trSum += tr
                count++
            }
        }
        if (count == 0) return null
        val lastClose = candles.last().close
        val floor = if (lastClose.isFinite() && lastClose > 0.0) lastClose * RENKO_AUTO_MIN_PRICE_FRACTION else 0.0
        val brick = ((trSum / count) * RENKO_AUTO_ATR_FRACTION).coerceAtLeast(floor)
        return if (brick.isFinite() && brick > 0.0) brick else null
    }

    // ========================================================================
    // SIMPLE OBSERVATION / UI ACTIONS
    // ========================================================================
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeDrawings() {
        combine(dataController.symbolFlow, dataController.timeframeFlow) { s, tf -> s to tf }
            .flatMapLatest { (symbol, tf) -> drawingRepository.observe(symbol, tf) }
            .onEach { drawings ->
                // Keep the in-memory engine in sync with the persisted set so a
                // subsequent placement (or hit-test) sees the restored drawings
                // instead of an empty engine after restart / symbol switch.
                drawingEngine.restore(drawings)
                _uiState.value = _uiState.value.copy(drawings = drawings.toPersistentList())
            }
            .launchIn(viewModelScope)
    }

    fun openCalculator() { _uiState.value = _uiState.value.copy(showCalculator = true) }
    fun closeCalculator() { _uiState.value = _uiState.value.copy(showCalculator = false) }

    fun addSymbolToWatchlist(symbol: String) = watchlistController.addSymbol(symbol)

    fun removeSymbolFromWatchlist(symbol: String) = watchlistController.removeSymbol(symbol)

    fun openSymbolPicker() { _uiState.value = _uiState.value.copy(showSymbolPicker = true) }
    fun closeSymbolPicker() { _uiState.value = _uiState.value.copy(showSymbolPicker = false) }

    fun toggleIndicatorPanel() {
        _uiState.value = _uiState.value.copy(showIndicatorPanel = !_uiState.value.showIndicatorPanel)
    }

    fun toggleSignalHistory() {
        _uiState.value = _uiState.value.copy(showSignalHistory = !_uiState.value.showSignalHistory)
    }

    /**
     * Select the strategy plotted on the chart, or pass null to clear it.
     * Selecting the already-active strategy toggles it off, which matches how
     * the indicator chips behave.
     */
    fun selectStrategy(type: StrategyType?) {
        val current = _uiState.value.indicators.activeStrategy
        val next = if (type == current) null else type
        if (next == current && !_uiState.value.indicators.allStrategies) return
        // Selecting a specific strategy exits "all strategies" mode.
        updateIndicators {
            it.copy(activeStrategy = next, activeBlueprintId = null, allStrategies = false)
        }
    }

    /** Toggle "all strategies" mode; mutually exclusive with a single strategy. */
    fun toggleAllStrategies() {
        updateIndicators {
            it.copy(
                allStrategies = !it.allStrategies,
                activeStrategy = null,
                activeBlueprintId = null,
            )
        }
    }

    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
        val current = _uiState.value.indicators
        val updated = transform(current)
        if (current.confluence != updated.confluence) { aiCoordinator.lastAiCandlesHash = 0L }
        if (current.smt != updated.smt) {
            smtContextKey = null
            smtContextCache = emptyMap()
        }
        if (current.smcVisualMode != updated.smcVisualMode) {
            appPreferences.setSmcVisualMode(updated.smcVisualMode)
        }
        _uiState.value = _uiState.value.copy(
            indicators = updated,
            confluence = if (updated.confluence) _uiState.value.confluence else null,
        )
        // The user explicitly asked for a different overlay set: give the render
        // pipeline a clean quality slate so a previously-degraded session cannot
        // silently skip the newly enabled indicator (see onOverlayConfigChanged).
        performanceMonitor.onOverlayConfigChanged()
        viewModelScope.launch {
            try {
                dataController.processMergedCandles(preferIncremental = false)
            } catch (cancel: CancellationException) {
                throw cancel
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
        smtContextKey = null
        smtContextCache = emptyMap()
        dataController.resetPrimaryChartContext()
        multiChartController.resetPrimaryViewportState()
        _primaryViewport.value = null
        _uiState.value = _uiState.value.copy(
            symbol = symbol, timeframe = timeframe,
            candles = CandleSeries.EMPTY, dataSource = CandleSource.CACHED,
            showSymbolPicker = if (clearSymbolPicker) false else _uiState.value.showSymbolPicker,
            aiDecision = null, confluence = null,
            isLoading = true, isLoadingOlder = false, historyEndReached = false,
        )
    }

    private suspend fun getSmtContext(
        symbol: String,
        timeframe: Timeframe,
        latestPrimaryTimestamp: Long,
    ): Map<String, List<Candle>> {
        val key = "$symbol|$timeframe|$latestPrimaryTimestamp"
        if (smtContextKey != key) {
            smtContextCache = mtfContextProvider.getCorrelatedContext(
                symbol = symbol,
                timeframe = timeframe,
                refreshMissing = true,
            )
            smtContextKey = key
        }
        return smtContextCache
    }

    private fun compileBlueprint(blueprint: StrategyBlueprint): Strategy {
        val cached = compiledBlueprintCache
        if (cached?.first == blueprint) return cached.second
        return scriptEngine.compileBlueprint(blueprint).also {
            compiledBlueprintCache = blueprint to it
        }
    }

    private inline fun <T> containedOrNull(block: () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    private inline fun <T> containedOrDefault(defaultValue: T, block: () -> T): T = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        defaultValue
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
        val current = _uiState.value
        if (!current.liveAvailable && !current.liveEnabled) return
        val enabled = !current.liveEnabled
        _uiState.value = current.copy(liveEnabled = enabled)
        dataController.toggleLive(!enabled)
    }

    fun onBarModeChange(barMode: ChartBarMode) {
        _uiState.value = _uiState.value.copy(barMode = barMode)
        viewModelScope.launch {
            try {
                dataController.processMergedCandles(preferIncremental = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Swallow concurrent modification exceptions during bar-mode change.
                // The next data emission will trigger a successful recompute.
            }
        }
    }

    fun onRenkoSizeChange(size: Double) {
        _uiState.value = _uiState.value.copy(renkoSize = size)
        if (_uiState.value.barMode == ChartBarMode.RENKO) {
            viewModelScope.launch {
                try {
                    dataController.processMergedCandles(preferIncremental = false)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Swallow concurrent modification exceptions during renko size change.
                }
            }
        }
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

    /** Delete a single drawing by id (per-object management). */
    fun deleteDrawing(id: String) = drawingController.deleteDrawing(id)

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
        // BUGFIX: viewModelScope is already cancelled when onCleared() runs,
        // so launching into it would never execute. Use runBlocking for the
        // short, non-blocking disconnect call to prevent the websocket from
        // leaking the Service context (SystemJobService leak in crash report).
        kotlinx.coroutines.runBlocking {
            try { webSocket.disconnectAll() } catch (_: Exception) { }
        }
        replayEngine.stop()
    }

    private companion object {
        /** Bars sampled when deriving an automatic Renko brick from volatility. */
        const val RENKO_AUTO_ATR_WINDOW = 100

        /** Brick = ATR × this fraction (half an average bar's range). */
        const val RENKO_AUTO_ATR_FRACTION = 0.5

        /** Absolute floor as a fraction of price, so flat data still bricks. */
        const val RENKO_AUTO_MIN_PRICE_FRACTION = 0.0001
    }
}
