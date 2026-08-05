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

    @Test
    fun `empty breaks yields no shift`() {
        assertFalse(classifier.classify(emptyList(), null).present)
    }

    @Test
    fun `a BOS-only list yields no shift`() {
        val bos = structureBreak(40, type = StructureBreakType.BOS)
        assertFalse(classifier.classify(listOf(bos), null).present)
    }

    @Test
    fun `an unconfirmed break is ignored`() {
        val choch = structureBreak(40, confirmed = false)
        assertFalse(classifier.classify(listOf(choch), null).present)
    }

    @Test
    fun `a stale break before the setup window is rejected`() {
        val stale = structureBreak(10)
        val result = classifier.classify(listOf(stale), displacement = null, minBreakIndex = 30)
        assertFalse("a shift older than the setup window must not validate", result.present)
    }

    @Test
    fun `a recent break at the window boundary is accepted`() {
        val recent = structureBreak(30)
        val result = classifier.classify(listOf(recent), displacement = null, minBreakIndex = 30)
        assertTrue(result.present)
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertEquals(30, result.breakIndex)
    }

    @Test
    fun `the most recent qualifying break is selected`() {
        val older = structureBreak(35)
        val newer = structureBreak(45)
        val result = classifier.classify(listOf(older, newer), null)
        assertEquals(45, result.breakIndex)
    }

    @Test
    fun `an aligned nearby displacement upgrades a CHOCH to MSS`() {
        val choch = structureBreak(40)
        val disp = displacement(43) // gap 3 <= 5
        val result = classifier.classify(listOf(choch), disp)
        assertEquals(StructureBreakType.MSS, result.type)
        assertTrue(result.isStrong)
    }

    @Test
    fun `displacement exactly at the gap boundary still upgrades`() {
        val choch = structureBreak(40)
        val disp = displacement(45) // gap 5 == max
        assertEquals(StructureBreakType.MSS, classifier.classify(listOf(choch), disp).type)
    }

    @Test
    fun `displacement beyond the gap leaves it a CHOCH`() {
        val choch = structureBreak(40)
        val disp = displacement(46) // gap 6 > 5
        val result = classifier.classify(listOf(choch), disp)
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertFalse(result.isStrong)
    }

    @Test
    fun `a misaligned displacement direction leaves it a CHOCH`() {
        val choch = structureBreak(40, direction = Direction.BULLISH)
        val disp = displacement(41, direction = Direction.BEARISH)
        assertEquals(StructureBreakType.CHOCH, classifier.classify(listOf(choch), disp).type)
    }

    @Test
    fun `a weak displacement below the ATR multiple leaves it a CHOCH`() {
        val choch = structureBreak(40)
        val disp = displacement(41, atrMultiple = 1.0) // below default 1.2 threshold
        val result = classifier.classify(listOf(choch), disp)
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertFalse(result.isStrong)
    }

    @Test
    fun `an upstream MSS label without corroborating displacement is downgraded to CHOCH`() {
        val upstreamMss = structureBreak(40, type = StructureBreakType.MSS)
        val result = classifier.classify(listOf(upstreamMss), displacement = null)
        assertTrue(result.present)
        assertEquals(StructureBreakType.CHOCH, result.type)
        assertFalse(result.isStrong)
    }

    @Test
    fun `direction is propagated from the selected break`() {
        val bearish = structureBreak(40, direction = Direction.BEARISH)
        assertEquals(Direction.BEARISH, classifier.classify(listOf(bearish), null).direction)
    }
}
