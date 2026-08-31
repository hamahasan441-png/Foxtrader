package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Displacement
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused regression suite for [MssClassifier] — the heart of the LIT X
 * "ordered, recent shift" validation shipped in the sequencing fix. Each case
 * is a pure function call with fully-controlled inputs (no candle fixtures), so
 * the recency gate, displacement-proximity window, confirmed-only filter, and
 * MSS↔CHOCH downgrade rules are pinned exactly.
 */
class MssClassifierTest {

    private val classifier = MssClassifier()

    private fun structureBreak(
        index: Int,
        direction: Direction = Direction.BULLISH,
        confirmed: Boolean = true,
        type: StructureBreakType = StructureBreakType.CHOCH,
    ) = StructureBreak(type, direction, 1.10, 0L, index, confirmed)

    private fun displacement(
        startIndex: Int,
        direction: Direction = Direction.BULLISH,
        atrMultiple: Double = 1.5,
    ) = Displacement(direction, startIndex, startIndex, 1.10, 1.13, 0.9, atrMultiple, true)

    /**
     * Stands in for the real corroboration search.
     *
     * The classifier is handed a search over the break's own window rather than
     * one candidate impulse, so the window and direction tests below live here —
     * which is exactly where they live in production, in
     * [com.foxtrader.app.domain.usecase.litx.DisplacementDetector.detectInWindow].
     */
    private fun search(vararg candidates: Displacement): (Direction, Int, Int) -> Displacement? =
        { direction, from, to ->
            candidates
                .filter { it.direction == direction && it.startIndex in from..to }
                .maxByOrNull { it.atrMultiple }
        }

    private val noImpulse: (Direction, Int, Int) -> Displacement? = { _, _, _ -> null }

    @Test
    fun `empty breaks yields no shift`() {
        assertFalse(classifier.classify(emptyList(), corroboration = noImpulse).present)
    }

    @Test
    fun `a BOS-only list yields no shift`() {
        val bos = structureBreak(40, type = StructureBreakType.BOS)
        assertFalse(classifier.classify(listOf(bos), corroboration = noImpulse).present)
    }

    @Test
    fun `an unconfirmed break is ignored`() {
        val choch = structureBreak(40, confirmed = false)
        assertFalse(classifier.classify(listOf(choch), corroboration = noImpulse).present)
    }

    @Test
    fun `a stale break before the setup window is rejected`() {
        val stale = structureBreak(10)
        val result = classifier.classify(listOf(stale), minBreakIndex = 30, corroboration = noImpulse)
        assertFalse("a shift older than the setup window must not validate", result.present)
    }

    @Test
    fun `a recent break at the window boundary is accepted`() {
        val recent = structureBreak(30)
        val result = classifier.classify(listOf(recent), minBreakIndex = 30, corroboration = noImpulse)
        assertTrue(result.present)
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertEquals(30, result.breakIndex)
    }

    @Test
    fun `the most recent qualifying break is selected`() {
        val older = structureBreak(35)
        val newer = structureBreak(45)
        val result = classifier.classify(listOf(older, newer), corroboration = noImpulse)
        assertEquals(45, result.breakIndex)
    }

    @Test
    fun `an aligned nearby displacement upgrades a CHOCH to MSS`() {
        val choch = structureBreak(40)
        val disp = displacement(43) // gap 3 <= 5
        val result = classifier.classify(listOf(choch), corroboration = search(disp))
        assertEquals(StructureBreakType.MSS, result.type)
        assertTrue(result.isStrong)
    }

    @Test
    fun `displacement exactly at the gap boundary still upgrades`() {
        val choch = structureBreak(40)
        val disp = displacement(45) // gap 5 == max
        assertEquals(StructureBreakType.MSS, classifier.classify(listOf(choch), corroboration = search(disp)).type)
    }

    @Test
    fun `displacement beyond the gap leaves it a CHOCH`() {
        val choch = structureBreak(40)
        val disp = displacement(46) // gap 6 > 5
        val result = classifier.classify(listOf(choch), corroboration = search(disp))
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertFalse(result.isStrong)
    }

    @Test
    fun `a misaligned displacement direction leaves it a CHOCH`() {
        val choch = structureBreak(40, direction = Direction.BULLISH)
        val disp = displacement(41, direction = Direction.BEARISH)
        assertEquals(StructureBreakType.CHOCH, classifier.classify(listOf(choch), corroboration = search(disp)).type)
    }

    @Test
    fun `a weak displacement below the ATR multiple leaves it a CHOCH`() {
        val choch = structureBreak(40)
        val disp = displacement(41, atrMultiple = 1.0) // below default 1.2 threshold
        val result = classifier.classify(listOf(choch), corroboration = search(disp))
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertFalse(result.isStrong)
    }

    @Test
    fun `an upstream MSS label without corroborating displacement is downgraded to CHOCH`() {
        val upstreamMss = structureBreak(40, type = StructureBreakType.MSS)
        val result = classifier.classify(listOf(upstreamMss), corroboration = noImpulse)
        assertTrue(result.present)
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertFalse(result.isStrong)
    }

    @Test
    fun `direction is propagated from the selected break`() {
        val bearish = structureBreak(40, direction = Direction.BEARISH)
        assertEquals(Direction.BEARISH, classifier.classify(listOf(bearish), corroboration = noImpulse).direction)
    }
}
