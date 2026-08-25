package com.foxtrader.app.data.remote.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for base/quote splitting on dash-addressed venues.
 *
 * The cases in [stablecoins ending in USD are not split on the bare USD suffix]
 * are the ones that were previously wrong. They are not exotic: BUSD, TUSD and
 * FDUSD are among the most-traded quote assets on the venues this code talks to,
 * and a mis-split produced a syntactically valid instrument, so the failure was
 * silent — the request went out, came back empty or with someone else's candles,
 * and nothing in the pipeline flagged it.
 */
class CryptoSymbolNormalizerTest {

    @Test
    fun `stablecoins ending in USD are not split on the bare USD suffix`() {
        // Previously: BTCB-USD, BTCT-USD, BTCFD-USD.
        assertEquals("BTC-BUSD", CryptoSymbolNormalizer.toDashPair("BTCBUSD"))
        assertEquals("BTC-TUSD", CryptoSymbolNormalizer.toDashPair("BTCTUSD"))
        assertEquals("BTC-FDUSD", CryptoSymbolNormalizer.toDashPair("BTCFDUSD"))
        assertEquals("ETH-BUSD", CryptoSymbolNormalizer.toDashPair("ETHBUSD"))
    }

    @Test
    fun `usdt is preferred over usd`() {
        assertEquals("BTC-USDT", CryptoSymbolNormalizer.toDashPair("BTCUSDT"))
        assertEquals("BTC-USDC", CryptoSymbolNormalizer.toDashPair("BTCUSDC"))
        assertEquals("BTC-USD", CryptoSymbolNormalizer.toDashPair("BTCUSD"))
    }

    @Test
    fun `fiat quotes resolve instead of falling through unchanged`() {
        // Previously returned the input verbatim, which the venue rejected.
        assertEquals("BTC-TRY", CryptoSymbolNormalizer.toDashPair("BTCTRY"))
        assertEquals("ETH-BRL", CryptoSymbolNormalizer.toDashPair("ETHBRL"))
        assertEquals("BTC-JPY", CryptoSymbolNormalizer.toDashPair("BTCJPY"))
    }

    @Test
    fun `crypto quotes still resolve`() {
        assertEquals("ETH-BTC", CryptoSymbolNormalizer.toDashPair("ETHBTC"))
        assertEquals("SOL-ETH", CryptoSymbolNormalizer.toDashPair("SOLETH"))
        assertEquals("WBTC-BTC", CryptoSymbolNormalizer.toDashPair("WBTCBTC"))
    }

    @Test
    fun `already delimited symbols pass through`() {
        assertEquals("BTC-USDT", CryptoSymbolNormalizer.toDashPair("BTC-USDT"))
        assertEquals("BTC-USDT", CryptoSymbolNormalizer.toDashPair("BTC/USDT"))
        assertEquals("BTC-USDT", CryptoSymbolNormalizer.toDashPair("btc/usdt"))
    }

    @Test
    fun `unknown pairs fail closed rather than guessing`() {
        // No confident split available: return the symbol untouched so the
        // venue reports an unknown instrument, instead of inventing one.
        assertEquals("XYZABC", CryptoSymbolNormalizer.toDashPair("XYZABC"))
        assertEquals("", CryptoSymbolNormalizer.toDashPair(""))
    }

    @Test
    fun `a symbol equal to a quote asset is never split into an empty base`() {
        assertEquals("USDT", CryptoSymbolNormalizer.toDashPair("USDT"))
        assertEquals("BTC", CryptoSymbolNormalizer.toDashPair("BTC"))
    }

    @Test
    fun `concatenated form strips every delimiter`() {
        assertEquals("BTCUSDT", CryptoSymbolNormalizer.toConcatenatedPair("BTC-USDT"))
        assertEquals("BTCUSDT", CryptoSymbolNormalizer.toConcatenatedPair("btc/usdt"))
        assertEquals("BTCUSDT", CryptoSymbolNormalizer.toConcatenatedPair("BTCUSDT"))
    }
}
