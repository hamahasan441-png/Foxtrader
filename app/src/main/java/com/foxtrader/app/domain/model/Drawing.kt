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
    // --- Advanced (professional) tools ---
    FIBONACCI_EXTENSION,
    LONG_POSITION,
    SHORT_POSITION,
    MEASURED_MOVE,
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

    /**
     * Fibonacci extension price levels projected *along* the signed 2-point move
     * (points[0] → points[1]). Unlike a retracement (which divides the move),
     * an extension projects it forward (127.2%, 161.8%, 200%, 261.8%) to locate
     * profit targets beyond the initial swing.
     */
    val fibExtensionLevels: List<Double>
        get() = if (type == DrawingToolType.FIBONACCI_EXTENSION && points.size == 2) {
            val anchor = points[0].price
            val move = points[1].price - points[0].price
            FIB_EXTENSION_RATIOS.map { anchor + move * it }
        } else emptyList()

    /**
     * Position-tool geometry as (entry, stop, target). points[0] is the entry,
     * points[1] is the stop; the target is projected at [POSITION_RR] reward:risk
     * in the tool's direction (up for long, down for short). Null unless this is
     * a position tool with both anchors placed.
     */
    val positionLevels: Triple<Double, Double, Double>?
        get() = if (points.size == 2 &&
            (type == DrawingToolType.LONG_POSITION || type == DrawingToolType.SHORT_POSITION)
        ) {
            val entry = points[0].price
            val stop = points[1].price
            val risk = kotlin.math.abs(entry - stop)
            val target = if (type == DrawingToolType.LONG_POSITION) {
                entry + POSITION_RR * risk
            } else {
                entry - POSITION_RR * risk
            }
            Triple(entry, stop, target)
        } else {
            null
        }

    companion object {
        /** Default reward:risk projected by the long/short position tools. */
        const val POSITION_RR = 2.0

        /** Ratios projected by the Fibonacci extension tool along the move. */
        val FIB_EXTENSION_RATIOS = listOf(0.0, 0.618, 1.0, 1.272, 1.618, 2.0, 2.618)
    }
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
