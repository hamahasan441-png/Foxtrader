package com.foxtrader.app.domain.model

/**
 * Drawing tool types available on the chart.
 */
enum class DrawingToolType {
    TREND_LINE,
    HORIZONTAL_LINE,
    VERTICAL_LINE,
    FIBONACCI_RETRACEMENT,
    RECTANGLE,
    RAY,
}

/**
 * A single point on the chart defined by candle index + price.
 * This allows drawings to stay anchored to price/time even when viewport moves.
 */
data class ChartPoint(
    val index: Float,     // Bar index (can be fractional for sub-bar precision)
    val price: Double,    // Price level
    val timestamp: Long = 0L,
)

/**
 * A drawing object placed on the chart by the user.
 * Immutable — modifications create new instances.
 */
data class ChartDrawing(
    val id: String,
    val type: DrawingToolType,
    val points: List<ChartPoint>,    // 1 point for h-line, 2 for trend/fib/rect
    val color: Long = 0xFFD4A84E,    // ARGB (default: FoxAmber50)
    val lineWidth: Float = 1.5f,
    val isVisible: Boolean = true,
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Fibonacci retracement levels (0%, 23.6%, 38.2%, 50%, 61.8%, 78.6%, 100%). */
    val fibLevels: List<Double>
        get() = if (type == DrawingToolType.FIBONACCI_RETRACEMENT && points.size == 2) {
            val high = maxOf(points[0].price, points[1].price)
            val low = minOf(points[0].price, points[1].price)
            val range = high - low
            listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0).map { level ->
                high - range * level
            }
        } else emptyList()
}

/**
 * State of the drawing tool interaction.
 */
enum class DrawingMode {
    NONE,           // Normal chart interaction (pan/zoom)
    PLACING_FIRST,  // Waiting for first point tap
    PLACING_SECOND, // First point placed, waiting for second
    EDITING,        // Dragging an existing drawing's anchor point
}
