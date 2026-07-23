package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a chart drawing.
 *
 * Points are stored as a semicolon-delimited string of "index,price,timestamp"
 * triples. Keeps the schema simple (avoids a separate points table or Room
 * TypeConverters) since drawings have at most 2 points.
 *
 * Scoped to (symbol, timeframe) so drawings are shown on the correct chart.
 * `updatedAt` for cloud-sync conflict resolution.
 */
@Entity(tableName = "chart_drawings")
data class DrawingEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val timeframe: String,
    val type: String,       // DrawingToolType.name
    val points: String,     // semicolon-delimited "index,price,timestamp"
    val color: Long,
    val lineWidth: Float,
    val isVisible: Boolean,
    val label: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
