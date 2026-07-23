package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.DrawingEntity
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.Timeframe

// ============================================================================
// DRAWING MAPPERS — ChartDrawing (domain) <-> DrawingEntity (Room).
// Points serialized as "index,price,timestamp;index,price,timestamp".
// ============================================================================

private const val POINT_DELIM = ";"
private const val FIELD_DELIM = ","

fun DrawingEntity.toDomain(): ChartDrawing = ChartDrawing(
    id = id,
    type = runCatching { DrawingToolType.valueOf(type) }.getOrDefault(DrawingToolType.TREND_LINE),
    points = deserializePoints(points),
    color = color,
    lineWidth = lineWidth,
    isVisible = isVisible,
    label = label,
    createdAt = createdAt,
)

fun ChartDrawing.toEntity(
    symbol: String,
    timeframe: Timeframe,
    updatedAt: Long = System.currentTimeMillis(),
): DrawingEntity = DrawingEntity(
    id = id,
    symbol = symbol,
    timeframe = timeframe.name,
    type = type.name,
    points = serializePoints(points),
    color = color,
    lineWidth = lineWidth,
    isVisible = isVisible,
    label = label,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun serializePoints(points: List<ChartPoint>): String =
    points.joinToString(POINT_DELIM) { "${it.index}$FIELD_DELIM${it.price}$FIELD_DELIM${it.timestamp}" }

private fun deserializePoints(raw: String): List<ChartPoint> {
    if (raw.isBlank()) return emptyList()
    return raw.split(POINT_DELIM).mapNotNull { segment ->
        val parts = segment.split(FIELD_DELIM)
        if (parts.size < 3) return@mapNotNull null
        ChartPoint(
            index = parts[0].toFloatOrNull() ?: return@mapNotNull null,
            price = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
            timestamp = parts[2].toLongOrNull() ?: 0L,
        )
    }
}
