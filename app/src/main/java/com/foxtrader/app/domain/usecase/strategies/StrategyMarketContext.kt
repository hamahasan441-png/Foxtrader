package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.Timeframe

/**
 * External market context consumed by strategy analysis.
 *
 * The primary candle series remains an explicit argument to the strategy engine.
 * This object carries only data that cannot be inferred safely from that one
 * series: correlated peer markets, higher-timeframe series and provider state.
 *
 * A context must be truncated before analysis. For historical decisions the
 * timeframe-aware [causalAt] overload is the strict boundary: an HTF candle is
 * admitted only when that candle has fully CLOSED by the primary decision-bar
 * close, preventing a partially formed H4/D1 candle from leaking future OHLC.
 */
data class StrategyMarketContext(
    val provider: DataProvider? = null,
    val freshness: MarketDataFreshness? = null,
    val peerCandles: Map<String, List<Candle>> = emptyMap(),
    val higherTimeframeCandles: Map<Timeframe, List<Candle>> = emptyMap(),
) {
    val hasPeerData: Boolean
        get() = peerCandles.values.any { it.isNotEmpty() }

    val hasHigherTimeframeData: Boolean
        get() = higherTimeframeCandles.values.any { it.isNotEmpty() }

    /** Synthetic/sample provenance is never allowed to authorise a trade. */
    val decisionEligible: Boolean
        get() = freshness != MarketDataFreshness.SIMULATED

    /**
     * Legacy timestamp-only boundary retained for source compatibility.
     * New strategy decisions should use the timeframe-aware overload below.
     */
    fun causalAt(cutoffTimestamp: Long): StrategyMarketContext = copy(
        peerCandles = normalizePeers { candle -> candle.timestamp <= cutoffTimestamp },
        higherTimeframeCandles = higherTimeframeCandles
            .mapValues { (_, candles) -> candles.filter { it.timestamp <= cutoffTimestamp } }
            .filterValues { it.isNotEmpty() },
    )

    /**
     * Strict closed-bar boundary for a decision made after the primary bar at
     * [decisionBarOpenTimestamp] has closed.
     *
     * Peer feeds are same-timeframe by contract, so a peer bar is usable when
     * its close is no later than the primary decision close. Each HTF series is
     * checked with its own duration. This is required for reproducible backtests
     * because OHLC for a still-open HTF candle contains future information.
     */
    fun causalAt(
        decisionBarOpenTimestamp: Long,
        primaryTimeframe: Timeframe,
    ): StrategyMarketContext {
        val primaryDuration = durationMillis(primaryTimeframe)
        val decisionClose = safeAdd(decisionBarOpenTimestamp, primaryDuration)
        return copy(
            peerCandles = normalizePeers { candle ->
                isClosedBy(
                    candleOpen = candle.timestamp,
                    durationMillis = primaryDuration,
                    decisionClose = decisionClose,
                )
            },
            higherTimeframeCandles = higherTimeframeCandles
                .mapValues { (timeframe, candles) ->
                    val duration = durationMillis(timeframe)
                    candles.filter { candle ->
                        isClosedBy(
                            candleOpen = candle.timestamp,
                            durationMillis = duration,
                            decisionClose = decisionClose,
                        )
                    }
                }
                .filterValues { it.isNotEmpty() },
        )
    }

    private fun normalizePeers(
        predicate: (Candle) -> Boolean,
    ): Map<String, List<Candle>> = peerCandles
        .asSequence()
        .filter { (symbol, _) -> symbol.isNotBlank() }
        .map { (symbol, candles) -> symbol to candles.filter(predicate) }
        .filter { (_, candles) -> candles.isNotEmpty() }
        .toMap()

    private fun durationMillis(timeframe: Timeframe): Long =
        timeframe.minutes.toLong().coerceAtLeast(1L) * 60_000L

    private fun isClosedBy(
        candleOpen: Long,
        durationMillis: Long,
        decisionClose: Long,
    ): Boolean = candleOpen <= decisionClose - durationMillis

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        val EMPTY = StrategyMarketContext()
    }
}
