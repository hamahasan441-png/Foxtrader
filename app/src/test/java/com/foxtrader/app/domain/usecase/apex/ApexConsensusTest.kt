package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.apex.model.ApexVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clustering rule, driven with hand-built votes.
 *
 * The arrangement that makes this rule matter — a third member confirming a few
 * bars after two already agreed — is rare enough that a synthetic price series
 * cannot be relied on to contain it. Constructing it directly is the only way
 * to know the rule holds rather than merely hoping it was exercised.
 */
class ApexConsensusTest {

    private fun vote(member: ApexMember, index: Int) = ApexVote(
        member = member,
        direction = Direction.BULLISH,
        index = index,
        timestamp = index * 60_000L,
        entry = 1.1000,
        stop = 1.0990,
        target = 1.1020,
    )

    private fun cluster(votes: List<ApexVote>, k: Int = 2, window: Int = 36) =
        ApexConsensus.cluster(votes, k, window)

    @Test
    fun `a cluster is stamped on the vote that completed the agreement`() {
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 100),
            vote(ApexMember.VIRGIN_WICK, 110),
        )
        val clusters = cluster(votes)
        assertEquals(1, clusters.size)
        assertEquals(110, clusters.single().maxOf { it.index })
    }

    @Test
    fun `a later member cannot move a cluster that already agreed`() {
        // The repaint case. Two members agree at bar 110; a third confirms at
        // 130, still inside the window. If the third were allowed to join, the
        // marker drawn at 110 would jump to 130 as soon as bar 130 closed.
        val early = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 100),
            vote(ApexMember.VIRGIN_WICK, 110),
        )
        val late = early + vote(ApexMember.RSI_ORDERFLOW, 130)

        val before = cluster(early).single().maxOf { it.index }
        val after = cluster(late).first().maxOf { it.index }

        assertEquals("agreement was reached at 110", 110, before)
        assertEquals("a later vote moved a decision already made", 110, after)
    }

    @Test
    fun `growing the vote list never moves or removes an existing cluster`() {
        // The same property stated generally: replaying the votes one at a time
        // must only ever append.
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 10),
            vote(ApexMember.VIRGIN_WICK, 20),
            vote(ApexMember.RSI_ORDERFLOW, 25),
            vote(ApexMember.AMD, 30),
            vote(ApexMember.VALUE_AREA_REJECTION, 44),
            vote(ApexMember.PIVOT_SWEEP_DIVERGENCE, 200),
            vote(ApexMember.LIQUIDITY_SWEEP, 210),
        )
        var previous = emptyList<Int>()
        for (n in 1..votes.size) {
            val stamps = cluster(votes.take(n)).map { c -> c.maxOf { it.index } }
            assertTrue(
                "a cluster moved or vanished when vote $n arrived: $previous then $stamps",
                stamps.size >= previous.size && stamps.take(previous.size) == previous,
            )
            previous = stamps
        }
    }

    @Test
    fun `votes beyond the window do not agree`() {
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 100),
            vote(ApexMember.VIRGIN_WICK, 200),
        )
        assertTrue(cluster(votes, window = 36).isEmpty())
        assertEquals(1, cluster(votes, window = 100).size)
    }

    @Test
    fun `one member repeating itself is not agreement`() {
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 100),
            vote(ApexMember.LIQUIDITY_SWEEP, 105),
            vote(ApexMember.LIQUIDITY_SWEEP, 110),
        )
        assertTrue("one opinion repeated is still one opinion", cluster(votes).isEmpty())
    }

    @Test
    fun `a member voting twice contributes only its earliest vote`() {
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 100),
            vote(ApexMember.LIQUIDITY_SWEEP, 104),
            vote(ApexMember.VIRGIN_WICK, 108),
        )
        val single = cluster(votes).single()
        assertEquals(2, single.size)
        assertEquals(listOf(100, 108), single.map { it.index }.sorted())
    }

    @Test
    fun `a closed cluster does not consume the votes of the next one`() {
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 10),
            vote(ApexMember.VIRGIN_WICK, 12),
            vote(ApexMember.RSI_ORDERFLOW, 14),
            vote(ApexMember.AMD, 16),
        )
        val clusters = cluster(votes)
        assertEquals("two independent pairs agreed", 2, clusters.size)
        assertEquals(listOf(12, 16), clusters.map { c -> c.maxOf { it.index } })
    }

    @Test
    fun `raising the requirement never produces more clusters`() {
        val votes = listOf(
            vote(ApexMember.LIQUIDITY_SWEEP, 10),
            vote(ApexMember.VIRGIN_WICK, 12),
            vote(ApexMember.RSI_ORDERFLOW, 14),
            vote(ApexMember.AMD, 16),
            vote(ApexMember.VALUE_AREA_REJECTION, 18),
            vote(ApexMember.PIVOT_SWEEP_DIVERGENCE, 20),
        )
        val counts = (2..6).map { cluster(votes, k = it).size }
        assertEquals("a stricter requirement admitted more", counts.sortedDescending(), counts)
        cluster(votes, k = 3).forEach {
            assertEquals("a cluster formed without enough members", 3, it.size)
        }
    }

    @Test
    fun `degenerate input is handled without throwing`() {
        assertTrue(cluster(emptyList()).isEmpty())
        assertTrue(cluster(listOf(vote(ApexMember.AMD, 5))).isEmpty())
        assertEquals(1, cluster(listOf(vote(ApexMember.AMD, 5), vote(ApexMember.LIQUIDITY_SWEEP, 5))).size)
    }
}
