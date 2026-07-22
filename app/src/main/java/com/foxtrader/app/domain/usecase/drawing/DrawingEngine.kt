package com.foxtrader.app.domain.usecase.drawing

import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drawing Engine — manages chart drawings (trend lines, fibs, etc.).
 *
 * Responsibilities:
 * - Drawing creation (two-point placement flow)
 * - Drawing storage (in-memory, persisted via repository later)
 * - Hit-testing (determines if a tap is near a drawing)
 * - Drawing modification (move anchor points)
 * - Drawing deletion
 *
 * Pure domain logic — no Android/framework dependencies.
 */
@Singleton
class DrawingEngine @Inject constructor() {

    private val drawings = mutableListOf<ChartDrawing>()

    /** Current drawing interaction mode. */
    var mode: DrawingMode = DrawingMode.NONE
        private set

    /** The tool type being placed (when mode != NONE). */
    var activeTool: DrawingToolType? = null
        private set

    /** First point of a two-point drawing being placed. */
    private var pendingFirstPoint: ChartPoint? = null

    /** ID of drawing currently being edited. */
    var editingDrawingId: String? = null
        private set

    // ========================================================================
    // DRAWING CREATION
    // ========================================================================

    /**
     * Start placing a new drawing of the given type.
     * Changes mode to PLACING_FIRST.
     */
    fun startPlacing(type: DrawingToolType) {
        activeTool = type
        mode = DrawingMode.PLACING_FIRST
        pendingFirstPoint = null
        editingDrawingId = null
    }

    /**
     * Place a point at the given chart coordinates.
     * For single-point tools (h-line, v-line): creates drawing immediately.
     * For two-point tools: first call sets point 1, second call completes.
     *
     * @return The completed drawing, or null if more points are needed.
     */
    fun placePoint(point: ChartPoint): ChartDrawing? {
        val tool = activeTool ?: return null

        return when {
            // Single-point tools
            tool == DrawingToolType.HORIZONTAL_LINE || tool == DrawingToolType.VERTICAL_LINE -> {
                val drawing = createDrawing(tool, listOf(point))
                drawings.add(drawing)
                cancelPlacement()
                drawing
            }
            // Two-point tools — first point
            mode == DrawingMode.PLACING_FIRST -> {
                pendingFirstPoint = point
                mode = DrawingMode.PLACING_SECOND
                null
            }
            // Two-point tools — second point
            mode == DrawingMode.PLACING_SECOND -> {
                val first = pendingFirstPoint ?: return null
                val drawing = createDrawing(tool, listOf(first, point))
                drawings.add(drawing)
                cancelPlacement()
                drawing
            }
            else -> null
        }
    }

    /** Cancel the current placement without creating a drawing. */
    fun cancelPlacement() {
        mode = DrawingMode.NONE
        activeTool = null
        pendingFirstPoint = null
        editingDrawingId = null
    }

    // ========================================================================
    // DRAWING MANAGEMENT
    // ========================================================================

    /** Get all drawings (visible only). */
    fun getVisibleDrawings(): List<ChartDrawing> = drawings.filter { it.isVisible }

    /** Get all drawings including hidden. */
    fun getAllDrawings(): List<ChartDrawing> = drawings.toList()

    /** Delete a drawing by ID. */
    fun deleteDrawing(id: String): Boolean = drawings.removeAll { it.id == id }

    /** Toggle visibility of a drawing. */
    fun toggleVisibility(id: String) {
        val idx = drawings.indexOfFirst { it.id == id }
        if (idx >= 0) {
            drawings[idx] = drawings[idx].copy(isVisible = !drawings[idx].isVisible)
        }
    }

    /** Delete all drawings. */
    fun clearAll() {
        drawings.clear()
        cancelPlacement()
    }

    /** Get the pending first point (for preview line during placement). */
    fun getPendingFirstPoint(): ChartPoint? = pendingFirstPoint

    // ========================================================================
    // HIT TESTING
    // ========================================================================

    /**
     * Find the drawing nearest to the given point, within [threshold] price units.
     * Returns null if no drawing is close enough.
     */
    fun hitTest(
        point: ChartPoint,
        threshold: Double = 0.001,
    ): ChartDrawing? {
        return drawings
            .filter { it.isVisible }
            .minByOrNull { distanceToDrawing(it, point) }
            ?.takeIf { distanceToDrawing(it, point) <= threshold }
    }

    private fun distanceToDrawing(drawing: ChartDrawing, point: ChartPoint): Double {
        return when (drawing.type) {
            DrawingToolType.HORIZONTAL_LINE -> {
                kotlin.math.abs(point.price - drawing.points[0].price)
            }
            DrawingToolType.VERTICAL_LINE -> {
                kotlin.math.abs(point.index - drawing.points[0].index).toDouble()
            }
            DrawingToolType.TREND_LINE, DrawingToolType.RAY -> {
                if (drawing.points.size < 2) return Double.MAX_VALUE
                pointToLineDistance(drawing.points[0], drawing.points[1], point)
            }
            DrawingToolType.FIBONACCI_RETRACEMENT -> {
                if (drawing.points.size < 2) return Double.MAX_VALUE
                val high = maxOf(drawing.points[0].price, drawing.points[1].price)
                val low = minOf(drawing.points[0].price, drawing.points[1].price)
                val range = high - low
                // Distance to nearest fib level
                drawing.fibLevels.minOfOrNull { kotlin.math.abs(it - point.price) }
                    ?: Double.MAX_VALUE
            }
            DrawingToolType.RECTANGLE -> {
                if (drawing.points.size < 2) return Double.MAX_VALUE
                // Distance to rectangle edge
                val p1 = drawing.points[0]
                val p2 = drawing.points[1]
                val inX = point.index in minOf(p1.index, p2.index)..maxOf(p1.index, p2.index)
                val inY = point.price in minOf(p1.price, p2.price)..maxOf(p1.price, p2.price)
                if (inX && inY) 0.0 else Double.MAX_VALUE
            }
        }
    }

    private fun pointToLineDistance(a: ChartPoint, b: ChartPoint, p: ChartPoint): Double {
        val dx = (b.index - a.index).toDouble()
        val dy = b.price - a.price
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return kotlin.math.abs(p.price - a.price)

        val t = (((p.index - a.index).toDouble()) * dx + (p.price - a.price) * dy) / lenSq
        val clampedT = t.coerceIn(0.0, 1.0)
        val projY = a.price + clampedT * dy
        return kotlin.math.abs(p.price - projY)
    }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun createDrawing(type: DrawingToolType, points: List<ChartPoint>): ChartDrawing {
        return ChartDrawing(
            id = UUID.randomUUID().toString(),
            type = type,
            points = points,
        )
    }
}
