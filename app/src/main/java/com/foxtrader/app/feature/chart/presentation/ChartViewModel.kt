package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.MarketDataFreshnessResolver
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
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.backtest.HistoricalBacktestRunner
import com.foxtrader.app.domain.usecase.backtest.HistoricalTestWindow
import com.foxtrader.app.domain.usecase.binary.DerivBinary3mSignalEngine
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.domain.usecase.marketdata.MarketProviderRouter
import com.foxtrader.app.domain.usecase.orders.PaperTradingSession
import com.foxtrader.app.domain.usecase.performance.AdaptiveQualityController
import com.foxtrader.app.domain.usecase.performance.PerformanceProfiler
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.replay.ReplayEngine
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.signalintel.LitEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.nascent.NascentEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.signalintel.SmsEngine
import com.foxtrader.app.domain.usecase.signalintel.SignalFusionEngine
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.domain.usecase.strategies.LiveStrategyEngine
import com.foxtrader.app.domain.usecase.strategies.StrategyLibrary
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.SignalIdentity
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.sdk.script.ScriptEngine
import com.foxtrader.app.domain.sdk.script.Strategy
import com.foxtrader.app.feature.chart.presentation.components.ChartPerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val providerRouter: MarketProviderRouter,
    private val tradeProEngine: TradeProSignalEngine,
    private val litXEngine: LitXEngine,
    private val litEngine: LitEngine,
    private val smsEngine: SmsEngine,
    private val signalFusionEngine: SignalFusionEngine,
    private val smtDivergenceDetector: SmtDivergenceDetector,
    private val rsiOrderFlowSignalEngine: RsiOrderFlowSignalEngine,
    private val pivotSweepDivergenceEngine: PivotSweepDivergenceEngine,
    private val valueAreaLiquidityRejectionEngine: ValueAreaLiquidityRejectionEngine,
    private val amdEngine: AccumulationManipulationDistributionEngine,
    private val nascentEngine: NascentEngine,
    private val heikinAshiTransformer: HeikinAshiTransformer,
    private val candleRenkoBuilder: CandleRenkoBuilder,
    private val signalComputer: SignalComputer,
    private val liveStrategyEngine: LiveStrategyEngine,
    private val strategyLibrary: StrategyLibrary,
    private val backtestEngine: BacktestEngine,
    private val derivBinary3mSignalEngine: DerivBinary3mSignalEngine,
    private val instrumentTypeResolver: InstrumentTypeResolver,
    private val scriptEngine: ScriptEngine,
    private val paperTradingSession: PaperTradingSession,
    profiler: PerformanceProfiler,
    qualityController: AdaptiveQualityController,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val performanceMonitor = ChartPerformanceMonitor(profiler, qualityController)
    // Use the exact BacktestEngine instance already owned by the chart so
    // visible-range research shares the same execution model and config.
    private val historicalBacktestRunner = HistoricalBacktestRunner(backtestEngine)
    private val liveSignalArchive = LiveSignalArchive()
    private val liveSignalPerformanceEvaluator = LiveSignalPerformanceEvaluator()

    /**
     * Causal history for LiT Adventure / May Madness. Those engines expose the
     * latest snapshot, so a prefix scan is required to reconstruct old arrows
     * when a system is first added to an already-loaded chart.
     */
    private val productionHistoryLock = Any()
    private var productionHistoryKey: ProductionHistoryKey? = null
    private var productionHistoryScannedThrough = -1
    private val productionHistorySignals = LinkedHashMap<String, ChartSignal>()

    /**
     * Independent, bounded cleanup scope. viewModelScope is already cancelled
     * by the time onCleared() runs; blocking the main thread to wait for a
     * websocket mutex can cause an ANR. This scope performs only final teardown
     * and cancels itself immediately afterwards.
     */
    private val lifecycleCleanupScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    private val _uiState = MutableStateFlow(
        ChartUiState(
            dataProvider = appPreferences.dataProvider.value,
            timeframe = appPreferences.defaultTimeframe.value,
        )
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

    /** Preserve an explicit user choice to keep streaming off across symbol changes. */
    private var liveUserOverrideOff = false

    /** Replay owns chart overlays while active; live frames are suppressed. */
    private var replayWasActive = false
    private var lastReplayFrameIndex = -1

    // --- Controllers (plain classes, NOT @Inject) ---
    private val dataController = ChartDataController(
        repository = repository,
        webSocket = webSocket,
        scope = viewModelScope,
        onMergedCandlesChanged = { source, preferIncremental ->
            processCandles(source, preferIncremental)
        },
        onUpsertTick = { symbol, timeframe, candle, tickProvider ->
            // ProviderMarketWebSocket stamps every routed tick. A tick that was
            // already queued when the user changed provider must never enter
            // the provider-agnostic Room cache under the new source identity.
            if (tickProvider == _uiState.value.dataProvider &&
                tickProvider == appPreferences.dataProvider.value
            ) {
                repository.upsertCandle(symbol, timeframe, candle)
            }
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
        dataController.observeLiveRecovery()
        multiChartController.observePersistedMultiChartPreferences()
        multiChartController.syncMultiChartPanelsToPrimary()
        webSocket.connectionState
            .onEach { cs ->
                val current = _uiState.value
                val now = System.currentTimeMillis()
                val latestTimestamp = current.candles.lastOrNull()?.timestamp
                _uiState.value = current.copy(
                    connectionState = cs,
                    dataFreshness = MarketDataFreshnessResolver.resolve(
                        source = current.dataSource,
                        connectionState = cs,
                        timeframe = current.timeframe,
                        latestBarTimestamp = latestTimestamp,
                        nowMillis = now,
                    ),
                    dataAgeMs = latestTimestamp?.let { (now - it).coerceAtLeast(0L) },
                )
            }
            .launchIn(viewModelScope)
        // Keep this back-stack ViewModel synchronized when the provider is
        // changed from Settings. Navigation can keep ChartViewModel alive even
        // while ChartScreen is not composed, so relying on re-creation would
        // leave the provider badge/history context stale. Settings clears the
        // provider-agnostic candle cache before emitting this preference.
        appPreferences.dataProvider
            .onEach { provider ->
                val current = _uiState.value
                if (current.dataProvider != provider) {
                    if (current.liveEnabled) dataController.disconnectLive()
                    _uiState.value = current.copy(
                        dataProvider = provider,
                        liveEnabled = false,
                        connectionState = ConnectionState.DISCONNECTED,
                        isLoading = true,
                        error = null,
                    )
                    resetChartContext()
                    refresh()
                }
            }
            .launchIn(viewModelScope)

        // Live availability is strict to the selected provider. Never continue
        // one provider's historical series with another provider's live ticks.
        combine(
            appPreferences.dataProvider,
            appPreferences.apiKeys,
            dataController.symbolFlow,
        ) { provider, _, symbol ->
            providerRouter.canGoLive(symbol, provider)
        }
            .distinctUntilChanged()
            .onEach { available ->
                val current = _uiState.value
                val shouldEnable = available && !liveUserOverrideOff
                _uiState.value = current.copy(
                    liveAvailable = available,
                    liveEnabled = shouldEnable,
                )
                when {
                    shouldEnable && !current.liveEnabled -> dataController.connectLive()
                    !available && current.liveEnabled -> dataController.disconnectLive()
                }
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
        // Chart-corner settings are live in both normal and replay modes.
        combine(
            appPreferences.litXConfig,
            appPreferences.litConfig,
            appPreferences.smtConfig,
            appPreferences.smsConfig,
        ) { litx, lit, smt, sms -> listOf(litx, lit, smt, sms) }
            .distinctUntilChanged()
            .onEach {
                try {
                    val replay = replayEngine.state.value
                    if (replay.isActive) {
                        processReplayFrame(replay)
                    } else {
                        dataController.processMergedCandles(preferIncremental = false)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // No candle set yet (startup) or a transient provider state;
                    // the next data emission retries with the persisted config.
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
                val backtestBlueprintId = previousState.chartBacktest.selectedBlueprintId
                val previousBacktestBlueprint = backtestBlueprintId?.let { id ->
                    previousState.strategyBlueprints.firstOrNull { it.id == id }
                }
                val nextBacktestBlueprint = backtestBlueprintId?.let { id ->
                    blueprints.firstOrNull { it.id == id }
                }
                val backtestBlueprintChanged = backtestBlueprintId != null &&
                    previousBacktestBlueprint != nextBacktestBlueprint
                val cachedBlueprint = compiledBlueprintCache?.first
                if (cachedBlueprint != null && cachedBlueprint !in blueprints) compiledBlueprintCache = null
                _uiState.value = previousState.copy(
                    strategyBlueprints = blueprints.toPersistentList(),
                    indicators = if (activeStillExists) {
                        previousState.indicators
                    } else {
                        previousState.indicators.copy(activeBlueprintId = null)
                    },
                    chartBacktest = if (backtestBlueprintChanged) {
                        ChartBacktestState(
                            selectedStrategy = previousState.chartBacktest.selectedStrategy,
                            selectedRange = previousState.chartBacktest.selectedRange,
                            selectedBlueprintId = nextBacktestBlueprint?.id,
                            showMarkers = previousState.chartBacktest.showMarkers,
                        )
                    } else {
                        previousState.chartBacktest
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
        // Replay is a separate causal render clock. While active, each new
        // revealed bar recomputes indicators/structure/primary-series signal
        // engines from that prefix only; live websocket frames cannot replace
        // the historical frame. Stopping replay restores the latest live frame.
        replayEngine.state
            .onEach { replay ->
                if (replay.isActive) {
                    replayWasActive = true
                    if (lastReplayFrameIndex != replay.currentIndex) {
                        lastReplayFrameIndex = replay.currentIndex
                        processReplayFrame(replay)
                    }
                } else if (replayWasActive) {
                    replayWasActive = false
                    lastReplayFrameIndex = -1
                    try {
                        dataController.processMergedCandles(preferIncremental = false)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // A later market/history emission retries the normal frame.
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
        // Replay owns the visual computation clock. Keep ingesting market
        // data in the repository, but never let a live frame overwrite a
        // historical prefix while the trader is testing the past.
        if (replayEngine.state.value.isActive) return
        // `CRASH-SAFETY` Outer containment for the whole pipeline. Every
        // per-engine guard below protects against a known failure mode, but the
        // user report that triggered this class of work — "touch an indicator
        // and the app crashes" — was caused by exceptions escaping the
        // toggle-driven recompute into an unhandled coroutine failure. The
        // belt-and-braces rule: NO exception (from any present or future
        // engine, including the parts between the inner guards) may ever escape
        // this pipeline. CancellationException always rethrows so structured
        // concurrency stays intact; everything else keeps the last good frame
        // and the next data emission or toggle retries cleanly.
        try {
            processCandlesInner(source, preferIncremental)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Keep the last good frame on screen; the next data emission (or the
            // next toggle, which forces a full recompute) retries cleanly.
        }
    }

    private suspend fun processCandlesInner(source: CandleSource, preferIncremental: Boolean) {
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

        // Phase 13 signal engines never inspect an in-progress candle. The
        // visual chart may still render it, but LiTX/LiT/SMS/SMT/TradePro and
        // strategy arrows operate on the closed-bar prefix only.
        val signalNow = System.currentTimeMillis()
        val latestConfirmedIndex = ConfirmedBarPolicy.latestConfirmedIndex(displayCandles, timeframe, signalNow)
        val signalCandles = if (latestConfirmedIndex >= 0) {
            displayCandles.subList(0, latestConfirmedIndex + 1)
        } else {
            emptyList()
        }

        // The chart selector is authoritative. A stale global enabled=false
        // value from an older build must never leave a visible study dormant.
        val litXConfig = appPreferences.litXConfig.value.copy(enabled = true).sanitized()
        val needLitX = ind.litX || ind.tradePro
        val needLit = ind.lit || ind.tradePro
        val needSms = ind.sms || ind.tradePro
        val needSmt = (ind.smt || ind.tradePro) && barMode.preservesTimeAxis

        // Refresh HTF context only on a newly confirmed primary bar. HTF bars
        // are independently trimmed to their own confirmed prefixes.
        val confirmedStamp = signalCandles.lastOrNull()?.timestamp
        val htfKey = confirmedStamp?.let { "$symbol|$timeframe|$it" }
        if (ind.tradePro && htfKey != null && htfKey != htfContextKey) {
            htfContextCache = mtfContextProvider.getHtfContext(symbol, timeframe)
            htfContextKey = htfKey
        }
        val confirmedHtfContext = htfContextCache.mapValues { (tf, series) ->
            ConfirmedBarPolicy.confirmedPrefix(series, tf, signalNow)
        }.filterValues { it.isNotEmpty() }

        val tradeProBase = if (ind.tradePro && signalCandles.isNotEmpty()) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    tradeProEngine.analyze(
                        symbol, signalCandles, appPreferences.tradeProConfig.value, confirmedHtfContext,
                    )
                }
            }
        } else null

        val litXAnalysis = if (needLitX && signalCandles.isNotEmpty()) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    litXEngine.analyze(symbol, timeframe, signalCandles, litXConfig)
                }
            }
        } else null

        val litAnalysis = if (needLit && signalCandles.isNotEmpty()) {
            withContext(defaultDispatcher) {
                containedOrNull { litEngine.analyze(symbol, timeframe, signalCandles, appPreferences.litConfig.value) }
            }
        } else null

        val smsAnalysis = if (needSms && signalCandles.isNotEmpty()) {
            withContext(defaultDispatcher) {
                containedOrNull { smsEngine.analyze(symbol, timeframe, signalCandles, appPreferences.smsConfig.value) }
            }
        } else null

        val smtDivergencesForFusion = if (needSmt && signalCandles.isNotEmpty()) {
            val rawPeers = getSmtContext(symbol, timeframe, signalCandles.last().timestamp)
            val confirmedPeers = rawPeers.mapValues { (_, series) ->
                ConfirmedBarPolicy.confirmedPrefix(series, timeframe, signalNow)
            }.filterValues { it.isNotEmpty() }
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    smtDivergenceDetector.detect(symbol, signalCandles, confirmedPeers, config = appPreferences.smtConfig.value)
                }
            }
        } else emptyList()

        // RSI Orderflow Candle is defined on executable raw candles only. Do
        // not generate trade arrows from Heikin-Ashi or Renko transformations.
        // signalCandles is already the closed-bar prefix, so this path is causal.
        val rsiOrderFlowSignals = if (
            ind.rsiOrderFlow &&
            barMode == ChartBarMode.TIME &&
            signalCandles.isNotEmpty()
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    rsiOrderFlowSignalEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = signalCandles,
                        config = ind.settings.rsiOrderFlow.toSignalEngineConfig(),
                    ).signals
                }
            }
        } else emptyList()

        // PSD derives every level from the previous completed trading day and
        // receives the same hard closed-bar prefix as the other signal engines.
        // It returns the complete bounded history, so old arrows remain on the
        // chart while a newly confirmed event alone is marked live.
        val pivotSweepDivergenceSignals: List<ChartSignal> = if (
            ind.pivotSweepDivergence &&
            barMode == ChartBarMode.TIME &&
            timeframe.minutes < Timeframe.D1.minutes &&
            signalCandles.isNotEmpty()
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    pivotSweepDivergenceEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = signalCandles,
                        config = ind.settings.pivotSweepDivergence.toEngineConfig(),
                    ).signals.map { signal ->
                        signal.toChartSignal(latestConfirmedIndex)
                    }
                }
            }
        } else emptyList()

        val valueAreaLiquidityAnalysis = if (
            ind.valueAreaLiquidityRejection &&
            barMode == ChartBarMode.TIME &&
            timeframe.minutes < Timeframe.D1.minutes &&
            signalCandles.isNotEmpty()
        ) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    valueAreaLiquidityRejectionEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = signalCandles,
                        config = ind.settings.valueAreaLiquidityRejection.toEngineConfig(),
                    )
                }
            }
        } else null
        val valueAreaLiquiditySignals = valueAreaLiquidityAnalysis?.signals.orEmpty().map { signal ->
            signal.toChartSignal(latestConfirmedIndex)
        }

        // AMD is detected structurally (range compression relative to ATR)
        // rather than by session clock, so — unlike PSD/VALR — it is not
        // gated to sub-daily timeframes.
        val amdAnalysisSignals = if (
            ind.amd &&
            barMode == ChartBarMode.TIME &&
            signalCandles.isNotEmpty()
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    amdEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = signalCandles,
                        config = ind.settings.amd.toEngineConfig(),
                    ).signals
                }
            }
        } else emptyList()
        val amdSignals = amdAnalysisSignals.map { signal -> signal.toChartSignal(latestConfirmedIndex) }

        // Nascent reconstructs its own history by replaying the closed-bar
        // prefix through the identical engine the backtester uses, so enabling
        // the study backfills previous sessions instead of only reporting
        // setups that form from now on.
        val nascentAnalysis = if (
            ind.nascent &&
            barMode == ChartBarMode.TIME &&
            signalCandles.isNotEmpty()
        ) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    nascentEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = signalCandles,
                        config = ind.settings.nascent.toEngineConfig(),
                    )
                }
            }
        } else null
        val nascentSignals: List<ChartSignal> = nascentAnalysis?.signals.orEmpty()
            .map { signal -> signal.toChartSignal(latestConfirmedIndex) }

        // Backfill previously confirmed LiT arrows. Each bar is evaluated only
        // through its own closed prefix, so later data cannot create, move or
        // delete an earlier marker (non-repaint by construction).
        val productionHistory = if ((ind.litX || ind.lit) && signalCandles.isNotEmpty()) {
            withContext(defaultDispatcher) {
                scanProductionSignalHistory(
                    indicators = ind,
                    symbol = symbol,
                    timeframe = timeframe,
                    barMode = barMode,
                    candles = signalCandles,
                    litXConfig = litXConfig,
                    litConfig = appPreferences.litConfig.value.sanitized(),
                )
            }
        } else {
            emptyList()
        }

        val fused = signalFusionEngine.fuse(
            tradePro = tradeProBase,
            litX = litXAnalysis,
            lit = litAnalysis,
            sms = smsAnalysis,
            smt = smtDivergencesForFusion,
            latestConfirmedIndex = signalCandles.lastIndex,
        )
        val tradeProAnalysis = fused.tradePro
        val signalFusion = fused.fusion
        val smtDivergences = if (ind.smt) smtDivergencesForFusion else emptyList()

        // Evaluate the selected strategy (or all strategies) over the visible
        // series. This is the same StrategyFunction the Backtest Lab measures,
        // so plotted markers and backtest results can never diverge. Runs on
        // the default dispatcher because several strategies re-run SMC
        // detection per bar. The all-strategies scan is bounded inside
        // LiveStrategyEngine (180 bars / 12 signals per strategy).
        val activeStrategy = ind.activeStrategy
        val strategySignals = when {
            ind.allStrategies -> withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    liveStrategyEngine.evaluateAll(
                        candles = signalCandles,
                        symbol = symbol,
                        timeframe = timeframe,
                    )
                }
            }
            activeStrategy != null -> withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    liveStrategyEngine.evaluate(
                        type = activeStrategy,
                        candles = signalCandles,
                        symbol = symbol,
                        timeframe = timeframe,
                    )
                }
            }
            ind.activeBlueprintId != null -> {
                val blueprint = _uiState.value.strategyBlueprints
                    .firstOrNull { it.id == ind.activeBlueprintId }
                if (blueprint == null) {
                    emptyList()
                } else {
                    withContext(defaultDispatcher) {
                        containedOrDefault(emptyList()) {
                            // Compiling a visual blueprint builds its Strategy
                            // lambda; run it off the caller's (main) thread and
                            // inside the containment so a malformed blueprint
                            // degrades to "no markers" instead of throwing out
                            // of the indicator-toggle recompute.
                            val compiled = compileBlueprint(blueprint)
                            liveStrategyEngine.evaluateCustom(
                                strategyId = compiled.id,
                                strategyName = compiled.name,
                                minimumBars = compiled.minBars,
                                function = { series, index -> scriptEngine.evaluate(compiled, series, index) },
                                candles = signalCandles,
                            )
                        }
                    }
                }
            }
            else -> emptyList()
        }

        // Deriv 3m uses the exact same closed-M1 engine as BinaryBacktestEngine.
        // The marker belongs to the CONFIRMATION bar; the trade model enters at
        // the next M1 open and expires after three minutes. Keeping the marker on
        // the signal bar makes that distinction explicit and avoids pretending a
        // future entry price was known at confirmation time. Synthetic/Heikin/Renko
        // bars are not eligible because fixed-expiry timing must use raw M1 candles.
        val binary3mSignals: List<ChartSignal> = if (
            ind.binary3m &&
            _uiState.value.dataProvider == DataProvider.DERIV &&
            timeframe == Timeframe.M1 &&
            barMode == ChartBarMode.TIME &&
            signalCandles.isNotEmpty()
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    derivBinary3mSignalEngine
                        .evaluateAll(signalCandles, DerivBinary3mSignalEngine.DEFAULT_MIN_CONFIDENCE)
                        .takeLast(BINARY3M_MAX_CHART_SIGNALS)
                        .mapNotNull { binary ->
                            val candle = signalCandles.getOrNull(binary.signalIndex) ?: return@mapNotNull null
                            ChartSignal(
                                id = "binary3m_${symbol}_${binary.timestamp}_${binary.direction.name}",
                                source = SignalSource.BINARY3M,
                                direction = binary.direction,
                                entry = candle.close,
                                sl = 0.0,
                                tp = 0.0,
                                barIndex = binary.signalIndex,
                                timestamp = binary.timestamp,
                                confidence = binary.confidence.toDouble(),
                                isLive = binary.signalIndex == signalCandles.lastIndex,
                                label = "Deriv 3m ${if (binary.direction == Direction.BULLISH) "CALL" else "PUT"} · enter next M1 · ${binary.confidence}%",
                            )
                        }
                }
            }
        } else {
            emptyList()
        }

        val chartStrategySignals = productionHistory + pivotSweepDivergenceSignals + valueAreaLiquiditySignals + amdSignals + nascentSignals + strategySignals + binary3mSignals

        // Drop stale frames: a newer computation (e.g. from a rapid indicator
        // toggle) has already started, so publishing this older result would
        // clobber the newer UI state.
        if (gen != computationGeneration.get()) return

        val now = System.currentTimeMillis()
        val mappedState = _uiState.value.withComputation(
            candles = displayCandles,
            source = source,
            computation = c,
            toggles = ind,
            tradeProAnalysis = tradeProAnalysis,
            litXAnalysis = litXAnalysis.takeIf { ind.litX },
            litAnalysis = litAnalysis.takeIf { ind.lit },
            smsAnalysis = smsAnalysis.takeIf { ind.sms },
            signalFusion = signalFusion.takeIf { ind.tradePro || ind.litX || ind.lit || ind.sms || ind.smt },
            smtDivergences = smtDivergences,
            barMode = barMode,
        ).copy(
            valueAreaLiquidityProfiles = valueAreaLiquidityAnalysis?.profiles.orEmpty().toPersistentList(),
            amdZones = amdAnalysisSignals.toPersistentList(),
            nascentKeyLevels = nascentAnalysis?.keyLevels.orEmpty().toPersistentList(),
        )
        val frameSignals = signalComputer.computeSignals(
            litXAnalysis = litXAnalysis.takeIf { ind.litX },
            tradeProAnalysis = tradeProAnalysis,
            smtDivergences = smtDivergences,
            candles = displayCandles,
            strategySignals = chartStrategySignals,
            litAnalysis = litAnalysis.takeIf { ind.lit },
            smsAnalysis = smsAnalysis.takeIf { ind.sms },
            rsiOrderFlowSignals = rsiOrderFlowSignals,
            latestConfirmedIndex = latestConfirmedIndex,
            fusion = signalFusion,
        )
        val liveSignalContext = LiveSignalContext(
            provider = mappedState.dataProvider,
            symbol = symbol.trim().uppercase(),
            timeframe = timeframe,
            barMode = barMode,
            configuration = LiveSignalConfiguration(
                indicators = ind,
                litXConfig = litXConfig,
                litConfig = appPreferences.litConfig.value.sanitized(),
                smtConfig = appPreferences.smtConfig.value.sanitized(),
            ),
        )
        val retainedSignals = liveSignalArchive.merge(
            context = liveSignalContext,
            frameSignals = frameSignals,
            candles = displayCandles,
        )
        val liveSignalStats = liveSignalPerformanceEvaluator.evaluate(
            signals = liveSignalArchive.liveSnapshot(liveSignalContext, displayCandles),
            candles = displayCandles,
            timeframe = timeframe,
        )
        _uiState.value = mappedState.copy(
            signals = retainedSignals,
            liveSignalStats = liveSignalStats,
            dataFreshness = MarketDataFreshnessResolver.resolve(
                source = source,
                connectionState = mappedState.connectionState,
                timeframe = timeframe,
                latestBarTimestamp = displayCandles.last().timestamp,
                nowMillis = now,
            ),
            dataAgeMs = (now - displayCandles.last().timestamp).coerceAtLeast(0L),
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

    private suspend fun processReplayFrame(replay: ReplayState) {
        if (!replay.isActive || replay.visibleCandles.isEmpty()) return
        val gen = computationGeneration.incrementAndGet()
        val current = _uiState.value
        val candles = replay.visibleCandles
        val ind = current.indicators
        val symbol = current.symbol
        val timeframe = current.timeframe

        val computation = try {
            indicatorCoordinator.processCandles(
                candles = candles,
                source = current.dataSource,
                toggles = ind,
                symbol = symbol,
                timeframe = timeframe,
                preferIncremental = false,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return
        }

        // Primary-series institutional engines are safe to replay because they
        // receive only the revealed prefix. MTF/peer engines (TradePro/SMT) are
        // deliberately suppressed here until every auxiliary series is bounded
        // to the same historical clock.
        val litXConfig = appPreferences.litXConfig.value.copy(enabled = true).sanitized()
        val litXAnalysis = if (ind.litX) {
            withContext(defaultDispatcher) {
                containedOrNull { litXEngine.analyze(symbol, timeframe, candles, litXConfig) }
            }
        } else null
        val litAnalysis = if (ind.lit) {
            withContext(defaultDispatcher) {
                containedOrNull { litEngine.analyze(symbol, timeframe, candles, appPreferences.litConfig.value) }
            }
        } else null
        val smsAnalysis = if (ind.sms) {
            withContext(defaultDispatcher) {
                containedOrNull { smsEngine.analyze(symbol, timeframe, candles, appPreferences.smsConfig.value) }
            }
        } else null

        // Replay visibleCandles is already the hard historical prefix. Running
        // the same engine/config here therefore produces the same confirmation
        // event as simulated live without any access to unrevealed bars.
        val rsiOrderFlowSignals = if (
            ind.rsiOrderFlow &&
            current.barMode == ChartBarMode.TIME
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    rsiOrderFlowSignalEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = candles,
                        config = ind.settings.rsiOrderFlow.toSignalEngineConfig(),
                    ).signals
                }
            }
        } else emptyList()

        val pivotSweepDivergenceSignals: List<ChartSignal> = if (
            ind.pivotSweepDivergence &&
            current.barMode == ChartBarMode.TIME &&
            timeframe.minutes < Timeframe.D1.minutes
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    pivotSweepDivergenceEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = candles,
                        config = ind.settings.pivotSweepDivergence.toEngineConfig(),
                    ).signals.map { signal -> signal.toChartSignal(candles.lastIndex) }
                }
            }
        } else emptyList()

        val valueAreaLiquidityAnalysis = if (
            ind.valueAreaLiquidityRejection &&
            current.barMode == ChartBarMode.TIME &&
            timeframe.minutes < Timeframe.D1.minutes
        ) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    valueAreaLiquidityRejectionEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = candles,
                        config = ind.settings.valueAreaLiquidityRejection.toEngineConfig(),
                    )
                }
            }
        } else null
        val valueAreaLiquiditySignals = valueAreaLiquidityAnalysis?.signals.orEmpty().map { signal ->
            signal.toChartSignal(candles.lastIndex)
        }

        val amdAnalysisSignals = if (
            ind.amd &&
            current.barMode == ChartBarMode.TIME
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    amdEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = candles,
                        config = ind.settings.amd.toEngineConfig(),
                    ).signals
                }
            }
        } else emptyList()
        val amdSignals = amdAnalysisSignals.map { signal -> signal.toChartSignal(candles.lastIndex) }

        // Replay hands the engine the revealed prefix, which is exactly what
        // live hands it, so the same arrows appear at the same bars.
        val nascentAnalysis = if (
            ind.nascent && current.barMode == ChartBarMode.TIME
        ) {
            withContext(defaultDispatcher) {
                containedOrNull {
                    nascentEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = candles,
                        config = ind.settings.nascent.toEngineConfig(),
                    )
                }
            }
        } else null
        val nascentSignals: List<ChartSignal> = nascentAnalysis?.signals.orEmpty()
            .map { signal -> signal.toChartSignal(candles.lastIndex) }

        val productionHistory = if ((ind.litX || ind.lit) && candles.isNotEmpty()) {
            withContext(defaultDispatcher) {
                scanProductionSignalHistory(
                    indicators = ind,
                    symbol = symbol,
                    timeframe = timeframe,
                    barMode = current.barMode,
                    candles = candles,
                    litXConfig = litXConfig,
                    litConfig = appPreferences.litConfig.value.sanitized(),
                )
            }
        } else {
            emptyList()
        }

        val strategySignals = when {
            ind.allStrategies -> withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    liveStrategyEngine.evaluateAll(candles, symbol = symbol, timeframe = timeframe)
                }
            }
            ind.activeStrategy != null -> withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    liveStrategyEngine.evaluate(
                        type = ind.activeStrategy,
                        candles = candles,
                        symbol = symbol,
                        timeframe = timeframe,
                    )
                }
            }
            ind.activeBlueprintId != null -> {
                val blueprint = current.strategyBlueprints.firstOrNull { it.id == ind.activeBlueprintId }
                if (blueprint == null) emptyList() else withContext(defaultDispatcher) {
                    containedOrDefault(emptyList()) {
                        val compiled = compileBlueprint(blueprint)
                        liveStrategyEngine.evaluateCustom(
                            strategyId = compiled.id,
                            strategyName = compiled.name,
                            minimumBars = compiled.minBars,
                            function = { series, index -> scriptEngine.evaluate(compiled, series, index) },
                            candles = candles,
                        )
                    }
                }
            }
            else -> emptyList()
        }

        val binary3mSignals: List<ChartSignal> = if (
            ind.binary3m &&
            current.dataProvider == DataProvider.DERIV &&
            timeframe == Timeframe.M1
        ) {
            withContext(defaultDispatcher) {
                containedOrDefault(emptyList()) {
                    derivBinary3mSignalEngine
                        .evaluateAll(candles, DerivBinary3mSignalEngine.DEFAULT_MIN_CONFIDENCE)
                        .takeLast(BINARY3M_MAX_CHART_SIGNALS)
                        .mapNotNull { binary ->
                            val candle = candles.getOrNull(binary.signalIndex) ?: return@mapNotNull null
                            ChartSignal(
                                id = "binary3m_${symbol}_${binary.timestamp}_${binary.direction.name}",
                                source = SignalSource.BINARY3M,
                                direction = binary.direction,
                                entry = candle.close,
                                sl = 0.0,
                                tp = 0.0,
                                barIndex = binary.signalIndex,
                                timestamp = binary.timestamp,
                                confidence = binary.confidence.toDouble(),
                                isLive = binary.signalIndex == candles.lastIndex,
                                label = "Deriv 3m ${if (binary.direction == Direction.BULLISH) "CALL" else "PUT"} · replay",
                            )
                        }
                }
            }
        } else emptyList()

        if (
            gen != computationGeneration.get() ||
            !replayEngine.state.value.isActive ||
            replayEngine.state.value.currentIndex != replay.currentIndex
        ) return

        val chartSignals = signalComputer.computeSignals(
            litXAnalysis = litXAnalysis,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = productionHistory + pivotSweepDivergenceSignals + valueAreaLiquiditySignals + amdSignals + nascentSignals + strategySignals + binary3mSignals,
            litAnalysis = litAnalysis,
            smsAnalysis = smsAnalysis,
            rsiOrderFlowSignals = rsiOrderFlowSignals,
            latestConfirmedIndex = candles.lastIndex,
            fusion = null,
        )
        val latest = _uiState.value
        _uiState.value = latest.withReplayComputation(
            replayCandles = candles,
            computation = computation,
            toggles = ind,
            signals = chartSignals,
            barMode = current.barMode,
            litXAnalysis = litXAnalysis,
            litAnalysis = litAnalysis,
            smsAnalysis = smsAnalysis,
        ).copy(
            valueAreaLiquidityProfiles = valueAreaLiquidityAnalysis?.profiles.orEmpty().toPersistentList(),
            amdZones = amdAnalysisSignals.toPersistentList(),
            nascentKeyLevels = nascentAnalysis?.keyLevels.orEmpty().toPersistentList(),
        )
    }

    /**
     * Reconstruct historical LiT signals without look-ahead. Every candidate is
     * evaluated on the candle prefix that was available at that moment, then
     * retained under a stable event identity. The cache is bounded and scans
     * only newly revealed bars after first activation.
     */
    private fun scanProductionSignalHistory(
        indicators: IndicatorToggles,
        symbol: String,
        timeframe: Timeframe,
        barMode: ChartBarMode,
        candles: List<Candle>,
        litXConfig: LitXConfig,
        litConfig: LitConfig,
    ): List<ChartSignal> = synchronized(productionHistoryLock) {
        if (candles.isEmpty() || (!indicators.litX && !indicators.lit)) {
            return@synchronized emptyList()
        }

        val key = ProductionHistoryKey(
            provider = _uiState.value.dataProvider,
            symbol = symbol.trim().uppercase(),
            timeframe = timeframe,
            barMode = barMode,
            firstTimestamp = candles.first().timestamp,
            litXEnabled = indicators.litX,
            litEnabled = indicators.lit,
            litXConfig = litXConfig.copy(enabled = true).sanitized(),
            litConfig = litConfig.sanitized(),
        )
        if (productionHistoryKey != key) {
            productionHistoryKey = key
            productionHistoryScannedThrough = -1
            productionHistorySignals.clear()
        }

        val lastIndex = candles.lastIndex
        if (lastIndex > productionHistoryScannedThrough) {
            val firstWanted = (lastIndex - PRODUCTION_HISTORY_SCAN_BARS + 1).coerceAtLeast(0)
            val scanFrom = maxOf(productionHistoryScannedThrough + 1, firstWanted)
            for (confirmationIndex in scanFrom..lastIndex) {
                val contextStart = (confirmationIndex - PRODUCTION_HISTORY_CONTEXT_BARS + 1).coerceAtLeast(0)
                val prefix = candles.subList(contextStart, confirmationIndex + 1)

                if (indicators.litX) {
                    containedOrNull {
                        litXEngine.analyze(symbol, timeframe, prefix, key.litXConfig)
                    }?.signal
                        ?.takeIf {
                            it.confirmationIndex == prefix.lastIndex &&
                                it.timestamp == candles[confirmationIndex].timestamp
                        }
                        ?.let { signal ->
                            val marker = ChartSignal(
                                id = "litx_${signal.timestamp}",
                                source = SignalSource.LITX,
                                direction = signal.direction,
                                entry = signal.entry,
                                sl = signal.stopLoss,
                                tp = signal.takeProfit1,
                                barIndex = confirmationIndex,
                                timestamp = signal.timestamp,
                                confidence = signal.confidence.score.toDouble() / 100.0,
                                isLive = false,
                                label = compactSignalLabel(
                                    "LiT Adventure",
                                    signal.confidence.score,
                                    signal.confirmations,
                                ),
                                variant = signal.confirmations
                                    .firstOrNull { it.startsWith("MODE_") }
                                    ?.removePrefix("MODE_"),
                                eventKey = SignalIdentity.litX(
                                    symbol = signal.symbol,
                                    timeframe = signal.timeframe,
                                    timestamp = signal.timestamp,
                                    direction = signal.direction,
                                    confirmationIndex = confirmationIndex,
                                ),
                            )
                            productionHistorySignals.putIfAbsent(marker.eventKey ?: marker.id, marker)
                        }
                }

                if (indicators.lit) {
                    containedOrNull {
                        litEngine.analyze(symbol, timeframe, prefix, key.litConfig)
                    }?.signal
                        ?.takeIf {
                            it.confirmationIndex == prefix.lastIndex &&
                                it.timestamp == candles[confirmationIndex].timestamp
                        }
                        ?.let { signal ->
                            val marker = ChartSignal(
                                id = "lit_${signal.symbol}_${signal.timestamp}_${signal.direction}",
                                source = SignalSource.LIT,
                                direction = signal.direction,
                                entry = signal.entry,
                                sl = signal.stopLoss,
                                tp = signal.takeProfit,
                                barIndex = confirmationIndex,
                                timestamp = signal.timestamp,
                                confidence = signal.confidence.toDouble() / 100.0,
                                isLive = false,
                                label = compactSignalLabel(
                                    "LiT May Madness",
                                    signal.confidence,
                                    signal.confirmations,
                                ),
                                eventKey = SignalIdentity.lit(
                                    symbol = signal.symbol,
                                    timeframe = signal.timeframe,
                                    timestamp = signal.timestamp,
                                    direction = signal.direction,
                                    confirmationIndex = confirmationIndex,
                                ),
                            )
                            productionHistorySignals.putIfAbsent(marker.eventKey ?: marker.id, marker)
                        }
                }
            }
            productionHistoryScannedThrough = lastIndex
            while (productionHistorySignals.size > PRODUCTION_HISTORY_MAX_SIGNALS) {
                val oldest = productionHistorySignals.keys.firstOrNull() ?: break
                productionHistorySignals.remove(oldest)
            }
        }

        productionHistorySignals.values
            .asSequence()
            .filter { it.barIndex <= lastIndex }
            .map { it.copy(isLive = it.barIndex == lastIndex) }
            .sortedBy { it.barIndex }
            .toList()
    }

    private fun compactSignalLabel(name: String, confidence: Int, confirmations: List<String>): String {
        val reasons = confirmations.take(3).joinToString(" · ")
        return if (reasons.isBlank()) "$name $confidence" else "$name $confidence · $reasons"
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
                // `CRASH-SAFETY` This collector has no awaiting caller: a
                // throwing restore would be an unhandled flow failure that
                // kills the app. Degrade to skipping the restore; the next
                // emission retries.
                try {
                    drawingEngine.restore(drawings)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Skip this emission; a later one restores the drawings.
                }
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

    /** Selects the built-in strategy used by the on-chart backtest. */
    fun selectChartBacktestStrategy(type: StrategyType) {
        val current = _uiState.value.chartBacktest
        if (current.selectedStrategy == type && current.error == null) return
        _uiState.value = _uiState.value.copy(
            chartBacktest = ChartBacktestState(
                selectedStrategy = type,
                selectedRange = current.selectedRange,
                selectedBlueprintId = null,
                showMarkers = current.showMarkers,
            ),
        )
    }

    /** Selects how much history the on-chart backtest should preload and test. */
    fun selectChartBacktestRange(range: ChartBacktestRange) {
        val current = _uiState.value.chartBacktest
        if (current.selectedRange == range && current.error == null) return
        _uiState.value = _uiState.value.copy(
            chartBacktest = ChartBacktestState(
                selectedStrategy = current.selectedStrategy,
                selectedRange = range,
                selectedBlueprintId = current.selectedBlueprintId,
                showMarkers = current.showMarkers,
            ),
        )
    }

    /** Selects a saved visual-builder strategy for the on-chart backtest. */
    fun selectChartBacktestBlueprint(blueprintId: String) {
        val current = _uiState.value.chartBacktest
        if (current.selectedBlueprintId == blueprintId && current.error == null) return
        if (_uiState.value.strategyBlueprints.none { it.id == blueprintId }) return
        _uiState.value = _uiState.value.copy(
            chartBacktest = ChartBacktestState(
                selectedStrategy = current.selectedStrategy,
                selectedRange = current.selectedRange,
                selectedBlueprintId = blueprintId,
                showMarkers = current.showMarkers,
            ),
        )
    }

    /** Shows/hides completed backtest entries and W/L exit badges on the price chart. */
    fun toggleChartBacktestMarkers() {
        val backtest = _uiState.value.chartBacktest
        _uiState.value = _uiState.value.copy(
            chartBacktest = backtest.copy(showMarkers = !backtest.showMarkers),
        )
    }

    /** Clears only the chart backtest projection; live strategy signals are unaffected. */
    fun clearChartBacktest() {
        val current = _uiState.value.chartBacktest
        _uiState.value = _uiState.value.copy(
            chartBacktest = ChartBacktestState(
                selectedStrategy = current.selectedStrategy,
                selectedRange = current.selectedRange,
                selectedBlueprintId = current.selectedBlueprintId,
                showMarkers = current.showMarkers,
            ),
        )
    }

    /**
     * Runs the exact [StrategyLibrary] function used by the live chart through
     * [BacktestEngine] on the currently loaded real candle series. Results are
     * projected back to candle indices so entries/exits stay attached to their
     * historical bars. Synthetic data is deliberately rejected.
     */
    fun runChartBacktest() {
        if (_uiState.value.chartBacktest.isRunning) return
        viewModelScope.launch {
            val initialSnapshot = _uiState.value
            val previous = initialSnapshot.chartBacktest
            // Freeze the visible range at click-time. A pan/zoom while the
            // backtest is running must not silently change the tested bars.
            val requestedViewport = _primaryViewport.value ?: multiChartController.currentPrimaryViewportState()
            val runningState = previous.copy(
                isRunning = true,
                error = null,
                strategyName = null,
                totalSignals = 0,
                winningSignals = 0,
                losingSignals = 0,
                breakevenSignals = 0,
                winRate = 0.0,
                netPnL = 0.0,
                profitFactor = 0.0,
                returnPercent = 0.0,
                maxDrawdownPercent = 0.0,
                expectancy = 0.0,
                averageR = 0.0,
                markers = kotlinx.collections.immutable.persistentListOf(),
                equityCurve = kotlinx.collections.immutable.persistentListOf(),
                testedBars = 0,
                testedFromTimestamp = 0L,
                testedThroughTimestamp = 0L,
                rangeCoverageComplete = true,
                historyNotice = null,
                sourceBarsAtRun = 0,
                sourceNewestTimestampAtRun = 0L,
            )
            _uiState.value = initialSnapshot.copy(chartBacktest = runningState)

            if (initialSnapshot.isSyntheticData) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Backtest disabled for simulated data. Connect a real market-data provider first.",
                    ),
                )
                return@launch
            }
            if (initialSnapshot.barMode != ChartBarMode.TIME) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Backtest uses executable market prices. Switch chart bar mode to Time first.",
                    ),
                )
                return@launch
            }

            val now = System.currentTimeMillis()
            val timeframeMillis = initialSnapshot.timeframe.minutes.toLong() * 60_000L
            val visibleRangeRequested = previous.selectedRange == ChartBacktestRange.VISIBLE
            val targetStartTimestamp = if (visibleRangeRequested) {
                null
            } else {
                previous.selectedRange.days?.let { days -> now - days.toLong() * MILLIS_PER_DAY }
            }
            // Date-range research preloads extra bars before the requested
            // start so EMA/ATR/SMC/LiT state is warm when the measured
            // interval begins. Those warm-up bars are never counted/traded.
            val historyLoadStartTimestamp = targetStartTimestamp?.let { target ->
                target - CHART_BACKTEST_WARMUP_BARS.toLong() * timeframeMillis
            }
            var rangeCoverageComplete = true
            var historyNotice: String? = null

            if (historyLoadStartTimestamp != null) {
                val prefetch = dataController.preloadHistoryBackTo(
                    targetStartTimestamp = historyLoadStartTimestamp,
                    maxTotalBars = CHART_BACKTEST_MAX_VISIBLE_BARS,
                ) { loading, endReached, error ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        isLoadingOlder = loading,
                        historyEndReached = endReached,
                        error = error,
                    )
                }
                val prefetchResult = prefetch.getOrElse { error ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        chartBacktest = current.chartBacktest.copy(
                            isRunning = false,
                            error = error.message ?: "Failed to preload backtest history.",
                        ),
                    )
                    return@launch
                }
                rangeCoverageComplete = prefetchResult.reachedTarget
                if (!rangeCoverageComplete) {
                    historyNotice = when {
                        prefetchResult.providerExhausted ->
                            "Provider history ended before the full ${previous.selectedRange.label} range was available."
                        prefetchResult.totalVisibleBars >= CHART_BACKTEST_MAX_VISIBLE_BARS ->
                            "Chart history reached the ${CHART_BACKTEST_MAX_VISIBLE_BARS} bar safety cap before the full ${previous.selectedRange.label} range."
                        else -> "Only the available portion of the requested ${previous.selectedRange.label} range was tested."
                    }
                }
            }

            val runSnapshot = _uiState.value
            if (
                runSnapshot.symbol != initialSnapshot.symbol ||
                runSnapshot.timeframe != initialSnapshot.timeframe
            ) return@launch
            if (runSnapshot.isSyntheticData) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Backtest history became simulated while loading; run cancelled.",
                    ),
                )
                return@launch
            }

            val allSourceCandles = runSnapshot.candles.toList()
            val lastIsClosed = allSourceCandles.lastOrNull()?.let { candle ->
                candle.timestamp + timeframeMillis <= now
            } ?: false
            val closedCandles = if (lastIsClosed) allSourceCandles else allSourceCandles.dropLast(1)
            if (closedCandles.size < 2) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Need at least 2 closed candles for historical testing.",
                    ),
                )
                return@launch
            }

            val testWindow = when {
                visibleRangeRequested -> {
                    val viewport = requestedViewport
                    if (viewport == null) {
                        val current = _uiState.value
                        _uiState.value = current.copy(
                            chartBacktest = current.chartBacktest.copy(
                                isRunning = false,
                                error = "Visible range is unavailable until the chart viewport is initialized.",
                            ),
                        )
                        return@launch
                    }
                    HistoricalTestWindow.visible(
                        startIndex = viewport.startIndex,
                        visibleBars = viewport.visibleBars,
                        lastIndex = closedCandles.lastIndex,
                    ).also {
                        historyNotice = "Visible chart range tested with causal warm-up; bars after the selected end were excluded."
                    }
                }
                targetStartTimestamp != null -> {
                    val firstInRange = closedCandles.indexOfFirst { it.timestamp >= targetStartTimestamp }
                    if (firstInRange < 0) {
                        val current = _uiState.value
                        _uiState.value = current.copy(
                            chartBacktest = current.chartBacktest.copy(
                                isRunning = false,
                                error = "No closed candles are available inside the requested ${previous.selectedRange.label} range.",
                            ),
                        )
                        return@launch
                    }
                    HistoricalTestWindow(firstInRange, closedCandles.lastIndex)
                }
                else -> HistoricalTestWindow(0, closedCandles.lastIndex)
            }

            val resolved = try {
                val blueprint = previous.selectedBlueprintId?.let { id ->
                    runSnapshot.strategyBlueprints.firstOrNull { it.id == id }
                }
                if (blueprint != null) {
                    val compiled = withContext(defaultDispatcher) { compileBlueprint(blueprint) }
                    ChartBacktestStrategy(
                        name = compiled.name,
                        minimumBars = compiled.minBars,
                        function = { series, index -> scriptEngine.evaluate(compiled, series, index) },
                    )
                } else {
                    val definition = strategyLibrary.get(
                        previous.selectedStrategy,
                        runSnapshot.symbol,
                        runSnapshot.timeframe,
                    )
                    ChartBacktestStrategy(
                        name = definition.name,
                        minimumBars = definition.minimumBars,
                        function = definition.function,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = e.message ?: "Strategy unavailable.",
                    ),
                )
                return@launch
            }

            val requiredWarmup = resolved.minimumBars.coerceAtLeast(CHART_BACKTEST_MIN_BARS)
            if (testWindow.endIndex + 1 < requiredWarmup) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Need at least $requiredWarmup closed bars before the selected range end for strategy warm-up. Available ${testWindow.endIndex + 1}.",
                    ),
                )
                return@launch
            }
            if (testWindow.barCount < CHART_BACKTEST_MIN_SELECTED_BARS) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Selected history is too small (${testWindow.barCount} bars). Zoom out/select at least $CHART_BACKTEST_MIN_SELECTED_BARS bars.",
                    ),
                )
                return@launch
            }

            val currentBeforeRun = _uiState.value
            _uiState.value = currentBeforeRun.copy(
                chartBacktest = currentBeforeRun.chartBacktest.copy(
                    isRunning = true,
                    error = null,
                    strategyName = resolved.name,
                    historyNotice = historyNotice,
                    rangeCoverageComplete = rangeCoverageComplete,
                ),
            )

            try {
                val result = withContext(defaultDispatcher) {
                    val executionConfig = BacktestConfig(
                        initialBalance = CHART_BACKTEST_INITIAL_BALANCE,
                        riskPercent = CHART_BACKTEST_RISK_PERCENT,
                        contractSize = instrumentTypeResolver.resolve(runSnapshot.symbol).contractSize.toInt(),
                    )
                    historicalBacktestRunner(
                        candles = closedCandles,
                        strategy = resolved.function,
                        window = testWindow,
                        symbol = runSnapshot.symbol,
                        timeframe = runSnapshot.timeframe,
                        config = executionConfig,
                    )
                }

                // Ignore a completed run if the user changed symbol/timeframe while it was computing.
                val current = _uiState.value
                if (current.symbol != runSnapshot.symbol || current.timeframe != runSnapshot.timeframe) return@launch
                _uiState.value = current.copy(
                    chartBacktest = ChartBacktestMapper.map(
                        result = result,
                        strategyName = resolved.name,
                        previous = current.chartBacktest.copy(
                            selectedStrategy = previous.selectedStrategy,
                            selectedRange = previous.selectedRange,
                            selectedBlueprintId = previous.selectedBlueprintId,
                        ),
                        sourceBarCount = allSourceCandles.size,
                        sourceNewestTimestamp = allSourceCandles.lastOrNull()?.timestamp ?: 0L,
                        rangeCoverageComplete = rangeCoverageComplete,
                        historyNotice = historyNotice,
                    ),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = e.message ?: "Chart backtest failed.",
                    ),
                )
            }
        }
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

    fun currentLitXConfig(): LitXConfig = appPreferences.litXConfig.value.copy(enabled = true).sanitized()
    fun currentLitConfig(): LitConfig = appPreferences.litConfig.value.sanitized()
    fun currentSmtConfig(): SmtConfig = appPreferences.smtConfig.value.sanitized()
    fun currentSmsConfig(): SmsConfig = appPreferences.smsConfig.value.sanitized()

    fun updateLitXConfig(config: LitXConfig) {
        appPreferences.setLitXConfig(config.copy(enabled = true).sanitized())
    }

    fun updateLitConfig(config: LitConfig) {
        appPreferences.setLitConfig(config.sanitized())
    }

    fun updateSmtConfig(config: SmtConfig) {
        appPreferences.setSmtConfig(config.sanitized())
    }

    fun updateSmsConfig(config: SmsConfig) {
        appPreferences.setSmsConfig(config.sanitized())
    }

    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
        val current = _uiState.value.indicators
        val updated = transform(current)
        // The chart toggle is authoritative now that LiT Adventure no longer
        // has a global Settings enable switch.
        if (!current.litX && updated.litX && !appPreferences.litXConfig.value.enabled) {
            appPreferences.setLitXConfig(appPreferences.litXConfig.value.copy(enabled = true).sanitized())
        }
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
                val replay = replayEngine.state.value
                if (replay.isActive) {
                    processReplayFrame(replay)
                } else {
                    dataController.processMergedCandles(preferIncremental = false)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the last good frame. The next replay/data emission retries.
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

    /**
     * Switch the primary chart to an explicit provider without mixing the old
     * provider's live route/history buffer into the new feed. The persisted
     * preference is still shared with Scanner/Markets, while the chart state
     * surfaces the active source so Deriv and other feeds are
     * visibly distinct.
     */
    fun onDataProviderChange(provider: DataProvider) {
        val current = _uiState.value
        if (current.dataProvider == provider) return

        // Stop the old transport first. ProviderMarketWebSocket also observes
        // the preference, but explicit teardown prevents an old-provider tick
        // from racing into Room during the switch.
        if (current.liveEnabled) dataController.disconnectLive()
        _uiState.value = current.copy(
            dataProvider = provider,
            liveEnabled = false,
            connectionState = ConnectionState.DISCONNECTED,
            isLoading = true,
            error = null,
        )
        viewModelScope.launch {
            // CandleEntity is provider-agnostic. Purge the global hot cache
            // before committing the new provider or requesting its history,
            // otherwise old exchange/broker bars could survive at timestamps
            // the new source did not return and contaminate signals/backtests.
            repository.clearMarketDataCache()
            appPreferences.setDataProvider(provider)
            // Drain any old-route tick that raced the preference observer. Once
            // the preference flips, ProviderMarketWebSocket drops old-socket
            // events by route identity, so this second purge closes the window.
            repository.clearMarketDataCache()
            resetChartContext()
            refresh()
        }
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
            dataFreshness = MarketDataFreshness.CACHED, dataAgeMs = null,
            showSymbolPicker = if (clearSymbolPicker) false else _uiState.value.showSymbolPicker,
            aiDecision = null, confluence = null,
            signals = emptyList(), liveSignalStats = LiveSignalPerformanceStats(),
            chartBacktest = ChartBacktestState(
                selectedStrategy = _uiState.value.chartBacktest.selectedStrategy,
                selectedRange = _uiState.value.chartBacktest.selectedRange,
                selectedBlueprintId = _uiState.value.chartBacktest.selectedBlueprintId,
                showMarkers = _uiState.value.chartBacktest.showMarkers,
            ),
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
        liveUserOverrideOff = !enabled
        _uiState.value = current.copy(liveEnabled = enabled)
        dataController.toggleLive(!enabled)
    }

    fun onBarModeChange(barMode: ChartBarMode) {
        val current = _uiState.value
        val backtest = current.chartBacktest
        _uiState.value = current.copy(
            barMode = barMode,
            signals = emptyList(),
            liveSignalStats = LiveSignalPerformanceStats(),
            chartBacktest = ChartBacktestState(
                selectedStrategy = backtest.selectedStrategy,
                selectedRange = backtest.selectedRange,
                selectedBlueprintId = backtest.selectedBlueprintId,
                showMarkers = backtest.showMarkers,
            ),
        )
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
    /**
     * Starts a closed-bar replay. The toolbar default uses the current visible
     * chart window as a hard range, matching TradingView-style historical
     * practice. Supplying an explicit positive [startAt] retains the legacy
     * whole-tail replay API for existing callers/tests.
     */
    fun startReplay(startAt: Int = -1) {
        val chart = _uiState.value
        val source = chart.candles.toList()
        val latestConfirmed = ConfirmedBarPolicy.latestConfirmedIndex(
            source,
            chart.timeframe,
            System.currentTimeMillis(),
        )
        if (latestConfirmed < 1) return
        val closed = source.subList(0, latestConfirmed + 1)

        if (startAt >= 1) {
            replayEngine.start(closed, startAt)
            return
        }

        val viewport = _primaryViewport.value ?: multiChartController.currentPrimaryViewportState()
        if (viewport == null) {
            replayEngine.start(
                closed,
                (closed.size - DEFAULT_REPLAY_TAIL_BARS).coerceAtLeast(1),
            )
            return
        }

        val window = HistoricalTestWindow.visible(
            startIndex = viewport.startIndex,
            visibleBars = viewport.visibleBars,
            lastIndex = closed.lastIndex,
        )
        if (window.barCount >= 2) {
            replayEngine.startRange(closed, window.startIndex, window.endIndex)
        } else {
            replayEngine.start(closed, (window.startIndex + 1).coerceIn(1, closed.lastIndex))
        }
    }
    fun stopReplay() = replayEngine.stop()
    fun toggleReplayPlayPause() = replayEngine.togglePlayPause()
    fun replayStepForward() = replayEngine.stepForward()
    fun replayStepBackward() = replayEngine.stepBackward()
    fun replayCycleSpeed() = replayEngine.cycleSpeed()

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    override fun onCleared() {
        multiChartController.cancelAllPanelJobs()
        replayEngine.stop()
        // viewModelScope is already cancelled here. Never block the main thread
        // on websocket teardown; perform a bounded best-effort disconnect on an
        // independent dispatcher and then dispose that cleanup scope.
        lifecycleCleanupScope.launch {
            try {
                withTimeoutOrNull(WEBSOCKET_CLEANUP_TIMEOUT_MS) {
                    webSocket.disconnectAll()
                }
            } finally {
                lifecycleCleanupScope.cancel()
            }
        }
        super.onCleared()
    }

    private data class ChartBacktestStrategy(
        val name: String,
        val minimumBars: Int,
        val function: com.foxtrader.app.domain.usecase.backtest.StrategyFunction,
    )

    private data class ProductionHistoryKey(
        val provider: DataProvider,
        val symbol: String,
        val timeframe: Timeframe,
        val barMode: ChartBarMode,
        val firstTimestamp: Long,
        val litXEnabled: Boolean,
        val litEnabled: Boolean,
        val litXConfig: LitXConfig,
        val litConfig: LitConfig,
    )

    private companion object {
        const val BINARY3M_MAX_CHART_SIGNALS = 24
        const val DEFAULT_REPLAY_TAIL_BARS = 120
        const val CHART_BACKTEST_MIN_BARS = 80
        const val CHART_BACKTEST_MIN_SELECTED_BARS = 20
        const val CHART_BACKTEST_WARMUP_BARS = 320
        const val CHART_BACKTEST_INITIAL_BALANCE = 100_000.0
        const val CHART_BACKTEST_RISK_PERCENT = 1.0
        const val CHART_BACKTEST_MAX_VISIBLE_BARS = 20_000
        const val MILLIS_PER_DAY = 86_400_000L
        const val WEBSOCKET_CLEANUP_TIMEOUT_MS = 2_000L
        // Twelve hours of M1 bars (or materially more context on higher TFs)
        // gives the trader a useful visual accuracy sample without turning an
        // on-chart toggle into an unbounded full-database backtest.
        const val PRODUCTION_HISTORY_SCAN_BARS = 720
        const val PRODUCTION_HISTORY_CONTEXT_BARS = 640
        const val PRODUCTION_HISTORY_MAX_SIGNALS = 160
        /** Bars sampled when deriving an automatic Renko brick from volatility. */
        const val RENKO_AUTO_ATR_WINDOW = 100

        /** Brick = ATR × this fraction (half an average bar's range). */
        const val RENKO_AUTO_ATR_FRACTION = 0.5

        /** Absolute floor as a fraction of price, so flat data still bricks. */
        const val RENKO_AUTO_MIN_PRICE_FRACTION = 0.0001
    }
}

private fun PivotSweepDivergenceEngine.Signal.toChartSignal(latestConfirmedIndex: Int): ChartSignal =
    ChartSignal(
        id = "psd_${symbol}_${timestamp}_${direction.name}_${levelName.name}",
        source = SignalSource.PIVOT_SWEEP_DIVERGENCE,
        direction = direction,
        entry = entry,
        sl = stopLoss,
        tp = takeProfit,
        barIndex = confirmationIndex,
        timestamp = timestamp,
        confidence = confidence.toDouble(),
        isLive = confirmationIndex == latestConfirmedIndex,
        label = "PSD ${levelName.name} · ${reasons.take(4).joinToString(" · ")}",
        eventKey = SignalIdentity.pivotSweepDivergence(
            symbol = symbol,
            timeframe = timeframe,
            timestamp = timestamp,
            direction = direction,
            confirmationIndex = confirmationIndex,
        ),
        variant = mode.name,
    )

private fun ValueAreaLiquidityRejectionEngine.Signal.toChartSignal(latestConfirmedIndex: Int): ChartSignal =
    ChartSignal(
        id = "valr_${symbol}_${timestamp}_${direction.name}_${edge.name}",
        source = SignalSource.VALUE_AREA_LIQUIDITY_REJECTION,
        direction = direction,
        entry = entry,
        sl = stopLoss,
        tp = takeProfit,
        barIndex = confirmationIndex,
        timestamp = timestamp,
        confidence = confidence.toDouble(),
        isLive = confirmationIndex == latestConfirmedIndex,
        label = "VALR ${edge.name} · ${reasons.take(4).joinToString(" · ")}",
        eventKey = SignalIdentity.valueAreaLiquidityRejection(
            symbol = symbol,
            timeframe = timeframe,
            timestamp = timestamp,
            direction = direction,
            confirmationIndex = confirmationIndex,
        ),
        variant = mode.name,
    )

private fun com.foxtrader.app.domain.usecase.nascent.model.NascentSignal.toChartSignal(
    latestConfirmedIndex: Int,
): ChartSignal = ChartSignal(
    id = id,
    source = SignalSource.NASCENT,
    direction = direction,
    entry = entryPrice,
    sl = invalidationPrice ?: 0.0,
    tp = targetPrice ?: 0.0,
    barIndex = barIndex,
    timestamp = timestamp,
    confidence = score.toDouble(),
    isLive = barIndex == latestConfirmedIndex,
    label = "Nascent ${setupType.name} @ ${keyLevelType.name} · ${reasons.take(4).joinToString(" · ")}",
    eventKey = SignalIdentity.nascent(
        symbol = symbol,
        timeframe = internalTimeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = barIndex,
    ),
    variant = "${setupType.name}/${confidence.name}",
)

private fun AccumulationManipulationDistributionEngine.Signal.toChartSignal(latestConfirmedIndex: Int): ChartSignal =
    ChartSignal(
        id = "amd_${symbol}_${timestamp}_${direction.name}_${sweepIndex}",
        source = SignalSource.ACCUMULATION_MANIPULATION_DISTRIBUTION,
        direction = direction,
        entry = entry,
        sl = stopLoss,
        tp = takeProfit,
        barIndex = confirmationIndex,
        timestamp = timestamp,
        confidence = confidence.toDouble(),
        isLive = confirmationIndex == latestConfirmedIndex,
        label = "AMD · ${reasons.take(4).joinToString(" · ")}",
        eventKey = SignalIdentity.accumulationManipulationDistribution(
            symbol = symbol,
            timeframe = timeframe,
            timestamp = timestamp,
            direction = direction,
            confirmationIndex = confirmationIndex,
        ),
        variant = mode.name,
    )
