package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.ProviderMarketSymbol

/**
 * Provider-native instrument discovery boundary.
 *
 * Implementations must query the selected provider's real symbol directory when
 * such an API exists. Static starter/watchlist symbols are not a successful
 * discovery result.
 */
interface MarketSymbolDirectory {
    suspend fun discover(provider: DataProvider): Result<List<ProviderMarketSymbol>>
}
