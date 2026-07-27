package com.foxtrader.app.domain.usecase.calculator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Symbol -> instrument type.
 *
 * This mapping decides `pipSize` and `contractSize`, which feed position size
 * directly. Getting it wrong does not produce a slightly-off number — it
 * produces one that is wrong by orders of magnitude, in the calculation a
 * trader relies on to size risk.
 */
class InstrumentTypeResolverTest {

    private val resolver = InstrumentTypeResolver()

    @Test
    fun `standard forex uses 4-decimal pips`() {
        listOf("EURUSD", "GBPUSD", "AUDNZD").forEach { symbol ->
            assertEquals(
                "$symbol should be FOREX_STANDARD",
                PositionCalculator.InstrumentType.FOREX_STANDARD,
                resolver.resolve(symbol),
            )
        }
        assertEquals(0.0001, resolver.resolve("EURUSD").pipSize, 1e-12)
    }

    @Test
    fun `JPY pairs use 2-decimal pips`() {
        // A pip on USDJPY is 0.01. Treating it as 0.0001 would size the
        // position 100x too large.
        listOf("USDJPY", "EURJPY", "GBPJPY").forEach { symbol ->
            assertEquals(
                "$symbol should be FOREX_JPY",
                PositionCalculator.InstrumentType.FOREX_JPY,
                resolver.resolve(symbol),
            )
        }
        assertEquals(0.01, resolver.resolve("USDJPY").pipSize, 1e-12)
    }

    @Test
    fun `bitcoin and altcoins resolve to distinct types`() {
        assertEquals(
            PositionCalculator.InstrumentType.CRYPTO_BTC,
            resolver.resolve("BTCUSDT"),
        )
        assertEquals(
            PositionCalculator.InstrumentType.CRYPTO_ALT,
            resolver.resolve("ETHUSDT"),
        )
        assertEquals(
            PositionCalculator.InstrumentType.CRYPTO_ALT,
            resolver.resolve("SOLUSDT"),
        )
    }

    @Test
    fun `metals resolve to gold contract sizing`() {
        assertEquals(PositionCalculator.InstrumentType.GOLD, resolver.resolve("XAUUSD"))
        assertEquals(100.0, resolver.resolve("XAUUSD").contractSize, 1e-9)
    }

    @Test
    fun `energy resolves to oil contract sizing`() {
        assertEquals(PositionCalculator.InstrumentType.OIL, resolver.resolve("WTIUSD"))
        assertEquals(1000.0, resolver.resolve("WTIUSD").contractSize, 1e-9)
    }

    @Test
    fun `indices resolve to point-based sizing`() {
        listOf("US30", "NAS100", "GER40").forEach { symbol ->
            assertEquals(
                PositionCalculator.InstrumentType.INDEX_STANDARD,
                resolver.resolve(symbol),
            )
        }
    }

    @Test
    fun `unknown tickers fall back to a forex-safe default without throwing`() {
        // AAPL classifies as STOCKS -> unit-priced, same shape as an index.
        assertEquals(
            PositionCalculator.InstrumentType.INDEX_STANDARD,
            resolver.resolve("AAPL"),
        )
        assertEquals(
            PositionCalculator.InstrumentType.FOREX_STANDARD,
            resolver.resolve(""),
        )
    }

    @Test
    fun `resolution is case and whitespace insensitive`() {
        assertEquals(
            PositionCalculator.InstrumentType.FOREX_JPY,
            resolver.resolve("  usdjpy  "),
        )
    }
}
