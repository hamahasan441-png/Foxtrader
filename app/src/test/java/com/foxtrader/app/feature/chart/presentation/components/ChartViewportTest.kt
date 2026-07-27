package com.foxtrader.app.feature.chart.presentation.components

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the chart camera (DEVELOPMENT.md §4.2, §4.6, §4.8, §4.9).
 *
 * These are pure-math tests: [ChartViewport] holds no Compose state and no
 * Android dependencies, so the whole coordinate system and the fling physics
 * are verifiable on the JVM without an emulator.
 */
class ChartViewportTest {

    private val chartWidth = 1000f
    private val chartHeight = 500f

    private fun candles(count: Int): List<Candle> = List(count) { i ->
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = 100.0 + i,
            high = 101.0 + i,
            low = 99.0 + i,
            close = 100.5 + i,
            volume = 1_000.0,
        )
    }

    // ========================================================================
    // COORDINATE TRANSFORMS
    // ========================================================================

    @Test
    fun `xForIndex and indexForX are inverse transforms`() {
        val vp = ChartViewport(startIndex = 40f, visibleBars = 120f)

        for (index in intArrayOf(40, 75, 100, 159)) {
            val x = vp.xForIndex(index.toFloat(), chartWidth)
            val roundTrip = vp.indexForX(x, chartWidth)
            assertEquals(index.toFloat(), roundTrip, 0.001f)
        }
    }

    @Test
    fun `yForPrice and priceForY are inverse transforms`() {
        val vp = ChartViewport(priceHigh = 1.2000, priceLow = 1.1000)

        for (price in doubleArrayOf(1.1000, 1.1250, 1.1500, 1.2000)) {
            val y = vp.yForPrice(price, chartHeight)
            assertEquals(price, vp.priceForY(y, chartHeight), 1e-9)
        }
    }

    @Test
    fun `yForPrice does not divide by zero on a flat price range`() {
        val vp = ChartViewport(priceHigh = 1.0, priceLow = 1.0)

        val y = vp.yForPrice(1.0, chartHeight)

        assertTrue("expected a finite y, got $y", y.isFinite())
    }

    // ========================================================================
    // PAN / ZOOM (§4.6)
    // ========================================================================

    @Test
    fun `panning right reveals older bars`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        vp.panByPixels(panPx = 100f, chartAreaWidth = chartWidth)

        // 100px at 10 bars/100px = 10 bars back into history.
        assertEquals(90f, vp.startIndex, 0.001f)
    }

    @Test
    fun `zoom keeps the bar under the centroid pinned`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)
        val centroidX = 250f // one quarter across the chart
        val barUnderFingerBefore = vp.indexForX(centroidX, chartWidth)

        vp.zoomBy(zoom = 2f, centroidX = centroidX, chartAreaWidth = chartWidth, total = 1000)

        val barUnderFingerAfter = vp.indexForX(centroidX, chartWidth)
        assertEquals(
            "the bar under the pinch centroid must not move",
            barUnderFingerBefore,
            barUnderFingerAfter,
            0.001f,
        )
        assertEquals("zoom of 2x should halve the visible bars", 50f, vp.visibleBars, 0.001f)
    }

    @Test
    fun `zoom out is bounded by the dataset size`() {
        val vp = ChartViewport(startIndex = 0f, visibleBars = 100f)

        vp.zoomBy(zoom = 0.001f, centroidX = 500f, chartAreaWidth = chartWidth, total = 300)

        assertEquals(300f, vp.visibleBars, 0.001f)
    }

    @Test
    fun `zoom in is bounded by the minimum bar count`() {
        val vp = ChartViewport(startIndex = 0f, visibleBars = 100f)

        vp.zoomBy(zoom = 1000f, centroidX = 500f, chartAreaWidth = chartWidth, total = 1000)

        assertEquals(ChartViewport.MIN_VISIBLE_BARS, vp.visibleBars, 0.001f)
    }

    @Test
    fun `identity zoom is a no-op`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        vp.zoomBy(zoom = 1f, centroidX = 500f, chartAreaWidth = chartWidth, total = 1000)

        assertEquals(100f, vp.startIndex, 0f)
        assertEquals(100f, vp.visibleBars, 0f)
    }

    // ========================================================================
    // CLAMPING (§4.8)
    // ========================================================================

    @Test
    fun `clamp prevents scrolling past the newest bar`() {
        val vp = ChartViewport(startIndex = 5_000f, visibleBars = 100f)

        vp.clamp(total = 500)

        assertEquals(400f, vp.startIndex, 0.001f)
    }

    @Test
    fun `clamp prevents scrolling before the first bar`() {
        val vp = ChartViewport(startIndex = -250f, visibleBars = 100f)

        vp.clamp(total = 500)

        assertEquals(0f, vp.startIndex, 0.001f)
    }

    @Test
    fun `clamp handles a dataset smaller than the window`() {
        val vp = ChartViewport(startIndex = 0f, visibleBars = 100f)

        vp.clamp(total = 20)

        assertEquals("startIndex cannot go negative", 0f, vp.startIndex, 0.001f)
    }

    // ========================================================================
    // FLING PHYSICS (§4.9)
    // ========================================================================

    @Test
    fun `a slow lift-off does not start a fling`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        val started = vp.startFling(velocityPxPerSec = 1f, chartAreaWidth = chartWidth)

        assertFalse(started)
        assertFalse(vp.isFling)
    }

    @Test
    fun `a fast lift-off starts a fling in the drag direction`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        // Finger flicked to the right → viewport travels back through history.
        val started = vp.startFling(velocityPxPerSec = 2_000f, chartAreaWidth = chartWidth)

        assertTrue(started)
        assertTrue(vp.isFling)
        assertTrue("expected negative bar velocity", vp.velocityBarsPerSec < 0f)
    }

    @Test
    fun `fling decelerates and eventually settles`() {
        val vp = ChartViewport(startIndex = 500f, visibleBars = 100f)
        vp.startFling(velocityPxPerSec = -3_000f, chartAreaWidth = chartWidth)

        var frames = 0
        while (vp.isFling && frames < 1_000) {
            vp.advanceFling(deltaSeconds = 1f / 60f, total = 5_000)
            frames++
        }

        assertFalse("fling must terminate", vp.isFling)
        assertEquals("velocity must be zeroed on settle", 0f, vp.velocityBarsPerSec, 0f)
        // Decay to the 0.5 bars/s cutoff takes ~2.3s → ~140 frames at 60fps.
        assertTrue("fling should settle within ~3s, took $frames frames", frames < 180)
    }

    @Test
    fun `fling distance is frame-rate independent`() {
        fun travel(fps: Int): Float {
            val vp = ChartViewport(startIndex = 2_000f, visibleBars = 100f)
            vp.startFling(velocityPxPerSec = -3_000f, chartAreaWidth = chartWidth)
            val dt = 1f / fps
            var guard = 0
            while (vp.isFling && guard < 10_000) {
                vp.advanceFling(dt, total = 100_000)
                guard++
            }
            return vp.startIndex - 2_000f
        }

        val at60 = travel(60)
        val at120 = travel(120)

        // Exponential decay integrated at different step sizes converges to the
        // same distance — a 120 Hz device must not scroll twice as far.
        assertTrue(
            "60fps travelled $at60 but 120fps travelled $at120",
            abs(at60 - at120) < abs(at60) * 0.05f,
        )
    }

    @Test
    fun `fling stops dead at the right bound`() {
        val vp = ChartViewport(startIndex = 890f, visibleBars = 100f)
        vp.startFling(velocityPxPerSec = -5_000f, chartAreaWidth = chartWidth)

        var guard = 0
        while (vp.isFling && guard < 1_000) {
            vp.advanceFling(1f / 60f, total = 1_000)
            guard++
        }

        assertFalse(vp.isFling)
        assertEquals(900f, vp.startIndex, 0.001f)
    }

    @Test
    fun `advanceFling is a no-op when no fling is active`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        val running = vp.advanceFling(1f / 60f, total = 1_000)

        assertFalse(running)
        assertEquals(100f, vp.startIndex, 0f)
    }

    @Test
    fun `stopFling clears velocity`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)
        vp.startFling(velocityPxPerSec = 5_000f, chartAreaWidth = chartWidth)

        vp.stopFling()

        assertFalse(vp.isFling)
        assertEquals(0f, vp.velocityBarsPerSec, 0f)
    }

    @Test
    fun `an extreme flick is velocity-capped`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        vp.startFling(velocityPxPerSec = -10_000_000f, chartAreaWidth = chartWidth)

        assertTrue(abs(vp.velocityBarsPerSec) <= ChartViewport.MAX_FLING_BARS_PER_SEC)
    }

    // ========================================================================
    // CAMERA RESET / LIVE EDGE
    // ========================================================================

    @Test
    fun `resetToLatest pins the window to the newest bars`() {
        val vp = ChartViewport(startIndex = 0f, visibleBars = 1_000f)

        vp.resetToLatest(total = 500, barCount = 120f)

        assertEquals(120f, vp.visibleBars, 0.001f)
        assertEquals(380f, vp.startIndex, 0.001f)
        assertTrue(vp.isAtRightEdge(500))
    }

    @Test
    fun `resetToLatest cancels an in-flight fling`() {
        val vp = ChartViewport(startIndex = 200f, visibleBars = 100f)
        vp.startFling(velocityPxPerSec = -4_000f, chartAreaWidth = chartWidth)

        vp.resetToLatest(total = 500)

        assertFalse(vp.isFling)
    }

    @Test
    fun `isAtRightEdge is false when scrolled into history`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)

        assertFalse(vp.isAtRightEdge(total = 1_000))
    }

    @Test
    fun `prepended bars preserve the same visual anchor`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)
        val anchorX = 250f
        val before = vp.indexForX(anchorX, chartWidth)

        vp.shiftForPrependedBars(200)

        val after = vp.indexForX(anchorX, chartWidth)
        assertEquals(before, after - 200f, 0.001f)
        assertEquals(300f, vp.startIndex, 0.001f)
    }

    @Test
    fun `non-positive prepend count is a no-op`() {
        val vp = ChartViewport(startIndex = 123f, visibleBars = 50f)

        vp.shiftForPrependedBars(0)
        vp.shiftForPrependedBars(-5)

        assertEquals(123f, vp.startIndex, 0f)
    }

    @Test
    fun `snapshot and restore round trip viewport state`() {
        val vp = ChartViewport(startIndex = 321f, visibleBars = 77f, priceHigh = 150.0, priceLow = 120.0)

        val snapshot = vp.snapshotState()
        val restored = ChartViewport()
        restored.restoreState(snapshot, total = 10_000)

        assertEquals(vp.startIndex, restored.startIndex, 0.001f)
        assertEquals(vp.visibleBars, restored.visibleBars, 0.001f)
        assertEquals(vp.priceHigh, restored.priceHigh, 0.0)
        assertEquals(vp.priceLow, restored.priceLow, 0.0)
    }

    // ========================================================================
    // AUTO-SCALE (§4.3)
    // ========================================================================

    @Test
    fun `autoScale only considers the visible range`() {
        val data = candles(500)
        val vp = ChartViewport(startIndex = 0f, visibleBars = 10f)

        vp.autoScale(data)

        // Bars 0..10 span lows of 99.0 and highs of ~110 — nowhere near bar 499.
        assertTrue("high must exclude off-screen bars", vp.priceHigh < 150.0)
    }

    @Test
    fun `autoScale adds symmetric padding`() {
        val data = candles(100)
        val vp = ChartViewport(startIndex = 0f, visibleBars = 100f)

        vp.autoScale(data, pad = 0.10)

        val hi = 101.0 + 99      // highest high (bar 99)
        val lo = 99.0            // lowest low (bar 0)
        val padding = (hi - lo) * 0.10
        assertEquals(hi + padding, vp.priceHigh, 1e-6)
        assertEquals(lo - padding, vp.priceLow, 1e-6)
    }

    @Test
    fun `autoScale on empty data leaves bounds untouched`() {
        val vp = ChartViewport(priceHigh = 5.0, priceLow = 1.0)

        vp.autoScale(emptyList())

        assertEquals(5.0, vp.priceHigh, 0.0)
        assertEquals(1.0, vp.priceLow, 0.0)
    }

    // ========================================================================
    // NICE GRIDS (§4.3)
    // ========================================================================

    @Test
    fun `niceStep follows the 1-2-5 progression`() {
        val vp = ChartViewport(priceHigh = 100.0, priceLow = 0.0)

        val step = vp.niceStep(targetLines = 5)

        // 100 / 5 = 20 → snaps onto the 2 x 10^1 rung.
        assertEquals(20.0, step, 1e-9)
    }

    @Test
    fun `niceStep scales down for forex-magnitude ranges`() {
        val vp = ChartViewport(priceHigh = 1.1050, priceLow = 1.1000)

        val step = vp.niceStep(targetLines = 5)

        assertTrue("step $step should be sub-pip scale", step in 0.0001..0.01)
    }

    @Test
    fun `niceTimeStep snaps to round bar counts`() {
        val vp = ChartViewport(visibleBars = 120f)

        assertEquals(20, vp.niceTimeStep(targetLabels = 6))
    }

    @Test
    fun `niceTimeStep never returns zero`() {
        val vp = ChartViewport(visibleBars = 3f)

        assertTrue(vp.niceTimeStep(targetLabels = 6) >= 1)
    }

    // ========================================================================
    // PRICE FORMATTING (§4.3)
    // ========================================================================

    @Test
    fun `formatPrice adapts precision to magnitude`() {
        val vp = ChartViewport()

        assertEquals("42,000", vp.formatPrice(42_000.0))
        assertEquals("450.25", vp.formatPrice(450.25))
        assertEquals("1.1050", vp.formatPrice(1.105))
        assertEquals("0.65432", vp.formatPrice(0.65432))
    }

    // ========================================================================
    // CROSSHAIR SNAPPING (§4.7)
    // ========================================================================

    @Test
    fun `crosshair snaps to the nearest bar`() {
        val vp = ChartViewport(startIndex = 100f, visibleBars = 100f)
        // 10px per bar; 355px lands just past the centre of bar 135.
        vp.crosshairX = 355f

        assertEquals(136, vp.snappedCrosshairIndex(total = 1_000, chartAreaWidth = chartWidth))
    }

    @Test
    fun `crosshair index is clamped into the dataset`() {
        val vp = ChartViewport(startIndex = 950f, visibleBars = 100f)
        vp.crosshairX = chartWidth // far right, beyond the last bar

        val index = vp.snappedCrosshairIndex(total = 1_000, chartAreaWidth = chartWidth)

        assertEquals(999, index)
    }

    @Test
    fun `crosshair index is negative when there is no data`() {
        val vp = ChartViewport()

        assertEquals(-1, vp.snappedCrosshairIndex(total = 0, chartAreaWidth = chartWidth))
    }

    // ========================================================================
    // LAYOUT
    // ========================================================================

    @Test
    fun `chart area excludes the axis gutters`() {
        val vp = ChartViewport().apply {
            priceScaleWidth = 64f
            timeAxisHeight = 24f
        }

        assertEquals(936f, vp.chartWidth(1_000f), 0f)
        assertEquals(476f, vp.chartHeight(500f), 0f)
    }

    @Test
    fun `chart area never collapses to zero`() {
        val vp = ChartViewport().apply {
            priceScaleWidth = 64f
            timeAxisHeight = 24f
        }

        assertTrue(vp.chartWidth(10f) >= 1f)
        assertTrue(vp.chartHeight(10f) >= 1f)
    }
}
