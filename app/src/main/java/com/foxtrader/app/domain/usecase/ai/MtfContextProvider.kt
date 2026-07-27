package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import javax.inject.Inject

/**
 * Provides multi-timeframe and correlated-symbol candle context for the AI agent system.
 *
 * Given the user's current (execution) timeframe, this fetches candles from
 * relevant higher timeframes (HTFs) via the repository and returns them as a
 * map suitable for [com.foxtrader.app.domain.model.AgentContext.mtfCandles].
 * It can also fetch a small peer set for SMT divergence via
 * [com.foxtrader.app.domain.model.AgentContext.correlatedCandles].
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
     * Fetch same-timeframe correlated symbols for SMT divergence analysis.
     *
     * Returns a small, deterministic peer set to bound CPU and DB work. Missing
     * or insufficient peer data is ignored so the AI pipeline degrades to normal
     * single-symbol analysis rather than failing.
     */
    suspend fun getCorrelatedContext(
        symbol: String,
        timeframe: Timeframe,
    ): Map<String, List<Candle>> = runCatching {
        val peers = correlatedPeers(symbol).take(MAX_CORRELATED_PEERS)
        val result = LinkedHashMap<String, List<Candle>>(peers.size)
        for (peer in peers) {
            val candles = runCatching { repository.getCandles(peer, timeframe) }.getOrElse { emptyList() }
            if (candles.size >= MIN_BARS) {
                result[peer] = candles
            }
        }
        result as Map<String, List<Candle>>
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

    private fun correlatedPeers(symbol: String): List<String> = when (symbol.uppercase()) {
        "EURUSD" -> listOf("GBPUSD", "AUDUSD")
        "GBPUSD" -> listOf("EURUSD", "AUDUSD")
        "AUDUSD" -> listOf("NZDUSD", "EURUSD")
        "NZDUSD" -> listOf("AUDUSD", "EURUSD")
        "USDJPY" -> listOf("USDCHF", "USDCAD")
        "USDCHF" -> listOf("USDJPY", "USDCAD")
        "XAUUSD" -> listOf("XAGUSD")
        "XAGUSD" -> listOf("XAUUSD")
        "BTCUSDT" -> listOf("ETHUSDT", "SOLUSDT")
        "ETHUSDT" -> listOf("BTCUSDT", "SOLUSDT")
        "SOLUSDT" -> listOf("BTCUSDT", "ETHUSDT")
        "BNBUSDT" -> listOf("BTCUSDT", "ETHUSDT")
        "NAS100" -> listOf("US500", "US30")
        "US500" -> listOf("NAS100", "US30")
        "US30" -> listOf("US500", "NAS100")
        else -> emptyList()
    }.filterNot { it.equals(symbol, ignoreCase = true) }

    private companion object {
        const val MIN_BARS = 50
        const val MAX_HTF_COUNT = 3
        const val MAX_CORRELATED_PEERS = 2

        /** Timeframes ordered lowest → highest. */
        val ORDERED_TIMEFRAMES = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
        )
    }
}
