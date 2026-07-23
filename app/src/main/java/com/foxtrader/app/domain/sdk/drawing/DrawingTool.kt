package com.foxtrader.app.domain.sdk.drawing

import com.foxtrader.app.domain.model.ChartPoint

/**
 * SDK interface for custom drawing tools.
 *
 * CONTRACT:
 * - [requiredPoints]: how many points the user must place (1 = h-line, 2 = trend/fib/rect).
 * - [render]: returns geometry primitives that the chart renderer draws. Pure function.
 * - Thread-safe, no side effects.
 */
interface DrawingTool {
    val id: String
    val displayName: String
    val iconName: String          // Material icon name for the toolbar
    val requiredPoints: Int       // 1 or 2

    /** Produce renderable geometry from the placed points. */
    fun render(points: List<ChartPoint>): DrawingGeometry
}

/** Geometry the chart renderer can draw. */
data class DrawingGeometry(
    val lines: List<DrawingLine> = emptyList(),
    val rects: List<DrawingRect> = emptyList(),
    val labels: List<DrawingLabel> = emptyList(),
)

data class DrawingLine(val x1: Float, val y1: Double, val x2: Float, val y2: Double, val color: Long = 0xFFD4A84E, val width: Float = 1.5f)
data class DrawingRect(val left: Float, val top: Double, val right: Float, val bottom: Double, val color: Long = 0x33D4A84E)
data class DrawingLabel(val x: Float, val y: Double, val text: String, val color: Long = 0xFFD4A84E)
