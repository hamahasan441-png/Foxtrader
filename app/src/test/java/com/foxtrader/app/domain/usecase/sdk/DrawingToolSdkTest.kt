package com.foxtrader.app.domain.usecase.sdk

import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.sdk.drawing.DrawingToolRegistry
import com.foxtrader.app.domain.sdk.drawing.builtin.FibonacciTool
import com.foxtrader.app.domain.sdk.drawing.builtin.HorizontalLineTool
import com.foxtrader.app.domain.sdk.drawing.builtin.RectangleTool
import com.foxtrader.app.domain.sdk.drawing.builtin.TrendLineTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the Drawing Tool SDK registry and all built-in drawing tools.
 *
 * Validates:
 * - Registry CRUD (register, get, unregister, contains, size).
 * - Each built-in tool renders correct geometry for given points.
 * - requiredPoints contract is respected by geometry output.
 */
class DrawingToolSdkTest {

    private lateinit var registry: DrawingToolRegistry

    @Before
    fun setup() {
        registry = DrawingToolRegistry().apply {
            register(TrendLineTool())
            register(HorizontalLineTool())
            register(RectangleTool())
            register(FibonacciTool())
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Registry CRUD
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `registry registers all built-in tools`() {
        assertEquals(4, registry.size)
    }

    @Test
    fun `registry get returns known tool`() {
        assertNotNull(registry.get("trend_line"))
        assertNotNull(registry.get("fibonacci"))
    }

    @Test
    fun `registry get returns null for unknown id`() {
        assertNull(registry.get("nonexistent_tool_xyz"))
    }

    @Test
    fun `registry unregister removes tool`() {
        registry.unregister("rectangle")
        assertFalse(registry.contains("rectangle"))
        assertEquals(3, registry.size)
    }

    @Test
    fun `registry re-register overwrites existing`() {
        val before = registry.size
        registry.register(TrendLineTool()) // same id
        assertEquals("Size unchanged on overwrite", before, registry.size)
    }

    @Test
    fun `getAll returns all registered tools`() {
        val all = registry.getAll()
        assertEquals(4, all.size)
        assertTrue(all.any { it.id == "trend_line" })
        assertTrue(all.any { it.id == "horizontal_line" })
        assertTrue(all.any { it.id == "rectangle" })
        assertTrue(all.any { it.id == "fibonacci" })
    }

    // ────────────────────────────────────────────────────────────────────────
    // TrendLineTool
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `TrendLineTool renders a single line between two points`() {
        val tool = TrendLineTool()
        assertEquals(2, tool.requiredPoints)
        val points = listOf(ChartPoint(10f, 1.1000), ChartPoint(50f, 1.1500))
        val geometry = tool.render(points)
        assertEquals(1, geometry.lines.size)
        val line = geometry.lines[0]
        assertEquals(10f, line.x1, 0.001f)
        assertEquals(1.1000, line.y1, 1e-6)
        assertEquals(50f, line.x2, 0.001f)
        assertEquals(1.1500, line.y2, 1e-6)
        assertTrue(geometry.rects.isEmpty())
    }

    @Test
    fun `TrendLineTool renders empty geometry with fewer than 2 points`() {
        val geometry = TrendLineTool().render(emptyList())
        assertTrue(geometry.lines.isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────────
    // HorizontalLineTool
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `HorizontalLineTool renders a flat line from a single point`() {
        val tool = HorizontalLineTool()
        assertEquals(1, tool.requiredPoints)
        val points = listOf(ChartPoint(30f, 1.2345))
        val geometry = tool.render(points)
        assertEquals(1, geometry.lines.size)
        assertEquals(1.2345, geometry.lines[0].y1, 1e-6)
        assertEquals(1.2345, geometry.lines[0].y2, 1e-6)
        // Label should include the price
        assertTrue("Label should contain price text", geometry.labels.isNotEmpty())
    }

    @Test
    fun `HorizontalLineTool renders empty geometry with no points`() {
        assertTrue(HorizontalLineTool().render(emptyList()).lines.isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────────
    // RectangleTool
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `RectangleTool renders a rect with correct bounds`() {
        val tool = RectangleTool()
        assertEquals(2, tool.requiredPoints)
        val points = listOf(ChartPoint(10f, 1.1000), ChartPoint(30f, 1.0800))
        val geometry = tool.render(points)
        assertEquals(1, geometry.rects.size)
        val rect = geometry.rects[0]
        assertEquals(10f, rect.left, 0.001f)
        assertEquals(30f, rect.right, 0.001f)
        assertEquals(1.1000, rect.top, 1e-6)
        assertEquals(1.0800, rect.bottom, 1e-6)
    }

    @Test
    fun `RectangleTool handles reversed point order correctly`() {
        // Right-to-left / top-to-bottom placement
        val points = listOf(ChartPoint(50f, 1.0500), ChartPoint(10f, 1.0800))
        val geometry = RectangleTool().render(points)
        val rect = geometry.rects[0]
        assertEquals("left < right", 10f, rect.left, 0.001f)
        assertEquals("right > left", 50f, rect.right, 0.001f)
        assertEquals("top > bottom", 1.0800, rect.top, 1e-6)
        assertEquals("bottom < top", 1.0500, rect.bottom, 1e-6)
    }

    // ────────────────────────────────────────────────────────────────────────
    // FibonacciTool
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `FibonacciTool renders 8 fib levels`() {
        val tool = FibonacciTool()
        assertEquals(2, tool.requiredPoints)
        val points = listOf(ChartPoint(10f, 1.0000), ChartPoint(50f, 1.1000))
        val geometry = tool.render(points)
        assertEquals("8 fib levels → 8 lines", 8, geometry.lines.size)
        assertEquals("8 fib labels", 8, geometry.labels.size)
    }

    @Test
    fun `FibonacciTool 0-percent level is at high, 100-percent is at low`() {
        val high = 1.1000
        val low = 1.0000
        val points = listOf(ChartPoint(10f, high), ChartPoint(50f, low))
        val geometry = FibonacciTool().render(points)
        // First line should be at high (0% level), last line at low (100% level)
        val yValues = geometry.lines.map { it.y1 }.sorted()
        assertEquals(low, yValues.first(), 1e-6)
        assertEquals(high, yValues.last(), 1e-6)
    }

    @Test
    fun `FibonacciTool renders empty on insufficient points`() {
        assertTrue(FibonacciTool().render(emptyList()).lines.isEmpty())
    }
}
