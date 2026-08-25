package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.AllRatesTodayDataSource
import com.foxtrader.app.data.remote.api.BinanceDataSource
import com.foxtrader.app.data.remote.api.BybitDataSource
import com.foxtrader.app.data.remote.api.KuCoinDataSource
import com.foxtrader.app.data.remote.api.OkxDataSource
import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketType
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.deriv.DerivActiveSymbol
import com.foxtrader.app.domain.repository.DerivRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarketSymbolDirectoryImplTest {

    private val allRates = mockk<AllRatesTodayDataSource>()
    private val binance = mockk<BinanceDataSource>()
    private val bybit = mockk<BybitDataSource>()
    private val kuCoin = mockk<KuCoinDataSource>()
    private val okx = mockk<OkxDataSource>()
    private val dukascopy = mockk<DukascopyDataSource>()
    private val deriv = mockk<DerivRepository>()
    private val directory = MarketSymbolDirectoryImpl(allRates, binance, bybit, kuCoin, okx, dukascopy, deriv)

    @Test
    fun `routes every native directory to its selected provider`() = runBlocking {
        coEvery { allRates.discoverSymbols() } returns listOf(symbol(DataProvider.ALL_RATES_TODAY, "EURUSD"))
        coEvery { binance.discoverSymbols() } returns listOf(symbol(DataProvider.BINANCE, "BTCUSDT"))
        coEvery { bybit.discoverSymbols() } returns listOf(symbol(DataProvider.BYBIT, "ETHUSDT"))
        coEvery { kuCoin.discoverSymbols() } returns listOf(symbol(DataProvider.KUCOIN, "SOL-USDT"))
        coEvery { okx.discoverSymbols() } returns listOf(symbol(DataProvider.OKX, "XRP-USDT"))
        coEvery { dukascopy.discoverSymbols() } returns listOf(symbol(DataProvider.DUKASCOPY, "EURUSD"))

        assertEquals("EURUSD", directory.discover(DataProvider.ALL_RATES_TODAY).getOrThrow().single().providerSymbol)
        assertEquals("BTCUSDT", directory.discover(DataProvider.BINANCE).getOrThrow().single().providerSymbol)
        assertEquals("ETHUSDT", directory.discover(DataProvider.BYBIT).getOrThrow().single().providerSymbol)
        assertEquals("SOL-USDT", directory.discover(DataProvider.KUCOIN).getOrThrow().single().providerSymbol)
        assertEquals("XRP-USDT", directory.discover(DataProvider.OKX).getOrThrow().single().providerSymbol)
        assertEquals("EURUSD", directory.discover(DataProvider.DUKASCOPY).getOrThrow().single().providerSymbol)
    }

    @Test
    fun `maps Deriv native symbols without losing exact provider id`() = runBlocking {
        coEvery { deriv.activeSymbols() } returns Result.success(
            listOf(
                DerivActiveSymbol(
                    symbol = "R_100",
                    displayName = "Volatility 100 Index",
                    market = "synthetic_index",
                    subgroup = "synthetics",
                    submarket = "random_index",
                    symbolType = "stockindex",
                    pipSize = 4.0,
                    exchangeOpen = true,
                    tradingSuspended = false,
                ),
                DerivActiveSymbol(
                    symbol = "frxEURUSD",
                    displayName = "EUR/USD",
                    market = "forex",
                    subgroup = null,
                    submarket = "major_pairs",
                    symbolType = "forex",
                    pipSize = 5.0,
                    exchangeOpen = false,
                    tradingSuspended = false,
                ),
            ),
        )

        val symbols = directory.discover(DataProvider.DERIV).getOrThrow()

        val synthetic = symbols.first { it.providerSymbol == "R_100" }
        assertEquals(DataProvider.DERIV, synthetic.provider)
        assertEquals("R_100", synthetic.canonicalSymbol)
        assertEquals(AssetClass.SYNTHETIC, synthetic.assetClass)
        assertEquals(MarketType.SYNTHETIC, synthetic.marketType)
        assertEquals(4, synthetic.pricePrecision)
        assertEquals(0.0001, synthetic.pipSize ?: 0.0, 1e-12)

        val forex = symbols.first { it.providerSymbol == "frxEURUSD" }
        assertEquals("EURUSD", forex.canonicalSymbol)
        assertEquals(AssetClass.FOREX, forex.assetClass)
        assertFalse(forex.isTrading)
    }

    private fun symbol(provider: DataProvider, id: String) = ProviderMarketSymbol(
        provider = provider,
        providerSymbol = id,
        canonicalSymbol = id.replace("-", ""),
        displayName = id,
        assetClass = AssetClass.CRYPTO,
        marketType = MarketType.SPOT,
    )
}
