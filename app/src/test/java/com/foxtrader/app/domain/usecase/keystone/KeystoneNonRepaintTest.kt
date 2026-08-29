package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property the whole engine is built around.
 *
 * A signal drawn on a chart is a claim that the market said something at that
 * bar. If a later bar can create, move or delete it, the claim was never true —
 * the chart is showing what the engine would have said with hindsight, and any
 * measurement taken from it is measuring hindsight.
 *
 * The test is the definition rather than a proxy for it: run the engine over
 * the series truncated at bar `t`, and every signal it reports must be exactly
 * what the full-series run reports for bars at or before `t`.
 */
class KeystoneNonRepaintTest {

    @Test
    fun `truncated runs agree with the full run`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val peers = listOf(KeystoneFixtures.peerOf(built))
        val full = analyze(built.primary, peers)
        assertTrue(
            "The fixture must contain signals or this test proves nothing.",
            full.isNotEmpty(),
        )

        var compared = 0
        for (cut in listOf(400, 600, 800, 1000, 1200, 1400)) {
            if (cut > built.primary.lastIndex) continue
            val truncated = analyze(
                built.primary.subList(0, cut + 1),
                listOf(KeystonePeerSeries(peers[0].symbol, built.peer.subList(0, cut + 1), peers[0].polarity)),
            )
            val expected = full.filter { it.index <= cut }
            assertEquals(
                "Truncating at $cut changed which signals exist at or before $cut.",
                expected.map { it.fingerprint() },
                truncated.map { it.fingerprint() },
            )
            compared += expected.size
        }
        assertTrue("No signal was actually compared.", compared > 0)
    }

    @Test
    fun `a peer that has not yet formed cannot retroactively create a signal`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val full = analyze(built.primary, listOf(KeystoneFixtures.peerOf(built)))

        // Every divergence a published signal rests on must have confirmed at
        // or before the bar the signal was published on.
        for (signal in full) {
            val divergence = signal.divergence
            assertTrue(
                "Signal at ${signal.index} rests on a divergence confirmed later.",
                divergence == null || divergence.confirmationIndex <= signal.index,
            )
        }
    }

    private fun analyze(candles: List<com.foxtrader.app.domain.model.Candle>, peers: List<KeystonePeerSeries>) =
        KeystoneFixtures.engine().analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = candles,
            peers = peers,
        ).signals

    private fun KeystoneSignal.fingerprint(): String =
        "$index|$direction|${"%.5f".format(entry)}|${"%.5f".format(stopLoss)}|${"%.5f".format(takeProfit)}"
}
