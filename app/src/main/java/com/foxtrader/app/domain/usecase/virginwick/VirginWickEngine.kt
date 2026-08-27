package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.KillZone
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.mtf.MultiTimeframeSeries
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWick
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWickAnalysis
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWickSignal
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWickState
import com.foxtrader.app.domain.usecase.virginwick.model.WickPoi
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Virgin Wick — untested-wick reversion engine.
 *
 * The premise is unfinished business. When price spikes into an area and is
 * rejected, the wick it leaves behind is a level nobody has since traded
 * against. While it stays untouched it acts as a magnet, and the return to it
 * is the trade.
 *
 * The sequence:
 * 1. On the context timeframe, find wicks no later bar has traded back into.
 * 2. Promote a wick to a point of interest once the context closes beyond it,
 *    which confirms the market left it behind rather than still working it.
 * 3. On the execution timeframe, wait for price to return into the zone.
 * 4. Require an inverted fair value gap to confirm the rejection is real.
 * 5. Stop on the far side of whichever is safer; target the next untested wick,
 *    or a fixed multiple when that draw is unreasonably far.
 *
 * ## Multi-timeframe
 *
 * The charted series is the execution timeframe; the context timeframe is
 * resampled from it via [MultiTimeframeSeries], which drops the unfinished
 * trailing bucket and dates every context bar in execution time. There is no
 * second data feed to disagree with the chart.
 *
 * ## The property everything rests on
 *
 * [analyze] is a pure function of the closed-bar prefix: run over candles
 * truncated at bar `t`, it returns exactly what the full-series run reports for
 * bars at or before `t`. Crucially, virginity is evaluated *as of* the bar
 * being asked about, so a wick tested next week is still virgin today and a
 * historical arrow never repaints.
 */
@Singleton
class VirginWickEngine @Inject constructor(
    private val smcDetector: SmcDetector,
) {

    private val detector = VirginWickDetector()
    private val entryEngine = VirginWickEntryEngine()

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: VirginWickConfig = VirginWickConfig(),
    ): VirginWickAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) {
            return VirginWickAnalysis.empty(integrity.reason ?: "Invalid market data.")
        }

        val contextTimeframe = config.contextTimeframeFor(timeframe)
            ?: return VirginWickAnalysis.empty(
                "Virgin Wick: no context timeframe mapped for ${timeframe.label}.",
            )

        val context = MultiTimeframeSeries.from(candles, timeframe, contextTimeframe)
        if (context.isEmpty) {
            return VirginWickAnalysis.empty(
                "Virgin Wick: not enough history for ${contextTimeframe.label} context.",
            )
        }

        // One inversion pass over the execution series, shared by every zone.
        val inversions = runCatching { smcDetector.detectIFVG(candles) }.getOrDefault(emptyList())

        val signals = ArrayList<VirginWickSignal>()
        val seen = HashSet<String>()
        // A zone is traded once. The wick's whole value is that it was
        // untested; after price has worked it, that is no longer true.
        val spent = HashSet<String>()

        var latestWicks: List<VirginWick> = emptyList()
        var latestPois: List<WickPoi> = emptyList()
        var state = VirginWickState.IDLE

        val first = config.warmupBars.coerceAtLeast(MIN_BARS)
        for (bar in first..candles.lastIndex) {
            val closedContext = context.countClosedAt(bar) - 1
            if (closedContext < 1) continue

            // Virginity as it stood at this bar, not as it stands at the end.
            val wicks = detector.virginWicks(context, closedContext, config)
            if (wicks.isEmpty()) continue
            state = VirginWickState.WICKS_MARKED

            val pois = detector.activate(wicks, context, closedContext, config)
            val active = detector.activeAt(pois, bar, config).filterNot { zoneKey(it) in spent }
            if (active.isEmpty()) continue
            state = VirginWickState.POI_ACTIVE

            latestWicks = wicks
            latestPois = pois

            for (poi in active) {
                // The return must happen at this bar for the setup to be
                // published here; earlier bars were already considered.
                val touch = entryEngine.touchIndex(candles, poi, bar, bar, config) ?: continue
                state = VirginWickState.PRICE_RETURNED
                spent += zoneKey(poi)

                val entry = entryEngine.confirm(candles, poi, touch, inversions, config) ?: continue
                val killZone = killZoneOf(candles[entry.index].timestamp)
                if (config.sessions.isNotEmpty() && killZone !in config.sessions) continue

                val geometry = VirginWickRiskEngine.build(
                    direction = poi.direction,
                    entry = entry.price,
                    entryCandle = candles[entry.index],
                    ifvg = entry.ifvg,
                    poi = poi,
                    opposingWicks = wicks.filter { it.direction != poi.direction },
                    config = config,
                ) ?: continue

                val signal = VirginWickSignal(
                    symbol = symbol,
                    executionTimeframe = timeframe,
                    poi = poi,
                    entryType = entry.type,
                    ifvg = entry.ifvg,
                    entryIndex = entry.index,
                    timestamp = candles[entry.index].timestamp,
                    entry = geometry.entry,
                    stop = geometry.stop,
                    target = geometry.target,
                    targetSource = geometry.source,
                    killZone = killZone,
                    reasons = buildReasons(poi, entry, killZone),
                )
                if (seen.add(signal.key)) {
                    signals += signal
                    state = VirginWickState.CONFIRMED
                }
            }
        }

        val published = if (config.historicalSignals) {
            signals
        } else {
            val cutoff = candles.lastIndex - config.liveWindowBars + 1
            signals.filter { it.entryIndex >= cutoff }
        }

        return VirginWickAnalysis(
            wicks = latestWicks,
            pois = latestPois,
            signals = published.sortedBy { it.entryIndex },
            state = state,
            statusText = statusText(latestPois, published),
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
        config: VirginWickConfig = VirginWickConfig(),
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
            setupType = "Virgin Wick ${signal.entryType.name} / ${signal.targetSource.name}",
        )
    }

    /** Strategy function for the backtester, bound to a symbol and timeframe. */
    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: VirginWickConfig = VirginWickConfig(),
    ): (List<Candle>, Int) -> StrategySignal? = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    /** Execution bars required before the engine can produce anything. */
    fun minimumBars(config: VirginWickConfig = VirginWickConfig()): Int =
        config.warmupBars.coerceAtLeast(MIN_BARS)

    // ------------------------------------------------------------------

    /** Identity of the zone itself, independent of when it was entered. */
    private fun zoneKey(poi: WickPoi): String =
        "${poi.wick.side.name}|${poi.wick.contextIndex}|${poi.wick.distal}"

    private fun buildReasons(
        poi: WickPoi,
        entry: VirginWickEntryEngine.Entry,
        killZone: KillZone?,
    ): List<String> = buildList {
        add(
            "Untested ${poi.wick.side.name.lowercase()} wick on ${poi.wick.timeframe.label} @ " +
                String.format(Locale.US, "%.5f", poi.distal),
        )
        add("Context closed beyond it ${poi.activatingCloses}x")
        addAll(entry.reasons)
        killZone?.let { add(it.label) }
    }

    private fun statusText(pois: List<WickPoi>, signals: List<VirginWickSignal>): String {
        val latest = signals.lastOrNull()
        return when {
            latest != null ->
                if (latest.direction == Direction.BULLISH) "BUY confirmed" else "SELL confirmed"

            pois.isEmpty() -> "Marking untested wicks"
            else -> "${pois.size} untested zone(s) live — waiting for the return"
        }
    }

    /**
     * The kill zone a timestamp falls in.
     *
     * UTC, because the zones are defined in UTC and a device's local offset
     * must not move where a session boundary sits.
     */
    private fun killZoneOf(timestamp: Long): KillZone? {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestamp
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return KillZone.entries.firstOrNull { hour >= it.startHourUtc && hour < it.endHourUtc }
    }

    private companion object {
        /** Enough execution bars for a context timeframe to exist at all. */
        const val MIN_BARS = 120
    }
}
