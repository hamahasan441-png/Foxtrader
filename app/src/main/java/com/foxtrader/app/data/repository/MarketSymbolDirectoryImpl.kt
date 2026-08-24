package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.AllRatesTodayDataSource
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.repository.MarketSymbolDirectory
import javax.inject.Inject
import javax.inject.Singleton

/** Provider-native instrument discovery. */
@Singleton
class MarketSymbolDirectoryImpl @Inject constructor(
    private val allRatesToday: AllRatesTodayDataSource,
) : MarketSymbolDirectory {
    override suspend fun discover(provider: DataProvider): Result<List<ProviderMarketSymbol>> =
        runCatching {
            when (provider) {
                DataProvider.ALL_RATES_TODAY -> allRatesToday.discoverSymbols()
                else -> emptyList()
            }
        }
}
