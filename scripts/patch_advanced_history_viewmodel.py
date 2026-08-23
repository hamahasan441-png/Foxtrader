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
    "    /** Preserve an explicit user choice to keep streaming off across symbol changes. */\n"
    "    private var liveUserOverrideOff = false\n",
    "    /** Preserve an explicit user choice to keep streaming off across symbol changes. */\n"
    "    private var liveUserOverrideOff = false\n\n"
    "    /** Replay owns chart overlays while active; live frames are suppressed. */\n"
    "    private var replayWasActive = false\n"
    "    private var lastReplayFrameIndex = -1\n",
    "replay state guards",
)

replace_once(
    "        refresh()\n"
    "    }\n\n"
    "    // ========================================================================\n"
    "    // INTERNAL PIPELINE WIRING\n",
    "        // Replay is a separate causal render clock. While active, each new\n"
    "        // revealed bar recomputes indicators/structure/primary-series signal\n"
    "        // engines from that prefix only; live websocket frames cannot replace\n"
    "        // the historical frame. Stopping replay restores the latest live frame.\n"
    "        replayEngine.state\n"
    "            .onEach { replay ->\n"
    "                if (replay.isActive) {\n"
    "                    replayWasActive = true\n"
    "                    if (lastReplayFrameIndex != replay.currentIndex) {\n"
    "                        lastReplayFrameIndex = replay.currentIndex\n"
    "                        processReplayFrame(replay)\n"
    "                    }\n"
    "                } else if (replayWasActive) {\n"
    "                    replayWasActive = false\n"
    "                    lastReplayFrameIndex = -1\n"
    "                    try {\n"
    "                        dataController.processMergedCandles(preferIncremental = false)\n"
    "                    } catch (cancel: CancellationException) {\n"
    "                        throw cancel\n"
    "                    } catch (_: Exception) {\n"
    "                        // A later market/history emission retries the normal frame.\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "            .launchIn(viewModelScope)\n"
    "        refresh()\n"
    "    }\n\n"
    "    // ========================================================================\n"
    "    // INTERNAL PIPELINE WIRING\n",
    "replay state collector",
)

replace_once(
    "    private suspend fun processCandles(source: CandleSource, preferIncremental: Boolean) {\n",
    "    private suspend fun processCandles(source: CandleSource, preferIncremental: Boolean) {\n"
    "        // Replay owns the visual computation clock. Keep ingesting market\n"
    "        // data in the repository, but never let a live frame overwrite a\n"
    "        // historical prefix while the trader is testing the past.\n"
    "        if (replayEngine.state.value.isActive) return\n",
    "suppress live frames during replay",
)

replay_method = r'''    private suspend fun processReplayFrame(replay: ReplayState) {
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
        val litXConfig = appPreferences.litXConfig.value
        val litXAnalysis = if (ind.litX && litXConfig.enabled) {
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
            strategySignals = strategySignals + binary3mSignals,
            litAnalysis = litAnalysis,
            smsAnalysis = smsAnalysis,
            latestConfirmedIndex = candles.lastIndex,
            fusion = null,
        )
        val latest = _uiState.value
        _uiState.value = latest.withReplayComputation(
            replayCandles = candles,
            computation = computation,
            toggles = ind,
            signals = chartSignals,
            litXAnalysis = litXAnalysis,
            litAnalysis = litAnalysis,
            smsAnalysis = smsAnalysis,
        )
    }

'''
replace_once(
    "    /**\n"
    "     * Volatility-derived Renko brick for the charted instrument: half the\n",
    replay_method +
    "    /**\n"
    "     * Volatility-derived Renko brick for the charted instrument: half the\n",
    "causal replay frame computation",
)

old_toggle = '''        viewModelScope.launch {
            try {
                dataController.processMergedCandles(preferIncremental = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Swallow concurrent modification exceptions during indicator toggle.
                // The next data emission will trigger a successful recompute.
            }
        }
'''
new_toggle = '''        viewModelScope.launch {
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
'''
# There are other launch blocks with similar content; anchor after the unique
# overlay-quality comment so only updateIndicators is changed.
anchor = '''        performanceMonitor.onOverlayConfigChanged()
''' + old_toggle
replace_once(
    anchor,
    '''        performanceMonitor.onOverlayConfigChanged()
''' + new_toggle,
    "indicator toggles during replay",
)

path.write_text(text)
print("ChartViewModel causal replay patch applied")
