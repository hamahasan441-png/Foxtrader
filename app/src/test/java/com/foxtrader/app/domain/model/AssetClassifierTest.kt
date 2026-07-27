package com.foxtrader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Symbol -> asset class inference.
 *
 * The ordering trap this guards: a crypto pair like `BTCUSD` is six characters
 * with a fiat quote, so a naive forex check would claim it as FX. Crypto must
 * be evaluated first.
 */
class AssetClassifierTest {

    @Test
    fun `classifies major forex pairs`() {
        listOf("EURUSD", "GBPUSD", "USDJPY", "AUDNZD", "eurusd").forEach { symbol ->
            assertEquals("$symbol should be FOREX", AssetClass.FOREX, AssetClassifier.classify(symbol))
        }
    }

    @Test
    fun `classifies crypto by quote suffix`() {
        listOf("BTCUSDT", "ETHUSDC", "SOLBUSD").forEach { symbol ->
            assertEquals("$symbol should be CRYPTO", AssetClass.CRYPTO, AssetClassifier.classify(symbol))
        }
    }

    @Test
    fun `crypto base beats the forex pair shape`() {
        // Six chars, both halves look like fiat-adjacent tickers.
        assertEquals(AssetClass.CRYPTO, AssetClassifier.classify("BTCUSD"))
        assertEquals(AssetClass.CRYPTO, AssetClassifier.classify("ETHEUR"))
    }

    @Test
    fun `classifies metals`() {
        assertEquals(AssetClass.METALS, AssetClassifier.classify("XAUUSD"))
        assertEquals(AssetClass.METALS, AssetClassifier.classify("XAGUSD"))
    }

    @Test
    fun `classifies indices`() {
        listOf("US30", "NAS100", "US500", "GER40").forEach { symbol ->
            assertEquals("$symbol should be INDICES", AssetClass.INDICES, AssetClassifier.classify(symbol))
        }
    }

    @Test
    fun `classifies energy`() {
        assertEquals(AssetClass.ENERGY, AssetClassifier.classify("WTIUSD"))
        assertEquals(AssetClass.ENERGY, AssetClassifier.classify("BRENTUSD"))
    }

    @Test
    fun `unknown tickers fall back to stocks`() {
        listOf("AAPL", "TSLA", "NVDA").forEach { symbol ->
            assertEquals(AssetClass.STOCKS, AssetClassifier.classify(symbol))
        }
    }

    @Test
    fun `handles whitespace, case and empty input without throwing`() {
        assertEquals(AssetClass.FOREX, AssetClassifier.classify("  eurusd  "))
        assertEquals(AssetClass.STOCKS, AssetClassifier.classify(""))
        assertEquals(AssetClass.STOCKS, AssetClassifier.classify("   "))
    }

    @Test
    fun `five-letter fiat-like ticker is not forex`() {
        // Only an exact 6-char fiat+fiat shape qualifies.
        assertEquals(AssetClass.STOCKS, AssetClassifier.classify("USDX"))
    }
}
