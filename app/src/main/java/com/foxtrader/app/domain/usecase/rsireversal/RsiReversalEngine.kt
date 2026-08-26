package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.rsireversal.model.PivotSeries
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalAnalysis
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalSetup
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalSignal
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalState
import javax.inject.Inject

/**
 * RSI Orderflow Reversal — orchestrator.
 *
 * Composes the RSI Orderflow candle engine, the shared pivot engine, the
 * higher-timeframe master pattern and the lower-timeframe confirmation into the
 * signal list the chart, replay and backtester all consume.
 *
 * The load-bearing property, and the reason chart/replay/backtest cannot
 * disagree: [analyze] is a **pure function of the closed-bar prefix**. Running
 * it over `candles` truncated at bar `t` returns exactly what the full-series
 * run reports for bars at or before `t`. There is no hidden state between
 * calls, nothing is measured backwards from the end of the series, and every
 * event is published on the bar it became knowable rather than the bar it
 * formed on.
 */
class RsiReversalEngine @Inject constructor() {

    private val htfEngine = RsiReversalHtfEngine()
    private val ltfEngine = RsiReversalLtfEngine()

    /**
     * Analyse a context-timeframe series.
     *
     * @param ltfCandles entry-timeframe bars. Empty means no lower-timeframe
     *   data is available: setups still arm and are reported, but no signal is
     *   produced, because §16–§18 confirmation cannot be evaluated. The engine
     *   never fabricates a confirmation from context bars.
     */
    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        ltfCandles: List<Candle> = emptyList(),
        ltfTimeframe: Timeframe? = null,
        config: RsiReversalConfig = RsiReversalConfig(),
    ): RsiReversalAnalysis {
        if (candles.isEmpty()) return empty()

        val rsiCandles = RsiCandleEngine.calculate(candles, config.rsiLength)

        val pricePivots = RsiReversalPivotEngine.detect(
            series = PivotSeries.PRICE,
            size = candles.size,
            left = config.pricePivotLeft,
            right = config.pricePivotRight,
            highAt = { candles[it].high },
            lowAt = { candles[it].low },
            timestampAt = { candles[it].timestamp },
        )

        val rsiPivots = RsiReversalPivotEngine.detect(
            series = PivotSeries.RSI,
            size = rsiCandles.size,
            left = config.rsiPivotLeft,
            right = config.rsiPivotRight,
            highAt = { rsiCandles[it].high },
            lowAt = { rsiCandles[it].low },
            timestampAt = { rsiCandles[it].timestamp },
        )

        val setups = htfEngine.scan(
            symbol = symbol,
            timeframe = timeframe,
            candles = candles,
            rsiCandles = rsiCandles,
            pricePivots = pricePivots,
            rsiPivots = rsiPivots,
            config = config,
        )

        val signals = if (ltfCandles.isEmpty() || ltfTimeframe == null) {
            emptyList()
        } else {
            buildSignals(setups, candles, ltfCandles, ltfTimeframe, config)
        }

        return RsiReversalAnalysis(
            rsiCandles = rsiCandles,
            pricePivots = pricePivots,
            rsiPivots = rsiPivots,
            armedSetups = setups,
            signals = signals,
            state = if (setups.isEmpty()) RsiReversalState.IDLE else RsiReversalState.ARMED,
            statusText = statusText(setups, signals),
        )
    }

    // ------------------------------------------------------------------

    private fun buildSignals(
        setups: List<RsiReversalSetup>,
        candles: List<Candle>,
        ltfCandles: List<Candle>,
        ltfTimeframe: Timeframe,
        config: RsiReversalConfig,
    ): List<RsiReversalSignal> {
        val out = ArrayList<RsiReversalSignal>()
        val seen = HashSet<String>()

        for (setup in setups) {
            // §28: the entry search may only look at bars that had not closed
            // before the setup armed. The armed bar's own close time is the
            // boundary, so an entry bar opening exactly at it is eligible.
            val startIndex = ltfCandles.indexOfFirst { it.timestamp >= setup.armedTimestamp }
            if (startIndex < 0) continue

            val confirmation = ltfEngine.confirm(
                direction = setup.direction,
                candles = ltfCandles,
                startIndex = startIndex,
                config = config,
            ) ?: continue

            val geometry = RsiReversalRiskEngine.build(
                direction = setup.direction,
                entry = confirmation.entry,
                sweptExtreme = confirmation.sweptExtreme,
                config = config,
            ) ?: continue

            val signal = RsiReversalSignal(
                setup = setup,
                entryTimeframe = ltfTimeframe,
                confirmationType = confirmation.type,
                entry = geometry.entry,
                stop = geometry.stop,
                target = geometry.target,
                contextIndex = contextIndexFor(candles, confirmation.entryTimestamp, setup.armedIndex),
                confirmedAt = confirmation.entryTimestamp,
                reasons = buildReasons(setup, confirmation),
            )

            // §30: one arrow per setup.
            if (seen.add(setup.key)) out += signal
        }
        return out.sortedBy { it.confirmedAt }
    }

    /**
     * The context bar an entry belongs to — the last context bar that had
     * opened at or before the confirmation, never earlier than the armed bar.
     */
    private fun contextIndexFor(candles: List<Candle>, confirmedAt: Long, armedIndex: Int): Int {
        var index = armedIndex
        for (i in armedIndex until candles.size) {
            if (candles[i].timestamp <= confirmedAt) index = i else break
        }
        return index.coerceIn(0, candles.lastIndex)
    }

    private fun buildReasons(
        setup: RsiReversalSetup,
        confirmation: RsiReversalLtfEngine.Confirmation,
    ): List<String> {
        val side = if (setup.direction == Direction.BULLISH) "LL" else "HH"
        val rsiSide = if (setup.direction == Direction.BULLISH) "HL" else "LH"
        return buildList {
            add("Price $side, RSI $rsiSide at P2")
            add("RSI structure break @ ${"%.2f".format(java.util.Locale.US, setup.p3.brokenLevel)}")
            add(
                if (setup.recursiveDepth == 0) {
                    "Final extreme P4 unconfirmed by RSI"
                } else {
                    "Recursive depth ${setup.recursiveDepth}, armed at P${setup.finalExtreme.ordinal}"
                }
            )
            addAll(confirmation.reasons)
        }
    }

    /** Compact status line for the signal-status UI (§45). */
    private fun statusText(
        setups: List<RsiReversalSetup>,
        signals: List<RsiReversalSignal>,
    ): String {
        val latestSignal = signals.lastOrNull()
        val latestSetup = setups.lastOrNull()
        return when {
            latestSignal != null && latestSetup != null &&
                latestSignal.setup.key == latestSetup.key ->
                if (latestSignal.direction == Direction.BULLISH) "BUY Confirmed" else "SELL Confirmed"

            latestSetup != null ->
                if (latestSetup.direction == Direction.BULLISH) "BUY Armed" else "SELL Armed"

            else -> "Scanning"
        }
    }

    private fun empty() = RsiReversalAnalysis(
        rsiCandles = emptyList(),
        pricePivots = emptyList(),
        rsiPivots = emptyList(),
        armedSetups = emptyList(),
        signals = emptyList(),
        state = RsiReversalState.IDLE,
        statusText = "Scanning",
    )

    /** Bars required before the engine can produce anything (§43). */
    fun minimumBars(config: RsiReversalConfig = RsiReversalConfig()): Int =
        config.warmupBars + config.rsiLength + config.pricePivotLeft + config.pricePivotRight
}
