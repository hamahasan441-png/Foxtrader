package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import javax.inject.Inject

/**
 * Provides multi-timeframe candle context for the AI agent system.
 *
 * Given the user's current (execution) timeframe, this fetches candles from
 * relevant higher timeframes (HTFs) via the repository and returns them as a
 * map suitable for [com.foxtrader.app.domain.model.AgentContext.mtfCandles].
 *
 * Design notes:
 * - HTFs are determined relative to the execution TF (always look UP).
 * - Uses the repository's one-shot [MarketRepository.getCandles] (cached /
 *   seeded) so it works offline and never blocks on the network.
 * - Limits to at most 3 HTFs to bound CPU cost per AI cycle.
 */
class MtfContextProvider @Inject constructor(
    private val repository: MarketRepository,
) {

    /**
     * Fetch HTF candle context for [symbol] relative to [executionTimeframe].
     *
     * @return A map of up to 3 higher timeframes → their candle lists (≥50 bars
     *         each, or empty if insufficient data). Does not include the execution
     *         TF itself (that's passed separately in [AgentContext.candles]).
     *         Returns an empty map on any unexpected error so AI analysis can
     *         still proceed with single-timeframe context rather than crashing.
     *         Errors are intentionally suppressed here because HTF context is
     *         supplementary — the primary candle set is always passed directly
     *         in [AgentContext.candles] and drives the core analysis even when
     *         HTF fetches fail (e.g. DB not yet seeded, candles not available
     *         for that timeframe).
     */
    suspend fun getHtfContext(
        symbol: String,
        executionTimeframe: Timeframe,
    ): Map<Timeframe, List<Candle>> = runCatching {
        val htfs = htfLadder(executionTimeframe)
        val result = LinkedHashMap<Timeframe, List<Candle>>(htfs.size)
        for (tf in htfs) {
            // Per-TF errors are suppressed: one failing TF must not cancel the rest.
            val candles = runCatching { repository.getCandles(symbol, tf) }.getOrElse { emptyList() }
            if (candles.size >= MIN_BARS) {
                result[tf] = candles
            }
        }
        result as Map<Timeframe, List<Candle>>
    }.getOrElse { emptyMap() }

    /**
     * Returns up to 3 higher timeframes above [tf], ordered from closest to
     * furthest. E.g. for M15 → [H1, H4, D1]; for H4 → [D1, W1, MN].
     */
    private fun htfLadder(tf: Timeframe): List<Timeframe> {
        val all = ORDERED_TIMEFRAMES
        val idx = all.indexOf(tf)
        if (idx < 0) return emptyList()
        // Take the next 3 higher TFs (higher = later in the ordered list).
        return all.drop(idx + 1).take(MAX_HTF_COUNT)
    }

    private companion object {
        const val MIN_BARS = 50
        const val MAX_HTF_COUNT = 3

        /** Timeframes ordered lowest → highest. */
        val ORDERED_TIMEFRAMES = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
        )
    }
}
