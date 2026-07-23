package com.foxtrader.app.domain.sdk.drawing.builtin

import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.sdk.drawing.DrawingGeometry
import com.foxtrader.app.domain.sdk.drawing.DrawingLabel
import com.foxtrader.app.domain.sdk.drawing.DrawingLine
import com.foxtrader.app.domain.sdk.drawing.DrawingRect
import com.foxtrader.app.domain.sdk.drawing.DrawingTool

/**
 * Built-in drawing tools.
 *
 * Each tool is a stateless, pure-function implementation of [DrawingTool].
 * Geometry is expressed in chart coordinate space (Float index / Double price).
 */

// ============================================================================
// TREND LINE — 2-point line connecting any two price/index coordinates
// ============================================================================

class TrendLineTool : DrawingTool {
    override val id = "trend_line"
    override val displayName = "Trend Line"
    override val iconName = "show_chart"
    override val requiredPoints = 2

    override fun render(points: List<ChartPoint>): DrawingGeometry {
        if (points.size < 2) return DrawingGeometry()
        return DrawingGeometry(
            lines = listOf(
                DrawingLine(
                    x1 = points[0].index,
                    y1 = points[0].price,
                    x2 = points[1].index,
                    y2 = points[1].price,
                )
            )
        )
    }
}

// ============================================================================
// HORIZONTAL LINE — flat price level spanning the chart width
// ============================================================================

class HorizontalLineTool : DrawingTool {
    override val id = "horizontal_line"
    override val displayName = "Horizontal Line"
    override val iconName = "horizontal_rule"
    override val requiredPoints = 1

    override fun render(points: List<ChartPoint>): DrawingGeometry {
        if (points.isEmpty()) return DrawingGeometry()
        val price = points[0].price
        // Use -1f as left sentinel (chart renderer extends to actual left edge).
        return DrawingGeometry(
            lines = listOf(
                DrawingLine(x1 = -1f, y1 = price, x2 = Float.MAX_VALUE, y2 = price)
            ),
            labels = listOf(
                DrawingLabel(x = -1f, y = price, text = "%.5f".format(price))
            )
        )
    }
}

// ============================================================================
// RECTANGLE — price zone / supply-demand box
// ============================================================================

class RectangleTool : DrawingTool {
    override val id = "rectangle"
    override val displayName = "Rectangle"
    override val iconName = "crop_square"
    override val requiredPoints = 2

    override fun render(points: List<ChartPoint>): DrawingGeometry {
        if (points.size < 2) return DrawingGeometry()
        val left = minOf(points[0].index, points[1].index)
        val right = maxOf(points[0].index, points[1].index)
        val top = maxOf(points[0].price, points[1].price)
        val bottom = minOf(points[0].price, points[1].price)
        return DrawingGeometry(
            rects = listOf(DrawingRect(left = left, top = top, right = right, bottom = bottom))
        )
    }
}

// ============================================================================
// FIBONACCI RETRACEMENT — key fib levels between two price points
// ============================================================================

class FibonacciTool : DrawingTool {
    override val id = "fibonacci"
    override val displayName = "Fibonacci"
    override val iconName = "waves"
    override val requiredPoints = 2

    private val levels = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.705, 0.786, 1.0)

    override fun render(points: List<ChartPoint>): DrawingGeometry {
        if (points.size < 2) return DrawingGeometry()
        val high = maxOf(points[0].price, points[1].price)
        val low = minOf(points[0].price, points[1].price)
        val range = high - low
        val startX = minOf(points[0].index, points[1].index)
        val endX = maxOf(points[0].index, points[1].index) + 20f

        val lines = mutableListOf<DrawingLine>()
        val labels = mutableListOf<DrawingLabel>()

        for (level in levels) {
            val price = high - range * level
            lines.add(DrawingLine(x1 = startX, y1 = price, x2 = endX, y2 = price))
            labels.add(DrawingLabel(x = endX, y = price, text = "%.1f%%".format(level * 100)))
        }

        return DrawingGeometry(lines = lines, labels = labels)
    }
}
