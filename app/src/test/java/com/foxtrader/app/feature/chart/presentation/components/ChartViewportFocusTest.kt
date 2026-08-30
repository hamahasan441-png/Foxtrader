package com.foxtrader.app.feature.chart.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bringing an off-screen marker into view.
 *
 * The chart analyses five thousand bars and shows about a hundred, so a study
 * that fires every few hundred bars is usually drawing somewhere the trader
 * cannot see. That is the difference between a study that looks broken and one
 * that looks selective, and it is a camera problem rather than a signal one.
 */
class ChartViewportFocusTest {

    private fun viewport(startIndex: Float, visibleBars: Float) =
        ChartViewport(startIndex = startIndex, visibleBars = visibleBars)

    @Test
    fun `an index already on screen does not move the camera`() {
        val vp = viewport(startIndex = 4_900f, visibleBars = 100f)

        assertFalse("A visible marker must not move the camera.", vp.focusOnIndex(4_950, 5_000))
        assertEquals(4_900f, vp.startIndex, 0.01f)
    }

    @Test
    fun `an index behind the window is brought into view`() {
        val vp = viewport(startIndex = 4_900f, visibleBars = 100f)

        assertTrue(vp.focusOnIndex(4_600, 5_000))
        assertTrue("The marker must end up visible.", vp.isIndexVisible(4_600))
    }

    /**
     * The marker lands right of centre so the bars that produced it are on
     * screen next to what price did afterwards. A marker pinned to the very
     * edge is visible and useless.
     */
    @Test
    fun `a focused marker keeps context on both sides`() {
        val vp = viewport(startIndex = 4_900f, visibleBars = 100f)
        vp.focusOnIndex(4_600, 5_000)

        val position = (4_600f - vp.startIndex) / vp.visibleBars
        assertTrue("Marker sat at $position across the screen.", position in 0.25f..0.85f)
    }

    @Test
    fun `focusing does not change the zoom`() {
        val vp = viewport(startIndex = 4_900f, visibleBars = 100f)
        vp.focusOnIndex(120, 5_000)

        assertEquals("Focusing must pan, not zoom.", 100f, vp.visibleBars, 0.01f)
        assertTrue(vp.isIndexVisible(120))
    }

    @Test
    fun `a marker near the start of the series is still reachable`() {
        val vp = viewport(startIndex = 4_900f, visibleBars = 100f)

        assertTrue(vp.focusOnIndex(3, 5_000))
        assertTrue(vp.isIndexVisible(3))
        assertTrue("The camera must not pan before the first bar.", vp.startIndex >= 0f)
    }

    @Test
    fun `an index outside the series is refused`() {
        val vp = viewport(startIndex = 4_900f, visibleBars = 100f)

        assertFalse(vp.focusOnIndex(-1, 5_000))
        assertFalse(vp.focusOnIndex(5_000, 5_000))
        assertFalse(vp.focusOnIndex(10, 0))
        assertEquals(4_900f, vp.startIndex, 0.01f)
    }
}
