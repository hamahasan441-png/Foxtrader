package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneDivergence
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoneSmtTest {

    private val smt = KeystoneSmt()
    private val config = KeystoneConfig()

    @Test
    fun `finds the divergence the fixture contains`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        val found = smt.detect(built.primary, listOf(KeystoneFixtures.peerOf(built)), config)
        assertTrue("No divergence found in a series built to contain them.", found.isNotEmpty())
        assertTrue(
            "The constructed divergences are bullish; something else was matched.",
            found.any { it.direction == Direction.BULLISH },
        )
    }

    /**
     * A divergence's score must not move once it has confirmed.
     *
     * This is a stricter statement than "the same events are found", and it is
     * the one that catches the subtle version of repainting: an event that
     * keeps its place on the chart while the number attached to it drifts as
     * new bars arrive. Any quantity normalised by a whole-series statistic
     * fails here, which is exactly why the strength is normalised by a rolling
     * window instead.
     */
    @Test
    fun `a confirmed divergence keeps its score as the series grows`() {
        val built = KeystoneFixtures.sequence(cycles = 20)
        val peer = KeystoneFixtures.peerOf(built)
        val full = smt.detect(built.primary, listOf(peer), config)
        assertTrue(full.isNotEmpty())

        var compared = 0
        for (cut in listOf(400, 600, 800)) {
            if (cut > built.primary.lastIndex) continue
            val truncated = smt.detect(
                built.primary.subList(0, cut + 1),
                listOf(KeystonePeerSeries(peer.symbol, built.peer.subList(0, cut + 1), peer.polarity)),
                config,
            )
            val expected = full.filter { it.confirmationIndex <= cut }
            assertEquals(
                "Truncating at $cut re-scored an already confirmed divergence.",
                expected.map { it.fingerprint() },
                truncated.map { it.fingerprint() },
            )
            compared += expected.size
        }
        assertTrue("No divergence was actually compared.", compared > 0)
    }

    @Test
    fun `an inverse peer is read against the primary, not with it`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        // The same peer series, declared inverse. It correlates positively with
        // the primary, so under inverse polarity the sign test must refuse it.
        val asInverse = KeystonePeerSeries(
            KeystoneFixtures.PEER,
            built.peer,
            KeystonePolarity.INVERSE,
        )
        val found = smt.detect(built.primary, listOf(asInverse), config)
        assertTrue(
            "A positively correlated peer was accepted as an inverse one.",
            found.isEmpty(),
        )
    }

    @Test
    fun `no peer means no divergence`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        assertTrue(smt.detect(built.primary, emptyList(), config).isEmpty())
    }

    private fun KeystoneDivergence.fingerprint(): String =
        "$confirmationIndex|$direction|$peerSymbol|${"%.6f".format(strength)}|${"%.6f".format(correlation)}"
}
