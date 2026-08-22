package com.foxtrader.app.domain.usecase.marketdata

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketSymbolClassifierTest {

    @Test
    fun `canonical symbol removes common separators`() {
        assertEquals("EURUSD", MarketSymbolClassifier.canonicalSymbol(" eur/usd "))
        assertEquals("BTCUSDT", MarketSymbolClassifier.canonicalSymbol("btc-usdt"))
    }

    @Test
    fun `classifies core watchlist markets correctly`() {
        assertEquals(MarketAssetClass.FOREX, MarketSymbolClassifier.classify("EURUSD"))
        assertEquals(MarketAssetClass.METAL, MarketSymbolClassifier.classify("XAUUSD"))
        assertEquals(MarketAssetClass.CRYPTO, MarketSymbolClassifier.classify("BTCUSDT"))
        assertEquals(MarketAssetClass.INDEX, MarketSymbolClassifier.classify("NAS100"))
        assertEquals(MarketAssetClass.STOCK, MarketSymbolClassifier.classify("AAPL"))
    }

    @Test
    fun `forex usd quote is not mistaken for crypto`() {
        assertEquals(MarketAssetClass.FOREX, MarketSymbolClassifier.classify("GBPUSD"))
        assertEquals(MarketAssetClass.FOREX, MarketSymbolClassifier.classify("AUDUSD"))
    }
}
