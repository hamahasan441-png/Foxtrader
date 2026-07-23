package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for chart-drawing persistence.
 * Drawings are user-authored and scoped to (symbol, timeframe); they are
 * the single source of truth for what the chart renderer displays.
 */
interface DrawingRepository {

    /** Observe drawings for a specific chart reactively. */
    fun observe(symbol: String, timeframe: Timeframe): Flow<List<ChartDrawing>>

    /** Save or update a drawing. */
    suspend fun upsert(drawing: ChartDrawing, symbol: String, timeframe: Timeframe)

    /** Delete a drawing by ID. */
    suspend fun delete(id: String)

    /** Clear all drawings for a chart. */
    suspend fun clearForChart(symbol: String, timeframe: Timeframe)

    /** All drawings (for sync). */
    suspend fun getAll(): List<ChartDrawing>
}
