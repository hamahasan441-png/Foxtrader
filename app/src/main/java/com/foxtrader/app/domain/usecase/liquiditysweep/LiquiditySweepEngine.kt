package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquidityLevel
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquiditySweep
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquiditySweepAnalysis
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquiditySweepSignal
import com.foxtrader.app.domain.usecase.liquiditysweep.model.SweepState
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liquidity Sweep — multi-timeframe scalping engine.
 *
 * Implements the model's five steps in order: read the higher-timeframe bias,
 * mark the key liquidity, wait for a sweep of it, enter on the reaction, and
 * place the stop behind what the sweep already rejected.
 *
 * ## Multi-timeframe without a second fetch
 *
 * The charted series is the execution timeframe. The two timeframes above it
 * are resampled from it, which is the only direction that adds no information
 * the execution bars did not already carry — so the chart, replay and the
 * backtester all see the same higher timeframe with no extra data path to
 * disagree about. [MultiTimeframeSeries] drops the unfinished trailing bucket
 * and records the execution bar each higher bar closed on, so nothing upstairs
 * is ever read before the execution series reached it.
 *
 * ## The property everything rests on
 *
 * [analyze] is a pure function of the closed-bar prefix: running it over
 * candles truncated at bar `t` returns exactly what the full-series run reports
 * for bars at or before `t`. Signals are published on the bar they became
 * confirmed, never on the bar the setup started forming.
 */
@Singleton
class LiquiditySweepEngine @Inject constructor(
    analyzeStructure: AnalyzeMarketStructureUseCase,
) {

    private val biasEngine = LiquiditySweepBiasEngine(analyzeStructure)
    private val levelEngine = LiquidityLevelEngine()
    private val sweepDetector = LiquiditySweepDetector()
    private val entryEngine = LiquiditySweepEntryEngine()

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: LiquiditySweepConfig = LiquiditySweepConfig(),
    ): LiquiditySweepAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) {
            return LiquiditySweepAnalysis.empty(integrity.reason ?: "Invalid market data.")
        }

        val pair = config.timeframesFor(timeframe)
            ?: return LiquiditySweepAnalysis.empty(
                "Liquidity Sweep: no higher timeframes mapped for ${timeframe.label}.",
            )

        val higher = MultiTimeframeSeries.from(candles, timeframe, pair.higher)
        val mid = MultiTimeframeSeries.from(candles, timeframe, pair.mid)
        if (higher.isEmpty || mid.isEmpty) {
            return LiquiditySweepAnalysis.empty(
                "Liquidity Sweep: not enough history for ${pair.higher.label}/${pair.mid.label} context.",
            )
        }

        val levels = levelEngine.levels(higher, mid, config)
        if (levels.isEmpty()) {
            return LiquiditySweepAnalysis.empty("Liquidity Sweep: no key liquidity marked yet.")
        }

        val signals = ArrayList<LiquiditySweepSignal>()
        val sweeps = ArrayList<LiquiditySweep>()
        val seen = HashSet<String>()
        var latestBias = biasEngine.biasAt(candles.lastIndex, higher, mid, config)
        var state = SweepState.LEVELS_MARKED

        // Liquidity that has been collected is gone. A level keeps re-arming
        // forever otherwise, and one shelf would produce a fresh setup on every
        // pullback that touched it — which is not what the model claims to be
        // trading, and inflates its frequency several times over.
        val consumed = ArrayList<Pair<Boolean, Double>>()

        val first = config.warmupBars.coerceAtLeast(MIN_BARS)
        for (bar in first..candles.lastIndex) {
            val active = levelEngine.activeAt(levels, bar, config)
                .filterNot { level ->
                    val tolerance = kotlin.math.abs(level.price) * config.levelClusterFraction
                    consumed.any { (side, price) ->
                        side == level.aboveMarket && kotlin.math.abs(price - level.price) <= tolerance
                    }
                }
            if (active.isEmpty()) continue

            val sweep = sweepDetector.sweepAt(candles, bar, active, config) ?: continue
            sweeps += sweep
            consumed += sweep.level.aboveMarket to sweep.level.price
            state = SweepState.RECLAIMED

            // Step 1 gates step 3: the bias as it stood when the sweep
            // confirmed, never as it stands now.
            val bias = biasEngine.biasAt(bar, higher, mid, config) ?: continue
            if (config.biasMode != BiasMode.NONE && bias.direction != sweep.direction) continue

            val entry = entryEngine.findEntry(candles, sweep, config) ?: continue
            state = SweepState.ENTRY_READY

            val geometry = entryEngine.geometry(
                direction = sweep.direction,
                entry = entry.price,
                sweep = sweep,
                opposingLevels = levelEngine.activeAt(levels, entry.index, config),
                config = config,
            ) ?: continue

            val signal = LiquiditySweepSignal(
                symbol = symbol,
                executionTimeframe = timeframe,
                bias = bias,
                sweep = sweep,
                entryType = entry.type,
                entryIndex = entry.index,
                timestamp = candles[entry.index].timestamp,
                entry = geometry.entry,
                stop = geometry.stop,
                target = geometry.target,
                reasons = buildReasons(bias, sweep, entry),
            )
            if (seen.add(signal.key)) signals += signal
            if (bar == candles.lastIndex) latestBias = bias
        }

        val published = if (config.historicalSignals) {
            signals
        } else {
            val cutoff = candles.lastIndex - config.liveWindowBars + 1
            signals.filter { it.entryIndex >= cutoff }
        }

        return LiquiditySweepAnalysis(
            bias = latestBias,
            levels = levels,
            sweeps = sweeps,
            signals = published.sortedBy { it.entryIndex },
            state = if (published.isEmpty()) state else SweepState.ENTRY_READY,
            statusText = statusText(latestBias, published),
        )
    }

    // ------------------------------------------------------------------
    // Backtest entry point
    // ------------------------------------------------------------------

    /** The signal confirmed exactly on [index], for the backtester. */
    fun signalAt(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
        config: LiquiditySweepConfig = LiquiditySweepConfig(),
    ): StrategySignal? {
        if (index !in candles.indices) return null
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)

        val signal = analyze(symbol, timeframe, visible, config).signals
            .lastOrNull { it.entryIndex == index } ?: return null

        return StrategySignal(
            index = index,
            timestamp = signal.timestamp,
            direction = signal.direction,
            entry = signal.entry,
            stopLoss = signal.stop,
            takeProfit = signal.target,
            confidence = 100,
            setupType = "Liquidity Sweep ${signal.entryType.name} / ${signal.sweep.level.source.name}",
        )
    }

    /** Strategy function for the backtester, bound to a symbol and timeframe. */
    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: LiquiditySweepConfig = LiquiditySweepConfig(),
    ): (List<Candle>, Int) -> StrategySignal? = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    /** Execution bars required before the engine can produce anything. */
    fun minimumBars(config: LiquiditySweepConfig = LiquiditySweepConfig()): Int =
        config.warmupBars.coerceAtLeast(MIN_BARS)

    // ------------------------------------------------------------------

    private fun buildReasons(
        bias: com.foxtrader.app.domain.usecase.liquiditysweep.model.SweepBias,
        sweep: LiquiditySweep,
        entry: LiquiditySweepEntryEngine.Entry,
    ): List<String> = buildList {
        add("Bias: ${bias.reason}")
        add(
            "Swept ${if (sweep.level.aboveMarket) "buy-side" else "sell-side"} " +
                "${sweep.level.source.name} @ ${format(sweep.level.price)} (${sweep.level.timeframe.label})",
        )
        add("Reclaimed @ ${format(sweep.reclaimClose)}")
        addAll(entry.reasons)
    }

    private fun statusText(
        bias: com.foxtrader.app.domain.usecase.liquiditysweep.model.SweepBias?,
        signals: List<LiquiditySweepSignal>,
    ): String {
        val latest = signals.lastOrNull()
        return when {
            latest != null ->
                if (latest.direction == Direction.BULLISH) "BUY confirmed" else "SELL confirmed"

            bias == null -> "Waiting for higher-timeframe bias"
            else -> "${bias.reason} — hunting a sweep"
        }
    }

    private fun format(value: Double) = String.format(java.util.Locale.US, "%.5f", value)

    private companion object {
        /** Enough execution bars for two timeframes above to exist at all. */
        const val MIN_BARS = 80
    }
}
