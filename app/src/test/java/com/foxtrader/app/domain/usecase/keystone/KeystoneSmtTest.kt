package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoneSmtTest {

    private val smt = KeystoneSmt()
    private val config = KeystoneConfig()

    @Test
    fun `finds the divergence at the sweep the fixture contains`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        val alignments = smt.align(built.primary, listOf(KeystoneFixtures.peerOf(built)), config)
        assertTrue("The peer could not be aligned at all.", alignments.isNotEmpty())

        val found = built.sweepBars.mapNotNull {
            smt.divergenceAt(it, Direction.BULLISH, built.primary, alignments, config)
        }
        assertTrue("No sweep carried a divergence in a series built to contain them.", found.isNotEmpty())
        assertTrue(found.all { it.direction == Direction.BULLISH })
        assertTrue("The divergence must be stamped at the sweep it supports.", found.all { it.strength > 0.0 })
    }

    /**
     * The filter has to be able to say no, or it is not a filter.
     *
     * A peer that follows the primary through the swept low agrees with it, and
     * agreement is the whole thing this test exists to detect the absence of.
     * Without this case, a divergence test that returned "yes" unconditionally
     * would pass every other test in this file.
     */
    @Test
    fun `a peer that follows the move is not a divergence`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        val follower = KeystonePeerSeries(KeystoneFixtures.PEER, built.primary, KeystonePolarity.POSITIVE)
        val alignments = smt.align(built.primary, listOf(follower), config)

        val found = built.sweepBars.mapNotNull {
            smt.divergenceAt(it, Direction.BULLISH, built.primary, alignments, config)
        }
        assertTrue("A peer making the identical low was read as diverging.", found.isEmpty())
    }

    /**
     * Nothing after the sweep may change what the sweep was supported by.
     *
     * The window form makes this true by construction rather than by care,
     * which is the reason to prefer it: the answer at a bar is computed from
     * that bar and the ones before it, so a longer series cannot revise it.
     */
    @Test
    fun `the answer at a bar does not change as the series grows`() {
        val built = KeystoneFixtures.sequence(cycles = 20)
        val peer = KeystoneFixtures.peerOf(built)
        val full = smt.align(built.primary, listOf(peer), config)

        var compared = 0
        for (cut in listOf(400, 600, 800)) {
            if (cut > built.primary.lastIndex) continue
            val truncated = smt.align(
                built.primary.subList(0, cut + 1),
                listOf(KeystonePeerSeries(peer.symbol, built.peer.subList(0, cut + 1), peer.polarity)),
                config,
            )
            for (sweep in built.sweepBars.filter { it <= cut }) {
                val a = smt.divergenceAt(sweep, Direction.BULLISH, built.primary, full, config)
                val b = smt.divergenceAt(
                    sweep, Direction.BULLISH, built.primary.subList(0, cut + 1), truncated, config,
                )
                assertEquals(
                    "Truncating at $cut changed the divergence at sweep $sweep.",
                    a?.fingerprint(),
                    b?.fingerprint(),
                )
                compared++
            }
        }
        assertTrue("No sweep was actually compared.", compared > 0)
    }

    @Test
    fun `an inverse peer is read against the primary, not with it`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        // The same peer series declared inverse. It correlates positively with
        // the primary, so the sign test must refuse it.
        val asInverse = KeystonePeerSeries(KeystoneFixtures.PEER, built.peer, KeystonePolarity.INVERSE)
        val alignments = smt.align(built.primary, listOf(asInverse), config)

        val found = built.sweepBars.mapNotNull {
            smt.divergenceAt(it, Direction.BULLISH, built.primary, alignments, config)
        }
        assertTrue("A positively correlated peer was accepted as an inverse one.", found.isEmpty())
    }

    @Test
    fun `no peer means no divergence`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        assertTrue(smt.align(built.primary, emptyList(), config).isEmpty())
        assertNull(
            smt.divergenceAt(built.sweepBars.first(), Direction.BULLISH, built.primary, emptyList(), config),
        )
    }

    @Test
    fun `a bar where the primary made no new extreme carries no divergence`() {
        val built = KeystoneFixtures.sequence(cycles = 12)
        val alignments = smt.align(built.primary, listOf(KeystoneFixtures.peerOf(built)), config)
        // The bar after each sweep is an up candle, so it makes no new low.
        val after = built.sweepBars.mapNotNull {
            smt.divergenceAt(it + 1, Direction.BULLISH, built.primary, alignments, config)
        }
        assertTrue("A bar that took no liquidity reported a divergence.", after.isEmpty())
        assertNotNull(
            "Sanity: the sweep itself must still report one.",
            built.sweepBars.firstNotNullOfOrNull {
                smt.divergenceAt(it, Direction.BULLISH, built.primary, alignments, config)
            },
        )
    }

    private fun com.foxtrader.app.domain.usecase.keystone.model.KeystoneDivergence.fingerprint(): String =
        "$confirmationIndex|$direction|$peerSymbol|${"%.6f".format(strength)}|${"%.6f".format(correlation)}"
}
