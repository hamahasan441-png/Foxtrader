package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

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
 * - Uses sourced repository reads and rejects synthetic context. Cached real
 *   bars remain usable offline; an explicit SMT request may refresh a missing
 *   peer through the configured market provider.
 * - Limits to at most 3 HTFs to bound CPU cost per AI cycle.
 */
class MtfContextProvider @Inject constructor(
    private val repository: MarketRepository,
) {
    private val peerRefreshAttempts = ConcurrentHashMap<String, Long>()

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
    ): Map<Timeframe, List<Candle>> {
        return try {
            val htfs = htfLadder(executionTimeframe)
            val result = LinkedHashMap<Timeframe, List<Candle>>(htfs.size)
            for (tf in htfs) {
                // Per-TF errors are suppressed; coroutine cancellation is not.
                val sourced = repositoryCallOrNull { repository.getSourcedCandles(symbol, tf) }
                // Synthetic HTF bars must never strengthen or veto a decision
                // made from real primary prices.
                if (sourced?.source?.isTrustworthy == true && sourced.candles.size >= MIN_BARS) {
                    result[tf] = sourced.candles
                }
            }
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyMap()
        }
    }

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
        refreshMissing: Boolean = false,
    ): Map<String, List<Candle>> {
        return try {
            val peers = correlatedPeers(symbol).take(MAX_CORRELATED_PEERS)
            val result = LinkedHashMap<String, List<Candle>>(peers.size)
            for (peer in peers) {
                var sourced = repositoryCallOrNull { repository.getSourcedCandles(peer, timeframe) }
                if (refreshMissing && !sourced.isUsableContext() && shouldRefresh(peer, timeframe)) {
                    // An explicit user SMT request gets one real-provider refresh
                    // attempt before the peer is rejected as unavailable.
                    repositoryCallOrNull {
                        repository.refreshCandles(peer, timeframe, PEER_FETCH_LIMIT)
                    }
                    sourced = repositoryCallOrNull { repository.getSourcedCandles(peer, timeframe) }
                }
                // A random-walk peer can manufacture a convincing-looking SMT
                // divergence against real prices. Provenance therefore gates
                // peer data before it reaches any signal engine.
                sourced?.takeIf { it.isUsableContext() }?.let { result[peer] = it.candles }
            }
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun <T> repositoryCallOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    private fun com.foxtrader.app.domain.model.SourcedCandles?.isUsableContext(): Boolean =
        this != null && source.isTrustworthy && candles.size >= MIN_BARS

    private fun shouldRefresh(symbol: String, timeframe: Timeframe): Boolean {
        val key = "$symbol|$timeframe"
        val now = System.currentTimeMillis()
        return synchronized(peerRefreshAttempts) {
            val previous = peerRefreshAttempts[key]
            if (previous != null && now - previous < PEER_REFRESH_COOLDOWN_MS) {
                false
            } else {
                peerRefreshAttempts[key] = now
                true
            }
        }
    }

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
        const val PEER_FETCH_LIMIT = 240
        const val PEER_REFRESH_COOLDOWN_MS = 5 * 60_000L

        /** Timeframes ordered lowest → highest. */
        val ORDERED_TIMEFRAMES = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
        )
    }
}
