package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.AllRatesTodayDataSource
import com.foxtrader.app.data.remote.api.BinanceDataSource
import com.foxtrader.app.data.remote.api.BybitDataSource
import com.foxtrader.app.data.remote.api.KuCoinDataSource
import com.foxtrader.app.data.remote.api.OkxDataSource
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketType
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.deriv.DerivActiveSymbol
import com.foxtrader.app.domain.repository.DerivRepository
import com.foxtrader.app.domain.repository.MarketSymbolDirectory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/** Provider-native instrument discovery. */
@Singleton
class MarketSymbolDirectoryImpl @Inject constructor(
    private val allRatesToday: AllRatesTodayDataSource,
    private val binance: BinanceDataSource,
    private val bybit: BybitDataSource,
    private val kuCoin: KuCoinDataSource,
    private val okx: OkxDataSource,
    private val deriv: DerivRepository,
) : MarketSymbolDirectory {
    override suspend fun discover(provider: DataProvider): Result<List<ProviderMarketSymbol>> =
        runCatching {
            when (provider) {
                DataProvider.ALL_RATES_TODAY -> allRatesToday.discoverSymbols()
                DataProvider.BINANCE -> binance.discoverSymbols()
                DataProvider.BYBIT -> bybit.discoverSymbols()
                DataProvider.KUCOIN -> kuCoin.discoverSymbols()
                DataProvider.OKX -> okx.discoverSymbols()
                DataProvider.DERIV -> deriv.activeSymbols().getOrThrow()
                    .mapNotNull { it.toProviderMarketSymbol() }
                    .distinctBy { it.providerSymbol }
                    .sortedBy { it.providerSymbol }
                else -> emptyList()
            }
        }

    private fun DerivActiveSymbol.toProviderMarketSymbol(): ProviderMarketSymbol? {
        val exact = symbol.trim()
        if (exact.isBlank()) return null
        val classificationText = listOfNotNull(market, submarket, subgroup, symbolType)
            .joinToString(" ")
            .lowercase()
        val assetClass = when {
            classificationText.contains("synthetic") ||
                classificationText.contains("derived") ||
                classificationText.contains("volatility") -> AssetClass.SYNTHETIC
            classificationText.contains("stock index") ||
                classificationText.contains("stock_indices") ||
                classificationText.contains("indices") -> AssetClass.INDICES
            classificationText.contains("forex") || exact.startsWith("frx", ignoreCase = true) -> AssetClass.FOREX
            classificationText.contains("stock") -> AssetClass.STOCKS
            classificationText.contains("metal") -> AssetClass.METALS
            classificationText.contains("energy") -> AssetClass.ENERGY
            classificationText.contains("commodit") -> AssetClass.COMMODITIES
            classificationText.contains("crypto") -> AssetClass.CRYPTO
            else -> AssetClass.OTHER
        }
        val rawPip = pipSize?.takeIf { it.isFinite() && it > 0.0 }
        val precision = rawPip
            ?.takeIf { it <= MAX_PRICE_PRECISION && it % 1.0 == 0.0 }
            ?.toInt()
        val resolvedPip = precision?.let { 10.0.pow(-it) }
            ?: rawPip?.takeIf { it <= 1.0 }

        return ProviderMarketSymbol(
            provider = DataProvider.DERIV,
            providerSymbol = exact,
            canonicalSymbol = exact
                .removePrefix("frx")
                .removePrefix("FRX")
                .uppercase(),
            displayName = displayName.trim().ifBlank { exact },
            assetClass = assetClass,
            marketType = if (assetClass == AssetClass.SYNTHETIC) {
                MarketType.SYNTHETIC
            } else {
                MarketType.DERIVATIVE_UNDERLYING
            },
            pricePrecision = precision,
            pipSize = resolvedPip,
            category = listOfNotNull(market, submarket, subgroup)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
                .ifBlank { null },
            isTrading = exchangeOpen && !tradingSuspended,
        )
    }

    private companion object {
        const val MAX_PRICE_PRECISION = 12.0
    }
}
