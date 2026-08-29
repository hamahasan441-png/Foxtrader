package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity

/**
 * Which markets Keystone is willing to read a divergence against, and in which
 * direction each of them is expected to move.
 *
 * SMT is only evidence when the two markets are genuinely driven by the same
 * thing. Two arbitrary symbols disagree constantly, and a divergence between
 * them says nothing at all — so the pairs here are the ones with a shared
 * driver: the same index complex, the same base currency, or the dollar itself
 * standing on the other side of the trade.
 *
 * The polarity is not decoration. XAUUSD and DXY move against each other by
 * construction, and reading them as a positive pair would report a divergence
 * on almost every bar while missing every real one. The measured correlation is
 * still checked at run time; this table only says which sign to expect.
 */
object KeystoneCorrelation {

    data class Peer(val symbol: String, val polarity: KeystonePolarity)

    /**
     * Peers for [symbol], strongest relationship first.
     *
     * Returns empty for a symbol with no defensible partner. That is a real
     * answer: with SMT required, Keystone stands down rather than inventing a
     * correlation to satisfy its own rule.
     */
    fun peersFor(symbol: String): List<Peer> = TABLE[symbol.uppercase()].orEmpty()
        .filterNot { it.symbol.equals(symbol, ignoreCase = true) }

    private val TABLE: Map<String, List<Peer>> = buildMap {
        // Index complex. NAS100/US500/US30 are this app's names for the same
        // relationship the model describes as ES/NQ.
        put("NAS100", listOf(positive("US500"), positive("US30")))
        put("US500", listOf(positive("NAS100"), positive("US30")))
        put("US30", listOf(positive("US500"), positive("NAS100")))

        // Dollar-quoted majors move together against the dollar.
        put("EURUSD", listOf(positive("GBPUSD"), inverse("DXY")))
        put("GBPUSD", listOf(positive("EURUSD"), inverse("DXY")))
        put("AUDUSD", listOf(positive("NZDUSD"), positive("EURUSD")))
        put("NZDUSD", listOf(positive("AUDUSD"), positive("EURUSD")))

        // Dollar-based majors move together with the dollar, so they move
        // against the dollar-quoted ones.
        put("USDJPY", listOf(positive("USDCHF"), positive("USDCAD")))
        put("USDCHF", listOf(positive("USDJPY"), positive("USDCAD")))
        put("USDCAD", listOf(positive("USDCHF"), positive("USDJPY")))

        // Metals: silver alongside gold, the dollar index against both.
        put("XAUUSD", listOf(positive("XAGUSD"), inverse("DXY")))
        put("XAGUSD", listOf(positive("XAUUSD"), inverse("DXY")))
        put("DXY", listOf(inverse("EURUSD"), inverse("XAUUSD")))

        // Crypto majors share a single risk driver.
        put("BTCUSDT", listOf(positive("ETHUSDT"), positive("SOLUSDT")))
        put("ETHUSDT", listOf(positive("BTCUSDT"), positive("SOLUSDT")))
        put("SOLUSDT", listOf(positive("BTCUSDT"), positive("ETHUSDT")))
        put("BNBUSDT", listOf(positive("BTCUSDT"), positive("ETHUSDT")))
    }

    private fun positive(symbol: String) = Peer(symbol, KeystonePolarity.POSITIVE)
    private fun inverse(symbol: String) = Peer(symbol, KeystonePolarity.INVERSE)
}
