package com.foxtrader.app.domain.usecase.drawing

import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DrawingEngine.
 * Validates placement flow, hit-testing, CRUD operations, and state machine transitions.
 */
class DrawingEngineTest {

    private lateinit var engine: DrawingEngine

    @Before
    fun setup() {
        engine = DrawingEngine()
    }

    // ========================================================================
    // PLACEMENT FLOW TESTS
    // ========================================================================

    @Test
    fun `initial state is NONE with no active tool`() {
        assertEquals(DrawingMode.NONE, engine.mode)
        assertNull(engine.activeTool)
    }

    @Test
    fun `startPlacing sets mode to PLACING_FIRST`() {
        engine.startPlacing(DrawingToolType.TREND_LINE)
        assertEquals(DrawingMode.PLACING_FIRST, engine.mode)
        assertEquals(DrawingToolType.TREND_LINE, engine.activeTool)
    }

    @Test
    fun `horizontal line completes on single point`() {
        engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
        val point = ChartPoint(index = 10f, price = 1.1234)
        val drawing = engine.placePoint(point)

        assertNotNull("H-line should complete on first point", drawing)
        assertEquals(DrawingToolType.HORIZONTAL_LINE, drawing!!.type)
        assertEquals(1, drawing.points.size)
        assertEquals(1.1234, drawing.points[0].price, 0.0001)
        assertEquals(DrawingMode.NONE, engine.mode) // Reset after completion
    }

    @Test
    fun `trend line requires two points`() {
        engine.startPlacing(DrawingToolType.TREND_LINE)

        val point1 = ChartPoint(index = 5f, price = 1.1000)
        val result1 = engine.placePoint(point1)
        assertNull("First point should not complete trend line", result1)
        assertEquals(DrawingMode.PLACING_SECOND, engine.mode)

        val point2 = ChartPoint(index = 20f, price = 1.1500)
        val result2 = engine.placePoint(point2)
        assertNotNull("Second point should complete trend line", result2)
        assertEquals(DrawingToolType.TREND_LINE, result2!!.type)
        assertEquals(2, result2.points.size)
        assertEquals(DrawingMode.NONE, engine.mode)
    }

    @Test
    fun `fibonacci retracement requires two points`() {
        engine.startPlacing(DrawingToolType.FIBONACCI_RETRACEMENT)

        engine.placePoint(ChartPoint(index = 0f, price = 100.0))
        assertEquals(DrawingMode.PLACING_SECOND, engine.mode)

        val drawing = engine.placePoint(ChartPoint(index = 50f, price = 150.0))
        assertNotNull(drawing)
        assertEquals(DrawingToolType.FIBONACCI_RETRACEMENT, drawing!!.type)

        // Verify fib levels are computed correctly
        val fibs = drawing.fibLevels
        assertEquals(7, fibs.size) // 0%, 23.6%, 38.2%, 50%, 61.8%, 78.6%, 100%
        assertEquals(150.0, fibs[0], 0.01) // 0% = high
        assertEquals(100.0, fibs[6], 0.01) // 100% = low
        assertEquals(125.0, fibs[3], 0.01) // 50% = midpoint
    }

    @Test
    fun `cancelPlacement resets state`() {
        engine.startPlacing(DrawingToolType.TREND_LINE)
        engine.placePoint(ChartPoint(index = 5f, price = 1.0))
        assertEquals(DrawingMode.PLACING_SECOND, engine.mode)

        engine.cancelPlacement()
        assertEquals(DrawingMode.NONE, engine.mode)
        assertNull(engine.activeTool)
    }

    @Test
    fun `placePoint with no active tool returns null`() {
        val result = engine.placePoint(ChartPoint(index = 5f, price = 1.0))
        assertNull(result)
    }

    // ========================================================================
    // STORAGE / CRUD TESTS
    // ========================================================================

    @Test
    fun `completed drawings are stored`() {
        engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
        engine.placePoint(ChartPoint(index = 10f, price = 1.5))

        val drawings = engine.getVisibleDrawings()
        assertEquals(1, drawings.size)
        assertEquals(DrawingToolType.HORIZONTAL_LINE, drawings[0].type)
    }

    @Test
    fun `multiple drawings accumulate`() {
        repeat(3) { i ->
            engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
            engine.placePoint(ChartPoint(index = i.toFloat(), price = 1.0 + i * 0.1))
        }
        assertEquals(3, engine.getVisibleDrawings().size)
    }

    @Test
    fun `deleteDrawing removes by ID`() {
        engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
        engine.placePoint(ChartPoint(index = 10f, price = 1.5))
        val drawings = engine.getAllDrawings()
        assertEquals(1, drawings.size)

        val id = drawings[0].id
        engine.deleteDrawing(id)
        assertEquals(0, engine.getAllDrawings().size)
    }

    @Test
    fun `toggleVisibility hides and shows`() {
        engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
        engine.placePoint(ChartPoint(index = 10f, price = 1.5))
        val id = engine.getAllDrawings()[0].id

        engine.toggleVisibility(id)
        assertEquals(0, engine.getVisibleDrawings().size)
        assertEquals(1, engine.getAllDrawings().size) // Still stored

        engine.toggleVisibility(id)
        assertEquals(1, engine.getVisibleDrawings().size)
    }

    @Test
    fun `clearAll removes everything`() {
        repeat(5) { i ->
            engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
            engine.placePoint(ChartPoint(index = i.toFloat(), price = 1.0))
        }
        assertEquals(5, engine.getAllDrawings().size)

        engine.clearAll()
        assertEquals(0, engine.getAllDrawings().size)
    }

    // ========================================================================
    // HIT TESTING
    // ========================================================================

    @Test
    fun `hitTest finds horizontal line at exact price`() {
        engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
        engine.placePoint(ChartPoint(index = 10f, price = 1.5000))

        val hit = engine.hitTest(ChartPoint(index = 20f, price = 1.5001), threshold = 0.001)
        assertNotNull("Should hit h-line within threshold", hit)
    }

    @Test
    fun `hitTest returns null when too far`() {
        engine.startPlacing(DrawingToolType.HORIZONTAL_LINE)
        engine.placePoint(ChartPoint(index = 10f, price = 1.5000))

        val hit = engine.hitTest(ChartPoint(index = 20f, price = 2.0000), threshold = 0.001)
        assertNull("Should not hit h-line when far away", hit)
    }

    @Test
    fun `hitTest on rectangle detects point inside`() {
        engine.startPlacing(DrawingToolType.RECTANGLE)
        engine.placePoint(ChartPoint(index = 10f, price = 100.0))
        engine.placePoint(ChartPoint(index = 20f, price = 110.0))

        val inside = engine.hitTest(ChartPoint(index = 15f, price = 105.0), threshold = 1.0)
        assertNotNull("Point inside rectangle should hit", inside)
    }

    @Test
    fun `hitTest on rectangle misses point outside`() {
        engine.startPlacing(DrawingToolType.RECTANGLE)
        engine.placePoint(ChartPoint(index = 10f, price = 100.0))
        engine.placePoint(ChartPoint(index = 20f, price = 110.0))

        val outside = engine.hitTest(ChartPoint(index = 30f, price = 120.0), threshold = 1.0)
        assertNull("Point outside rectangle should not hit", outside)
    }

    // ========================================================================
    // ADVANCED TOOLS (Sprint 7)
    // ========================================================================

    @Test
    fun `fibonacci extension requires two points and projects levels along the move`() {
        engine.startPlacing(DrawingToolType.FIBONACCI_EXTENSION)
        assertNull(engine.placePoint(ChartPoint(index = 0f, price = 100.0)))
        assertEquals(DrawingMode.PLACING_SECOND, engine.mode)

        val drawing = engine.placePoint(ChartPoint(index = 40f, price = 110.0))
        assertNotNull(drawing)
        assertEquals(DrawingToolType.FIBONACCI_EXTENSION, drawing!!.type)

        // move = +10 from an anchor of 100 → levels at anchor + move*ratio.
        val levels = drawing.fibExtensionLevels
        assertEquals(7, levels.size)
        assertEquals(100.0, levels[0], 1e-9)      // 0%
        assertEquals(110.0, levels[2], 1e-9)      // 100%
        assertEquals(112.72, levels[3], 1e-9)     // 127.2%
        assertEquals(126.18, levels[6], 1e-9)     // 261.8%
    }

    @Test
    fun `long position projects target at 2R above entry`() {
        engine.startPlacing(DrawingToolType.LONG_POSITION)
        engine.placePoint(ChartPoint(index = 5f, price = 100.0))  // entry
        val drawing = engine.placePoint(ChartPoint(index = 15f, price = 98.0)) // stop
        assertNotNull(drawing)

        val (entry, stop, target) = drawing!!.positionLevels!!
        assertEquals(100.0, entry, 1e-9)
        assertEquals(98.0, stop, 1e-9)
        assertEquals(104.0, target, 1e-9) // entry + 2 * risk(2) = 104
    }

    @Test
    fun `short position projects target at 2R below entry`() {
        engine.startPlacing(DrawingToolType.SHORT_POSITION)
        engine.placePoint(ChartPoint(index = 5f, price = 100.0))  // entry
        val drawing = engine.placePoint(ChartPoint(index = 15f, price = 102.0)) // stop
        assertNotNull(drawing)

        val (_, _, target) = drawing!!.positionLevels!!
        assertEquals(96.0, target, 1e-9) // entry - 2 * risk(2) = 96
    }

    @Test
    fun `measured move requires two points`() {
        engine.startPlacing(DrawingToolType.MEASURED_MOVE)
        assertNull(engine.placePoint(ChartPoint(index = 0f, price = 100.0)))
        val drawing = engine.placePoint(ChartPoint(index = 10f, price = 108.0))
        assertNotNull(drawing)
        assertEquals(DrawingToolType.MEASURED_MOVE, drawing!!.type)
        assertEquals(2, drawing.points.size)
    }

    @Test
    fun `hitTest detects point inside a long position box`() {
        engine.startPlacing(DrawingToolType.LONG_POSITION)
        engine.placePoint(ChartPoint(index = 10f, price = 100.0))
        engine.placePoint(ChartPoint(index = 20f, price = 98.0)) // target derived at 104

        // Inside the [98, 104] price span and [10, 20] index span.
        val inside = engine.hitTest(ChartPoint(index = 15f, price = 103.0), threshold = 1.0)
        assertNotNull("Point inside position box should hit", inside)

        val outside = engine.hitTest(ChartPoint(index = 15f, price = 120.0), threshold = 1.0)
        assertNull("Point above the target should not hit", outside)
    }
}
