package com.foxtrader.app.feature.backtest.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestExecutionMode
import com.foxtrader.app.domain.model.BinaryBacktestConfig
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.sdk.script.ScriptEngine
import com.foxtrader.app.domain.usecase.backtest.AiScoredBacktestEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.backtest.LitXModeComparisonRunner
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.binary.BinaryBacktestEngine
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.strategies.StrategyLibrary
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.smt.SmtSignalEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.toPersistentList
import javax.inject.Inject

/**
 * Backtesting Lab ViewModel.
 *
 * Runs non-repainting strategy simulations on cached market data and optionally
 * scores every trade through the AI Master Decision pipeline.
 */
@HiltViewModel
class BacktestLabViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val backtestEngine: BacktestEngine,
    private val binaryBacktestEngine: BinaryBacktestEngine,
    private val aiScoredBacktestEngine: AiScoredBacktestEngine,
    private val analyticsEngine: BacktestAnalyticsEngine,
    private val litXModeComparisonRunner: LitXModeComparisonRunner,
    private val instrumentTypeResolver: InstrumentTypeResolver,
    private val tradeProEngine: TradeProSignalEngine,
    private val strategyLibrary: StrategyLibrary,
    private val rsiOrderFlowSignalEngine: RsiOrderFlowSignalEngine,
    private val amdEngine: AccumulationManipulationDistributionEngine,
    private val smtSignalEngine: SmtSignalEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val scriptEngine: ScriptEngine,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BacktestLabUiState(
            selectedBlueprintId = appPreferences.consumeRequestedBacktestBlueprintId(),
            dataProvider = appPreferences.dataProvider.value,
        ),
    )
    val uiState: StateFlow<BacktestLabUiState> = _uiState.asStateFlow()
    private var initialBacktestStarted = false
    private var replayJob: Job? = null

    init {
        appPreferences.strategyBlueprints
            .onEach { blueprints ->
                _uiState.update { state ->
                    val selectedId = state.selectedBlueprintId
                        ?.takeIf { id -> blueprints.any { it.id == id } }
                    val selectedBlueprint = selectedId?.let { id -> blueprints.first { it.id == id } }
                    state.copy(
                        strategyBlueprints = blueprints.toPersistentList(),
                        selectedBlueprintId = selectedId,
                        riskPercent = if (!initialBacktestStarted && selectedBlueprint != null) {
                            selectedBlueprint.action.riskPercent.coerceIn(0.1, 5.0)
                        } else {
                            state.riskPercent
                        },
                    )
                }
                if (!initialBacktestStarted) {
                    initialBacktestStarted = true
                    runBacktest()
                }
            }
            .launchIn(viewModelScope)
    }

    fun setSymbol(symbol: String) {
        _uiState.update {
            it.copy(
                symbol = symbol,
                result = null,
                binaryResult = null,
                analyticsReport = null,
                error = null,
                modeComparisonReport = null,
                modeComparisonError = null,
            )
        }
    }

    fun setDataProvider(provider: DataProvider) {
        if (!provider.implemented) return
        if (_uiState.value.isBinary3m && provider != DataProvider.DERIV) return
        appPreferences.setDataProvider(provider)
        _uiState.update {
            it.copy(
                dataProvider = provider,
                result = null,
                binaryResult = null,
                analyticsReport = null,
                error = null,
                modeComparisonReport = null,
                modeComparisonError = null,
            )
        }
    }

    fun setTimeframe(timeframe: Timeframe) {
        _uiState.update { state ->
            val resolved = if (state.isBinary3m) Timeframe.M1 else timeframe
            state.copy(
                timeframe = resolved,
                result = null,
                binaryResult = null,
                analyticsReport = null,
                error = null,
                modeComparisonReport = null,
                modeComparisonError = null,
            )
        }
    }

    fun setStrategy(strategy: BacktestStrategyTemplate) {
        val isBinary = strategy == BacktestStrategyTemplate.DERIV_BINARY_3M
        if (isBinary) appPreferences.setDataProvider(DataProvider.DERIV)
        _uiState.update {
            it.copy(
                strategy = strategy,
                selectedBlueprintId = null,
                timeframe = if (isBinary) Timeframe.M1 else it.timeframe,
                dataProvider = if (isBinary) DataProvider.DERIV else it.dataProvider,
                symbol = if (isBinary && it.symbol !in BacktestLabUiState.DERIV_BINARY_SYMBOLS) "EURUSD" else it.symbol,
                aiScoringEnabled = if (isBinary) false else it.aiScoringEnabled,
                result = null,
                binaryResult = null,
                analyticsReport = null,
                error = null,
                modeComparisonReport = null,
                modeComparisonError = null,
            )
        }
    }

    fun setBlueprint(id: String) {
        val blueprint = _uiState.value.strategyBlueprints.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                selectedBlueprintId = id,
                riskPercent = blueprint.action.riskPercent.coerceIn(0.1, 5.0),
                result = null,
                binaryResult = null,
                analyticsReport = null,
                error = null,
                modeComparisonReport = null,
                modeComparisonError = null,
            )
        }
    }

    fun setRiskPercent(value: Double) {
        _uiState.update {
            it.copy(
                riskPercent = value.coerceIn(0.1, 5.0),
                result = null,
                binaryResult = null,
                analyticsReport = null,
                modeComparisonReport = null,
                modeComparisonError = null,
            )
        }
    }

    fun setBinaryPayoutRatio(value: Double) {
        _uiState.update {
            it.copy(
                binaryPayoutRatio = value.coerceIn(0.50, 1.20),
                binaryResult = null,
                analyticsReport = null,
                error = null,
            )
        }
    }

    fun setBinaryMinConfidence(value: Int) {
        _uiState.update {
            it.copy(
                binaryMinConfidence = value.coerceIn(60, 90),
                binaryResult = null,
                analyticsReport = null,
                error = null,
            )
        }
    }

    fun setAiScoringEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                aiScoringEnabled = enabled && !state.isBinary3m,
                result = null,
                binaryResult = null,
                analyticsReport = null,
            )
        }
    }

    fun runBacktest() {
        if (_uiState.value.isComparingModes) return
        replayJob?.cancel()
        replayJob = null
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isRunning = true, replayPlaying = false, error = null) }
            try {
                // Backtests must measure the provider currently selected in
                // this screen, not an anonymous Room series left by a previous
                // venue. CandleEntity intentionally has no provider dimension,
                // so refresh is mandatory here and replaces the cached series
                // before we read it. Live-provider tests fail closed if refresh
                // degraded to the clearly-labelled synthetic seed.
                val requestedBars = if (state.isBinary3m) BINARY_BACKTEST_REFRESH_BARS else BACKTEST_REFRESH_BARS
                repository.refreshCandles(state.symbol, state.timeframe, requestedBars).getOrThrow()
                val sourced = repository.getSourcedCandles(state.symbol, state.timeframe)
                if (state.dataProvider != DataProvider.SAMPLE) {
                    require(sourced.source == CandleSource.LIVE) {
                        "Fresh ${state.dataProvider.displayName} data is required for this backtest; synthetic fallback is not allowed."
                    }
                }
                val candles = sourced.candles
                require(candles.size >= MIN_REQUIRED_BARS) {
                    "Need at least $MIN_REQUIRED_BARS candles for a reliable backtest. Got ${candles.size}."
                }

                if (state.isBinary3m) {
                    require(state.timeframe == Timeframe.M1) { "Deriv Binary 3m requires the 1-minute timeframe." }
                    require(state.dataProvider == DataProvider.DERIV) { "Select Deriv as the data provider for the Deriv Binary 3m backtest." }
                    val (binary, analytics) = withContext(defaultDispatcher) {
                        val result = binaryBacktestEngine(
                            candles = candles,
                            symbol = state.symbol,
                            timeframe = state.timeframe,
                            config = BinaryBacktestConfig(
                                initialBalance = state.initialBalance,
                                riskPercent = state.riskPercent,
                                payoutRatio = state.binaryPayoutRatio,
                                expiryBars = 3,
                                minConfidence = state.binaryMinConfidence,
                                allowOverlappingContracts = false,
                            ),
                        )
                        result to analyticsEngine.analyzeBinary(result)
                    }
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            result = null,
                            binaryResult = binary,
                            analyticsReport = analytics,
                            replayCandles = candles.toPersistentList(),
                            replayCursor = initialReplayCursor(candles),
                            replayPlaying = false,
                            lastRunTime = System.currentTimeMillis(),
                        )
                    }
                    return@launch
                }

                val config = BacktestConfig(
                    initialBalance = state.initialBalance,
                    riskPercent = state.riskPercent,
                    // Backtest Lab is intentionally TradingView-style: signals
                    // are decided from a closed bar and market fills occur at
                    // the next bar open. Direct engine callers retain the legacy
                    // default unless they explicitly select this mode.
                    executionMode = BacktestExecutionMode.NEXT_BAR_OPEN,
                    // Resolve the contract size for the instrument being tested so
                    // crypto/gold/index P&L is not computed as a forex lot.
                    contractSize = instrumentTypeResolver.resolve(state.symbol).contractSize.toInt(),
                )
                val correlatedCandles = if (state.strategy == BacktestStrategyTemplate.SMT) {
                    mtfContextProvider.getCorrelatedContext(
                        symbol = state.symbol,
                        timeframe = state.timeframe,
                        refreshMissing = true,
                    ).also { peers ->
                        require(peers.isNotEmpty()) {
                            "SMT requires at least one real correlated peer series for ${state.symbol} ${state.timeframe.label}."
                        }
                    }
                } else {
                    emptyMap()
                }
                val strategy = buildStrategy(state, correlatedCandles)

                val result = withContext(defaultDispatcher) {
                    if (state.aiScoringEnabled) {
                        aiScoredBacktestEngine.updateConfig(config)
                        aiScoredBacktestEngine(
                            candles = candles,
                            strategy = strategy,
                            symbol = state.symbol,
                            timeframe = state.timeframe,
                            dataSource = sourced.source,
                        )
                    } else {
                        backtestEngine.updateConfig(config)
                        backtestEngine(
                            candles = candles,
                            strategy = strategy,
                            symbol = state.symbol,
                            timeframe = state.timeframe,
                        )
                    }
                }

                val analytics = withContext(defaultDispatcher) {
                    analyticsEngine.analyze(result)
                }

                _uiState.update {
                    it.copy(
                        isRunning = false,
                        result = result,
                        binaryResult = null,
                        analyticsReport = analytics,
                        replayCandles = candles.toPersistentList(),
                        replayCursor = initialReplayCursor(candles),
                        replayPlaying = false,
                        lastRunTime = System.currentTimeMillis(),
                    )
                }
            } catch (cancel: CancellationException) {
                // Never swallow cancellation: doing so breaks structured
                // concurrency and surfaces a routine screen-close or
                // re-run as a spurious on-screen error.
                throw cancel
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        error = e.message ?: "Backtest failed.",
                    )
                }
            }
        }
    }

    /** Run every LiT Adventure mode against one identical, verified candle set. */
    fun runLitModeComparison() {
        val initial = _uiState.value
        if (!initial.canCompareLitModes || initial.isRunning || initial.isComparingModes) return

        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    isComparingModes = true,
                    modeComparisonCompleted = 0,
                    modeComparisonTotal = LitXMode.entries.size,
                    modeComparisonReport = null,
                    modeComparisonError = null,
                )
            }
            try {
                repository.refreshCandles(state.symbol, state.timeframe, BACKTEST_REFRESH_BARS).getOrThrow()
                val sourced = repository.getSourcedCandles(state.symbol, state.timeframe)
                if (state.dataProvider != DataProvider.SAMPLE) {
                    require(sourced.source == CandleSource.LIVE) {
                        "Fresh ${state.dataProvider.displayName} data is required for mode comparison; synthetic fallback is not allowed."
                    }
                }
                require(sourced.candles.size >= MIN_REQUIRED_BARS) {
                    "Need at least $MIN_REQUIRED_BARS candles for LiT mode comparison. Got ${sourced.candles.size}."
                }
                val config = BacktestConfig(
                    initialBalance = state.initialBalance,
                    riskPercent = state.riskPercent,
                    executionMode = BacktestExecutionMode.NEXT_BAR_OPEN,
                    contractSize = instrumentTypeResolver.resolve(state.symbol).contractSize.toInt(),
                )
                val report = withContext(defaultDispatcher) {
                    litXModeComparisonRunner(
                        candles = sourced.candles,
                        symbol = state.symbol,
                        timeframe = state.timeframe,
                        backtestConfig = config,
                        baseConfig = appPreferences.litXConfig.value,
                        onProgress = { completed, total ->
                            _uiState.update {
                                it.copy(
                                    modeComparisonCompleted = completed,
                                    modeComparisonTotal = total,
                                )
                            }
                        },
                    )
                }
                _uiState.update {
                    it.copy(
                        isComparingModes = false,
                        modeComparisonReport = report,
                        modeComparisonError = null,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isComparingModes = false,
                        modeComparisonError = error.message ?: "LiT mode comparison failed.",
                    )
                }
            }
        }
    }

    fun restartVisualReplay() {
        replayJob?.cancel()
        replayJob = null
        _uiState.update { state ->
            if (!state.hasReplayData) state else state.copy(
                replayCursor = initialReplayCursor(state.replayCandles),
                replayPlaying = false,
            )
        }
    }

    fun stepVisualReplay(delta: Int) {
        replayJob?.cancel()
        replayJob = null
        _uiState.update { state ->
            if (!state.hasReplayData) state else state.copy(
                replayCursor = (state.replayCursor + delta).coerceIn(0, state.replayCandles.lastIndex),
                replayPlaying = false,
            )
        }
    }

    fun seekVisualReplay(index: Int) {
        replayJob?.cancel()
        replayJob = null
        _uiState.update { state ->
            if (!state.hasReplayData) state else state.copy(
                replayCursor = index.coerceIn(0, state.replayCandles.lastIndex),
                replayPlaying = false,
            )
        }
    }

    fun toggleVisualReplayPlay() {
        val current = _uiState.value
        if (!current.hasReplayData) return
        if (current.replayPlaying) {
            replayJob?.cancel()
            replayJob = null
            _uiState.update { it.copy(replayPlaying = false) }
            return
        }
        if (current.replayCursor >= current.replayCandles.lastIndex) {
            _uiState.update { it.copy(replayCursor = initialReplayCursor(it.replayCandles)) }
        }
        replayJob?.cancel()
        replayJob = viewModelScope.launch {
            _uiState.update { it.copy(replayPlaying = true) }
            try {
                while (true) {
                    delay(VISUAL_REPLAY_TICK_MS)
                    var finished = false
                    _uiState.update { state ->
                        if (!state.hasReplayData) {
                            finished = true
                            state.copy(replayPlaying = false)
                        } else {
                            val next = state.replayCursor + 1
                            if (next > state.replayCandles.lastIndex) {
                                finished = true
                                state.copy(replayPlaying = false)
                            } else {
                                state.copy(replayCursor = next)
                            }
                        }
                    }
                    if (finished) break
                }
            } finally {
                replayJob = null
            }
        }
    }

    override fun onCleared() {
        replayJob?.cancel()
        replayJob = null
        super.onCleared()
    }

    private fun initialReplayCursor(candles: List<Candle>): Int =
        (VISUAL_REPLAY_WARMUP_BARS - 1).coerceIn(0, candles.lastIndex.coerceAtLeast(0))

    private fun buildStrategy(
        state: BacktestLabUiState,
        correlatedCandles: Map<String, List<Candle>> = emptyMap(),
    ): StrategyFunction {
        val blueprint = state.selectedBlueprint
        if (blueprint != null) {
            val compiled = scriptEngine.compileBlueprint(blueprint)
            return { candles, index -> scriptEngine.evaluate(compiled, candles, index) }
        }

        return when (state.strategy) {
            // Use the same production strategy definition as the strategy
            // library/live surfaces. This removes the former Backtest-Lab-only
            // RSI implementation that fired repeatedly on every extreme bar.
            BacktestStrategyTemplate.RSI_MEAN_REVERSION -> strategyLibrary.get(
                StrategyType.MEAN_REVERSION,
                state.symbol,
                state.timeframe,
            ).function
            BacktestStrategyTemplate.EMA_TREND_PULLBACK -> ::emaTrendPullback
            BacktestStrategyTemplate.ATR_BREAKOUT -> ::atrBreakout
            BacktestStrategyTemplate.TRADEPRO -> ::tradePro
            BacktestStrategyTemplate.DERIV_BINARY_3M -> error("Binary 3m uses BinaryBacktestEngine, not the SL/TP backtester.")
            BacktestStrategyTemplate.LITX -> strategyLibrary.get(
                StrategyType.LITX,
                state.symbol,
                state.timeframe,
            ).function
            BacktestStrategyTemplate.LIT_MAY_MADNESS -> strategyLibrary.get(
                StrategyType.LIT,
                state.symbol,
                state.timeframe,
            ).function
            BacktestStrategyTemplate.SMT -> smtSignalEngine.strategyFunction(
                primarySymbol = state.symbol,
                correlatedCandles = correlatedCandles,
            )
            BacktestStrategyTemplate.RSI_ORDERFLOW -> rsiOrderFlowSignalEngine.strategyFunction(
                symbol = state.symbol,
                timeframe = state.timeframe,
            )
            BacktestStrategyTemplate.AMD -> amdEngine.strategyFunction(
                symbol = state.symbol,
                timeframe = state.timeframe,
            )
        }
    }

    /**
     * TRADEPRO strategy for the backtester: runs the order-flow/auction engine on a trailing window
     * (recent structure only) and emits a signal solely when a setup reaches EXECUTE — i.e. price
     * pulled into a defended Buy/Sell-Hold zone with order-flow confirmation, with the trend.
     */
    private fun tradePro(candles: List<Candle>, index: Int): StrategySignal? {
        if (index < TRADEPRO_MIN_BARS || index >= candles.size) return null
        val window = candles.subList((index - TRADEPRO_WINDOW + 1).coerceAtLeast(0), index + 1)
        val setup = tradeProEngine.analyze(_uiState.value.symbol, window, appPreferences.tradeProConfig.value).setup ?: return null
        if (!setup.isExecutable) return null
        return StrategySignal(
            index = index,
            timestamp = candles[index].timestamp,
            direction = setup.direction,
            entry = setup.entry,
            stopLoss = setup.stopLoss,
            takeProfit = setup.target2,
            confidence = setup.confidence,
            setupType = BacktestStrategyTemplate.TRADEPRO.displayName,
        )
    }

    private fun emaTrendPullback(candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 60 || index >= candles.size) return null
        val atr = TechnicalIndicators.calculateATR(candles, 14).getOrNull(index)?.takeIf { it > 0.0 } ?: return null
        val fast = sma(candles, index, 20)
        val slow = sma(candles, index, 50)
        val last = candles[index]
        val prior = candles[index - 1]
        return when {
            fast > slow && last.low <= fast && last.close > fast && prior.close <= fast -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BULLISH,
                entry = last.close,
                stopLoss = last.close - atr * 2.0,
                takeProfit = last.close + atr * 3.0,
                setupType = BacktestStrategyTemplate.EMA_TREND_PULLBACK.displayName,
            )
            fast < slow && last.high >= fast && last.close < fast && prior.close >= fast -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BEARISH,
                entry = last.close,
                stopLoss = last.close + atr * 2.0,
                takeProfit = last.close - atr * 3.0,
                setupType = BacktestStrategyTemplate.EMA_TREND_PULLBACK.displayName,
            )
            else -> null
        }
    }

    private fun atrBreakout(candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 40 || index >= candles.size) return null
        val last = candles[index]
        val priorRange = candles.subList(index - BREAKOUT_LOOKBACK, index)
        val rangeHigh = priorRange.maxOf { it.high }
        val rangeLow = priorRange.minOf { it.low }
        val atr = TechnicalIndicators.calculateATR(candles, 14).getOrNull(index)?.takeIf { it > 0.0 } ?: return null
        return when {
            last.close > rangeHigh + atr * 0.15 -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BULLISH,
                entry = last.close,
                stopLoss = last.close - atr * 2.2,
                takeProfit = last.close + atr * 3.3,
                setupType = BacktestStrategyTemplate.ATR_BREAKOUT.displayName,
            )
            last.close < rangeLow - atr * 0.15 -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BEARISH,
                entry = last.close,
                stopLoss = last.close + atr * 2.2,
                takeProfit = last.close - atr * 3.3,
                setupType = BacktestStrategyTemplate.ATR_BREAKOUT.displayName,
            )
            else -> null
        }
    }

    private fun sma(candles: List<Candle>, index: Int, period: Int): Double {
        val start = (index - period + 1).coerceAtLeast(0)
        val slice = candles.subList(start, index + 1)
        return slice.sumOf { it.close } / slice.size
    }

    private companion object {
        const val BACKTEST_REFRESH_BARS = 1_000
        const val BINARY_BACKTEST_REFRESH_BARS = 5_000
        const val MIN_REQUIRED_BARS = 100
        const val BREAKOUT_LOOKBACK = 20
        const val TRADEPRO_MIN_BARS = 40
        const val TRADEPRO_WINDOW = 250
        const val VISUAL_REPLAY_WARMUP_BARS = 100
        const val VISUAL_REPLAY_TICK_MS = 350L
    }
}
