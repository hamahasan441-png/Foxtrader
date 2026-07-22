package com.foxtrader.app.domain.usecase.replay

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ReplaySpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ReplayEngine.
 * Validates start/stop, step controls, speed cycling, and state transitions.
 *
 * Note: play() launches a coroutine loop — these tests focus on synchronous operations
 * (step, jump, speed, state) without testing the async playback timing.
 */
class ReplayEngineTest {

    private lateinit var engine: ReplayEngine
    private lateinit var testCandles: List<Candle>

    @Before
    fun setup() {
        engine = ReplayEngine()
        testCandles = (0 until 100).map { i ->
            Candle(
                timestamp = 1_000_000L + i * 60_000L,
                open = 100.0 + i * 0.5,
                high = 101.0 + i * 0.5,
                low = 99.0 + i * 0.5,
                close = 100.5 + i * 0.5,
                volume = 1000.0,
            )
        }
    }

    // ========================================================================
    // START / STOP
    // ========================================================================

    @Test
    fun `initial state is inactive`() {
        val state = engine.state.value
        assertFalse(state.isActive)
        assertEquals(0, state.currentIndex)
        assertTrue(state.visibleCandles.isEmpty())
    }

    @Test
    fun `start activates replay at specified index`() {
        engine.start(testCandles, startAt = 30)
        val state = engine.state.value

        assertTrue(state.isActive)
        assertTrue(state.isPaused) // Starts paused
        assertEquals(30, state.currentIndex)
        assertEquals(100, state.totalBars)
        assertEquals(30, state.visibleCandles.size)
    }

    @Test
    fun `start clamps to valid range`() {
        engine.start(testCandles, startAt = 200) // Beyond data size
        assertEquals(100, engine.state.value.currentIndex)

        engine.stop()
        engine.start(testCandles, startAt = 0) // At zero — clamped to 1
        assertEquals(1, engine.state.value.currentIndex)
    }

    @Test
    fun `stop deactivates replay`() {
        engine.start(testCandles, startAt = 30)
        assertTrue(engine.state.value.isActive)

        engine.stop()
        assertFalse(engine.state.value.isActive)
        assertEquals(0, engine.state.value.currentIndex)
        assertTrue(engine.state.value.visibleCandles.isEmpty())
    }

    // ========================================================================
    // STEP CONTROLS
    // ========================================================================

    @Test
    fun `stepForward advances one bar`() {
        engine.start(testCandles, startAt = 30)
        engine.stepForward()

        assertEquals(31, engine.state.value.currentIndex)
        assertEquals(31, engine.state.value.visibleCandles.size)
    }

    @Test
    fun `stepForward stops at end of data`() {
        engine.start(testCandles, startAt = 99)
        engine.stepForward()

        assertEquals(100, engine.state.value.currentIndex)
        engine.stepForward() // Should not go beyond
        assertEquals(100, engine.state.value.currentIndex)
    }

    @Test
    fun `stepBackward goes one bar back`() {
        engine.start(testCandles, startAt = 30)
        engine.stepBackward()

        assertEquals(29, engine.state.value.currentIndex)
        assertEquals(29, engine.state.value.visibleCandles.size)
    }

    @Test
    fun `stepBackward stops at beginning`() {
        engine.start(testCandles, startAt = 2)
        engine.stepBackward()
        assertEquals(1, engine.state.value.currentIndex)
        engine.stepBackward() // Should not go below 1
        assertEquals(1, engine.state.value.currentIndex)
    }

    @Test
    fun `step does nothing when not active`() {
        engine.stepForward()
        assertEquals(0, engine.state.value.currentIndex) // No change
    }

    // ========================================================================
    // JUMP
    // ========================================================================

    @Test
    fun `jumpTo moves to specified bar`() {
        engine.start(testCandles, startAt = 30)
        engine.jumpTo(75)

        assertEquals(75, engine.state.value.currentIndex)
        assertEquals(75, engine.state.value.visibleCandles.size)
    }

    @Test
    fun `jumpTo clamps to valid range`() {
        engine.start(testCandles, startAt = 30)
        engine.jumpTo(500) // Beyond range
        assertEquals(100, engine.state.value.currentIndex)

        engine.jumpTo(-10) // Negative
        assertEquals(1, engine.state.value.currentIndex)
    }

    // ========================================================================
    // SPEED CONTROLS
    // ========================================================================

    @Test
    fun `setSpeed updates speed`() {
        engine.start(testCandles, startAt = 30)
        engine.setSpeed(ReplaySpeed.SPEED_4)
        assertEquals(ReplaySpeed.SPEED_4, engine.state.value.speed)
    }

    @Test
    fun `cycleSpeed advances to next speed`() {
        engine.start(testCandles, startAt = 30)
        val initialSpeed = engine.state.value.speed
        engine.cycleSpeed()
        val newSpeed = engine.state.value.speed

        // Should be different from initial (wraps around)
        assertTrue(initialSpeed != newSpeed || ReplaySpeed.entries.size == 1)
    }

    @Test
    fun `cycleSpeed wraps around from last to first`() {
        engine.start(testCandles, startAt = 30)
        engine.setSpeed(ReplaySpeed.entries.last())
        engine.cycleSpeed()
        assertEquals(ReplaySpeed.entries.first(), engine.state.value.speed)
    }

    // ========================================================================
    // PROGRESS
    // ========================================================================

    @Test
    fun `progress reflects current position`() {
        engine.start(testCandles, startAt = 50)
        val progress = engine.state.value.progress
        assertEquals(0.5f, progress, 0.01f)
    }

    @Test
    fun `progress is 0 when not started`() {
        assertEquals(0f, engine.state.value.progress, 0.001f)
    }

    // ========================================================================
    // VISIBLE CANDLES CORRECTNESS
    // ========================================================================

    @Test
    fun `visibleCandles contains exactly currentIndex bars`() {
        engine.start(testCandles, startAt = 40)
        val visible = engine.state.value.visibleCandles
        assertEquals(40, visible.size)
        // First visible candle should be the first in the original data
        assertEquals(testCandles[0], visible[0])
        // Last visible should be at index currentIndex-1
        assertEquals(testCandles[39], visible.last())
    }

    @Test
    fun `visibleCandles updates on stepForward`() {
        engine.start(testCandles, startAt = 40)
        engine.stepForward()
        val visible = engine.state.value.visibleCandles
        assertEquals(41, visible.size)
        assertEquals(testCandles[40], visible.last())
    }
}
