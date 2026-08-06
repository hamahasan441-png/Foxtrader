package com.foxtrader.app.domain.usecase.bars

import com.foxtrader.app.domain.model.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VolumeBarBuilder]. Real instance, synthetic ticks with a known
 * per-tick volume so bar boundaries are exact.
 */
class VolumeBarBuilderTest {

    private lateinit var builder: VolumeBarBuilder

    @Before
    fun setup() {
        builder = VolumeBarBuilder()
    }

    /** Tick with mid == [mid] and total (bid+ask) volume == [volume]. */
    private fun tick(ts: Long, mid: Double, volume: Double = 2.0) =
        Tick(timestampMs = ts, bid = mid, ask = mid, bidVolume = volume / 2.0, askVolume = volume / 2.0)

    @Test
    fun `empty input yields no bars`() {
        assertTrue(builder.build(emptyList(), volumePerBar = 10.0).isEmpty())
    }

    @Test
    fun `non-positive threshold yields no bars`() {
        val ticks = listOf(tick(1, 100.0), tick(2, 101.0))
        assertTrue(builder.build(ticks, volumePerBar = 0.0).isEmpty())
    }

    @Test
    fun `closes a bar each time accumulated volume reaches the threshold`() {
        // 10 ticks, mids 100..109, 2.0 volume each. Threshold 10 -> bar every 5 ticks.
        val ticks = (0 until 10).map { i -> tick(i.toLong(), 100.0 + i, volume = 2.0) }
        val bars = builder.build(ticks, volumePerBar = 10.0)

        assertEquals(2, bars.size)

        // Bar 0: ticks with mids 100..104
        assertEquals(100.0, bars[0].open, 1e-9)
        assertEquals(104.0, bars[0].high, 1e-9)
        assertEquals(100.0, bars[0].low, 1e-9)
        assertEquals(104.0, bars[0].close, 1e-9)
        assertEquals(10.0, bars[0].volume, 1e-9)

        // Bar 1: ticks with mids 105..109
        assertEquals(105.0, bars[1].open, 1e-9)
        assertEquals(109.0, bars[1].close, 1e-9)
        assertEquals(10.0, bars[1].volume, 1e-9)
    }

    @Test
    fun `trailing incomplete volume is emitted as a forming bar`() {
        // 7 ticks * 2.0 = 14 total. Threshold 10 -> one full bar (vol 10), one partial (vol 4).
        val ticks = (0 until 7).map { i -> tick(i.toLong(), 100.0 + i, volume = 2.0) }
        val bars = builder.build(ticks, volumePerBar = 10.0)

        assertEquals(2, bars.size)
        assertEquals(10.0, bars[0].volume, 1e-9)
        assertEquals(4.0, bars[1].volume, 1e-9)
    }
}
