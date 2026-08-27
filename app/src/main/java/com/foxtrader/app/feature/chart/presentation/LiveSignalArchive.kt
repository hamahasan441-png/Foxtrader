package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.SmtConfig

/** Exact rule-set identity for one retained signal archive. */
data class LiveSignalConfiguration(
    val indicators: IndicatorToggles = IndicatorToggles(),
    val litXConfig: LitXConfig = LitXConfig(),
    val litConfig: LitConfig = LitConfig(),
    val smtConfig: SmtConfig = SmtConfig(),
)

/**
 * Context boundary for live-forward signal retention.
 *
 * Signals from different providers/symbols/timeframes/bar modes must never be
 * mixed because their bar indices and execution semantics are not interchangeable.
 */
data class LiveSignalContext(
    val provider: DataProvider,
    val symbol: String,
    val timeframe: Timeframe,
    val barMode: ChartBarMode,
    val configuration: LiveSignalConfiguration = LiveSignalConfiguration(),
) {
    val normalizedSymbol: String = symbol.trim().uppercase().ifBlank { "UNKNOWN" }
}

/**
 * Bounded in-memory archive of signals that were objectively observed as live.
 *
 * Important distinction: a strategy can recompute historical markers on every
 * frame, but those retrospective markers are NOT promoted into the live-forward
 * sample. Only [ChartSignal.isLive] events are frozen into this archive. The
 * current frame's retrospective markers can still be rendered, while retained
 * live events survive later recomputes after their source stops emitting them.
 *
 * Stored events are immutable snapshots. A later recalculation cannot rewrite
 * the entry/SL/TP/confidence of an already-observed event. Bar indices are
 * remapped from timestamp on every read so prepending older chart history does
 * not move an archived arrow onto the wrong candle.
 */
class LiveSignalArchive(
    private val maxSignalsPerContext: Int = DEFAULT_MAX_SIGNALS_PER_CONTEXT,
    private val maxContexts: Int = DEFAULT_MAX_CONTEXTS,
) {
    private val archive = LinkedHashMap<LiveSignalContext, LinkedHashMap<String, ChartSignal>>()

    @Synchronized
    fun merge(
        context: LiveSignalContext,
        frameSignals: List<ChartSignal>,
        candles: List<com.foxtrader.app.domain.model.Candle>,
    ): List<ChartSignal> {
        val store = storeFor(context)

        // Freeze only events that were actually observed at the live edge.
        // Historical backfill from a strategy scan stays visible for this frame
        // but never inflates the forward-observed performance sample.
        frameSignals.asSequence()
            .filter { it.isLive }
            .filter(::isArchivable)
            .forEach { signal ->
                store.putIfAbsent(identity(signal), signal.copy(isLive = false))
            }
        trimSignals(store)

        val merged = LinkedHashMap<String, ChartSignal>()
        remap(store.values, candles).forEach { candidate ->
            mergePreferred(merged, candidate)
        }
        frameSignals.forEach { candidate ->
            if (candidate.barIndex in candles.indices && candidate.entry.isFinite() && candidate.entry > 0.0) {
                mergePreferred(merged, candidate)
            }
        }

        return merged.values
            .sortedWith(compareBy<ChartSignal> { it.barIndex }.thenByDescending { sourcePriority(it.source) }.thenBy { it.id })
    }

    /** Live-forward sample only; retrospective frame markers are excluded. */
    @Synchronized
    fun liveSnapshot(
        context: LiveSignalContext,
        candles: List<com.foxtrader.app.domain.model.Candle>,
    ): List<ChartSignal> = remap(archive[context]?.values.orEmpty(), candles)
        .sortedBy { it.barIndex }

    @Synchronized
    fun clear() = archive.clear()

    @Synchronized
    fun clear(context: LiveSignalContext) {
        archive.remove(context)
    }

    private fun storeFor(context: LiveSignalContext): LinkedHashMap<String, ChartSignal> {
        archive[context]?.let { return it }
        if (archive.size >= maxContexts.coerceAtLeast(1)) {
            val oldest = archive.keys.firstOrNull()
            if (oldest != null) archive.remove(oldest)
        }
        return LinkedHashMap<String, ChartSignal>().also { archive[context] = it }
    }

    private fun trimSignals(store: LinkedHashMap<String, ChartSignal>) {
        val cap = maxSignalsPerContext.coerceAtLeast(1)
        while (store.size > cap) {
            val oldest = store.keys.firstOrNull() ?: break
            store.remove(oldest)
        }
    }

    private fun remap(
        stored: Collection<ChartSignal>,
        candles: List<com.foxtrader.app.domain.model.Candle>,
    ): List<ChartSignal> {
        if (stored.isEmpty() || candles.isEmpty()) return emptyList()
        val indexByTimestamp = HashMap<Long, Int>(candles.size * 2)
        candles.forEachIndexed { index, candle -> indexByTimestamp[candle.timestamp] = index }
        return stored.mapNotNull { signal ->
            val index = indexByTimestamp[signal.timestamp] ?: return@mapNotNull null
            signal.copy(barIndex = index, isLive = false)
        }
    }

    private fun mergePreferred(target: MutableMap<String, ChartSignal>, candidate: ChartSignal) {
        val key = identity(candidate)
        val existing = target[key]
        if (existing == null || prefer(candidate, existing)) target[key] = candidate
    }

    private fun prefer(candidate: ChartSignal, existing: ChartSignal): Boolean = when {
        candidate.isLive && !existing.isLive -> true
        existing.isLive && !candidate.isLive -> false
        sourcePriority(candidate.source) > sourcePriority(existing.source) -> true
        else -> false
    }

    /**
     * eventKey deliberately crosses source boundaries. A canonical LiT/LiTX
     * event and its StrategyLibrary mirror are one event, not two arrows/trades.
     */
    private fun identity(signal: ChartSignal): String =
        signal.eventKey?.takeIf { it.isNotBlank() } ?: "${signal.source.name}|${signal.id}"

    private fun isArchivable(signal: ChartSignal): Boolean =
        signal.id.isNotBlank() && signal.timestamp > 0L && signal.barIndex >= 0 &&
            signal.entry.isFinite() && signal.entry > 0.0

    private fun sourcePriority(source: SignalSource): Int = when (source) {
        SignalSource.LIT -> 100
        SignalSource.LITX -> 95
        SignalSource.SMS -> 90
        SignalSource.RSI_ORDERFLOW -> 88
        SignalSource.RSI_REVERSAL -> 96
        SignalSource.LIQUIDITY_SWEEP -> 89
        SignalSource.VIRGIN_WICK -> 87
        SignalSource.PIVOT_SWEEP_DIVERGENCE -> 92
        SignalSource.VALUE_AREA_LIQUIDITY_REJECTION -> 93
        SignalSource.ACCUMULATION_MANIPULATION_DISTRIBUTION -> 91
        SignalSource.NASCENT -> 94
        SignalSource.APEX -> 97
        SignalSource.COMPASS -> 98
        SignalSource.CRUCIBLE -> 95
        SignalSource.SMT -> 85
        SignalSource.TRADEPRO -> 80
        SignalSource.BINARY3M -> 70
        SignalSource.STRATEGY -> 10
    }

    private companion object {
        const val DEFAULT_MAX_SIGNALS_PER_CONTEXT = 500
        const val DEFAULT_MAX_CONTEXTS = 12
    }
}
