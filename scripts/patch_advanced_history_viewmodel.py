from pathlib import Path

path = Path("app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt")
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import com.foxtrader.app.domain.usecase.backtest.BacktestEngine\n",
    "import com.foxtrader.app.domain.usecase.backtest.BacktestEngine\n"
    "import com.foxtrader.app.domain.usecase.backtest.HistoricalBacktestRunner\n"
    "import com.foxtrader.app.domain.usecase.backtest.HistoricalTestWindow\n",
    "history imports",
)

replace_once(
    "    val performanceMonitor = ChartPerformanceMonitor(profiler, qualityController)\n",
    "    val performanceMonitor = ChartPerformanceMonitor(profiler, qualityController)\n"
    "    // Use the exact BacktestEngine instance already owned by the chart so\n"
    "    // visible-range research shares the same execution model and config.\n"
    "    private val historicalBacktestRunner = HistoricalBacktestRunner(backtestEngine)\n",
    "historical runner property",
)

replace_once(
    "            val initialSnapshot = _uiState.value\n"
    "            val previous = initialSnapshot.chartBacktest\n",
    "            val initialSnapshot = _uiState.value\n"
    "            val previous = initialSnapshot.chartBacktest\n"
    "            // Freeze the visible range at click-time. A pan/zoom while the\n"
    "            // backtest is running must not silently change the tested bars.\n"
    "            val requestedViewport = _primaryViewport.value ?: multiChartController.currentPrimaryViewportState()\n",
    "freeze visible viewport",
)

replace_once(
    "            val now = System.currentTimeMillis()\n"
    "            val targetStartTimestamp = previous.selectedRange.days?.let { days ->\n"
    "                now - days.toLong() * MILLIS_PER_DAY\n"
    "            }\n"
    "            var rangeCoverageComplete = true\n"
    "            var historyNotice: String? = null\n\n"
    "            if (targetStartTimestamp != null) {\n"
    "                val prefetch = dataController.preloadHistoryBackTo(\n"
    "                    targetStartTimestamp = targetStartTimestamp,\n",
    "            val now = System.currentTimeMillis()\n"
    "            val timeframeMillis = initialSnapshot.timeframe.minutes.toLong() * 60_000L\n"
    "            val visibleRangeRequested = previous.selectedRange == ChartBacktestRange.VISIBLE\n"
    "            val targetStartTimestamp = if (visibleRangeRequested) {\n"
    "                null\n"
    "            } else {\n"
    "                previous.selectedRange.days?.let { days -> now - days.toLong() * MILLIS_PER_DAY }\n"
    "            }\n"
    "            // Date-range research preloads extra bars before the requested\n"
    "            // start so EMA/ATR/SMC/LiT state is warm when the measured\n"
    "            // interval begins. Those warm-up bars are never counted/traded.\n"
    "            val historyLoadStartTimestamp = targetStartTimestamp?.let { target ->\n"
    "                target - CHART_BACKTEST_WARMUP_BARS.toLong() * timeframeMillis\n"
    "            }\n"
    "            var rangeCoverageComplete = true\n"
    "            var historyNotice: String? = null\n\n"
    "            if (historyLoadStartTimestamp != null) {\n"
    "                val prefetch = dataController.preloadHistoryBackTo(\n"
    "                    targetStartTimestamp = historyLoadStartTimestamp,\n",
    "range prefetch with warmup",
)

old_candle_block = '''            val allSourceCandles = runSnapshot.candles.toList()
            val rangedSourceCandles = if (targetStartTimestamp == null) {
                allSourceCandles
            } else {
                allSourceCandles.filter { it.timestamp >= targetStartTimestamp }
            }
            val timeframeMillis = runSnapshot.timeframe.minutes.toLong() * 60_000L
            val lastIsClosed = rangedSourceCandles.lastOrNull()?.let { candle ->
                candle.timestamp + timeframeMillis <= now
            } ?: false
            val candles = if (lastIsClosed) rangedSourceCandles else rangedSourceCandles.dropLast(1)
'''
new_candle_block = '''            val allSourceCandles = runSnapshot.candles.toList()
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
'''
replace_once(old_candle_block, new_candle_block, "selected historical window")

old_min = '''            if (candles.size < resolved.minimumBars.coerceAtLeast(CHART_BACKTEST_MIN_BARS)) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    chartBacktest = current.chartBacktest.copy(
                        isRunning = false,
                        error = "Need at least ${resolved.minimumBars.coerceAtLeast(CHART_BACKTEST_MIN_BARS)} real closed candles. Loaded ${candles.size}.",
                    ),
                )
                return@launch
            }
'''
new_min = '''            val requiredWarmup = resolved.minimumBars.coerceAtLeast(CHART_BACKTEST_MIN_BARS)
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
'''
replace_once(old_min, new_min, "range minimum bars")

old_run = '''                val result = withContext(defaultDispatcher) {
                    backtestEngine.updateConfig(
                        BacktestConfig(
                            initialBalance = CHART_BACKTEST_INITIAL_BALANCE,
                            riskPercent = CHART_BACKTEST_RISK_PERCENT,
                            contractSize = instrumentTypeResolver.resolve(runSnapshot.symbol).contractSize.toInt(),
                        ),
                    )
                    backtestEngine(
                        candles = candles,
                        strategy = resolved.function,
                        symbol = runSnapshot.symbol,
                        timeframe = runSnapshot.timeframe,
                    )
                }
'''
new_run = '''                val result = withContext(defaultDispatcher) {
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
'''
replace_once(old_run, new_run, "historical runner integration")

old_replay = '''    // --- Replay delegates ---
    fun startReplay(startAt: Int = 50) = replayEngine.start(_uiState.value.candles, startAt)
    fun stopReplay() = replayEngine.stop()
'''
new_replay = '''    // --- Replay delegates ---
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
'''
replace_once(old_replay, new_replay, "visible range replay")

replace_once(
    "        const val CHART_BACKTEST_MIN_BARS = 80\n",
    "        const val CHART_BACKTEST_MIN_BARS = 80\n"
    "        const val CHART_BACKTEST_MIN_SELECTED_BARS = 20\n"
    "        const val CHART_BACKTEST_WARMUP_BARS = 320\n",
    "backtest constants",
)

replace_once(
    "        const val BINARY3M_MAX_CHART_SIGNALS = 24\n",
    "        const val BINARY3M_MAX_CHART_SIGNALS = 24\n"
    "        const val DEFAULT_REPLAY_TAIL_BARS = 120\n",
    "replay tail constant",
)

path.write_text(text)
print("ChartViewModel advanced history patch applied")
