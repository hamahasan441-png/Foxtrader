package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample-size gate on reported accuracy.
 *
 * A win rate computed from a handful of resolved signals is noise wearing a
 * percentage sign. "100% (3 signals)" is the failure mode that matters: it reads
 * as a strong result, it is not one, and a trader who sizes up on it is being
 * misled by the app rather than by the market.
 *
 * The gate is a display concern, so the counts stay available — [SourceStats]
 * still reports resolved/wins/losses and the raw [SourceStats.winRate]. Only
 * [SourceStats.reportableWinRate] is withheld, which lets the UI show
 * "4W / 1L — not enough data" instead of either a fake percentage or nothing.
 */
class SignalOutcomeEvaluatorSampleSizeTest {

    private fun stats(wins: Int, losses: Int) = SignalOutcomeEvaluator.SourceStats(
        source = SignalSource.LITX,
        resolved = wins + losses,
        wins = wins,
        losses = losses,
        unresolved = 0,
        winRate = if (wins + losses == 0) null else wins.toDouble() / (wins + losses),
        averageR = 1.0,
        profitFactor = 2.0,
    )

    @Test
    fun `a perfect record on a tiny sample is not reportable`() {
        val tiny = stats(wins = 3, losses = 0)
        assertEquals(1.0, tiny.winRate!!, 1e-9)
        assertFalse("3 resolved signals is not evidence", tiny.rateIsMeaningful)
        assertNull("a 100% rate from n=3 must not be quotable", tiny.reportableWinRate)
    }

    @Test
    fun `counts survive the gate so the UI can still say something true`() {
        val tiny = stats(wins = 4, losses = 1)
        assertEquals(5, tiny.resolved)
        assertEquals(4, tiny.wins)
        assertEquals(1, tiny.losses)
        assertNotNull("the raw rate stays available for diagnostics", tiny.winRate)
    }

    @Test
    fun `the rate becomes reportable exactly at the threshold`() {
        val below = stats(wins = 10, losses = 9)
        val at = stats(wins = 10, losses = 10)
        assertEquals(19, below.resolved)
        assertEquals(SignalOutcomeEvaluator.MIN_RESOLVED_FOR_RATE, at.resolved)
        assertFalse(below.rateIsMeaningful)
        assertTrue(at.rateIsMeaningful)
        assertNull(below.reportableWinRate)
        assertEquals(0.5, at.reportableWinRate!!, 1e-9)
    }

    @Test
    fun `an empty record set reports no rate at all`() {
        val empty = stats(wins = 0, losses = 0)
        assertNull(empty.winRate)
        assertNull(empty.reportableWinRate)
        assertFalse(empty.rateIsMeaningful)
    }

    @Test
    fun `a selective mode with few resolved signals is withheld rather than flattered`() {
        // The LiT Adventure SNIPER case: correct behaviour is silence, not a
        // confident number, until the sample catches up.
        val sniperLike = stats(wins = 6, losses = 1)
        assertFalse(sniperLike.rateIsMeaningful)
        assertNull(sniperLike.reportableWinRate)
    }
}
