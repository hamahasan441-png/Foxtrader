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
 * [causalAt] is the mandatory boundary before analysis. It removes bars whose
 * timestamps are later than the primary decision bar so SMT/MTF consumers can
 * never inspect future peer or higher-timeframe candles.
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

    val decisionEligible: Boolean
        get() = freshness != MarketDataFreshness.SIMULATED

    fun causalAt(cutoffTimestamp: Long): StrategyMarketContext = copy(
        peerCandles = peerCandles
            .asSequence()
            .filter { (symbol, _) -> symbol.isNotBlank() }
            .map { (symbol, candles) -> symbol to candles.filter { it.timestamp <= cutoffTimestamp } }
            .filter { (_, candles) -> candles.isNotEmpty() }
            .toMap(),
        higherTimeframeCandles = higherTimeframeCandles
            .mapValues { (_, candles) -> candles.filter { it.timestamp <= cutoffTimestamp } }
            .filterValues { it.isNotEmpty() },
    )

    companion object {
        val EMPTY = StrategyMarketContext()
    }
}
