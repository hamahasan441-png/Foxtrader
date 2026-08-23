package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import javax.inject.Inject

/**
 * Loads real external context for strategy packages through the existing
 * repository-backed MTF/peer provider.
 *
 * This deliberately reuses [MtfContextProvider] instead of opening a second
 * data path. Its provenance gates reject synthetic HTF/peer candles and its
 * explicit refresh path stays on the currently selected market provider.
 */
class StrategyMarketContextProvider @Inject constructor(
    private val mtfContextProvider: MtfContextProvider,
) {
    suspend fun load(
        symbol: String,
        timeframe: Timeframe,
        provider: DataProvider? = null,
        freshness: MarketDataFreshness? = null,
        refreshMissingPeers: Boolean = false,
    ): StrategyMarketContext {
        val htf = mtfContextProvider.getHtfContext(symbol, timeframe)
        val peers = mtfContextProvider.getCorrelatedContext(
            symbol = symbol,
            timeframe = timeframe,
            refreshMissing = refreshMissingPeers,
        )
        return StrategyMarketContext(
            provider = provider,
            freshness = freshness,
            peerCandles = peers,
            higherTimeframeCandles = htf,
        )
    }
}
