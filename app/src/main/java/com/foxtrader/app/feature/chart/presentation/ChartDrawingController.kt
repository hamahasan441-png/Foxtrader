package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.DrawingRepository
import com.foxtrader.app.domain.usecase.drawing.DrawingEngine
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages drawing tool interactions and persists completed drawings to Room.
 *
 * This is a plain class instantiated by [ChartViewModel].
 */
internal class ChartDrawingController(
    private val drawingEngine: DrawingEngine,
    private val drawingRepository: DrawingRepository,
    private val scope: CoroutineScope,
    private val symbolAccessor: () -> String,
    private val timeframeAccessor: () -> Timeframe,
) {

    data class DrawingState(
        val drawingMode: DrawingMode,
        val activeTool: DrawingToolType?,
        val showDrawingToolbar: Boolean,
        val drawings: ImmutableList<ChartDrawing>,
    )

    fun startDrawing(type: DrawingToolType): DrawingState {
        drawingEngine.startPlacing(type)
        return DrawingState(
            drawingMode = drawingEngine.mode,
            activeTool = type,
            showDrawingToolbar = true,
            drawings = drawingEngine.getVisibleDrawings().toPersistentList(),
        )
    }

    fun placeDrawingPoint(index: Float, price: Double): DrawingState {
        val point = ChartPoint(index = index, price = price)
        val completed = drawingEngine.placePoint(point)
        val state = DrawingState(
            drawingMode = drawingEngine.mode,
            activeTool = null,
            showDrawingToolbar = true,
            drawings = drawingEngine.getVisibleDrawings().toPersistentList(),
        )
        if (completed != null) {
            scope.launch {
                drawingRepository.upsert(completed, symbolAccessor(), timeframeAccessor())
            }
        }
        return state
    }

    fun cancelDrawing(): DrawingState {
        drawingEngine.cancelPlacement()
        return DrawingState(
            drawingMode = drawingEngine.mode,
            activeTool = null,
            showDrawingToolbar = true,
            drawings = drawingEngine.getVisibleDrawings().toPersistentList(),
        )
    }

    fun clearAllDrawings() {
        drawingEngine.clearAll()
        scope.launch {
            drawingRepository.clearForChart(symbolAccessor(), timeframeAccessor())
        }
    }

    /** Delete a single drawing by id (removes it from the engine + Room). */
    fun deleteDrawing(id: String) {
        drawingEngine.deleteDrawing(id)
        scope.launch { drawingRepository.delete(id) }
        // The reactive observeDrawings() flow updates the displayed list.
    }

    fun toggleDrawingToolbar(currentlyShown: Boolean): DrawingState {
        val show = !currentlyShown
        return if (!show) {
            drawingEngine.cancelPlacement()
            DrawingState(
                drawingMode = drawingEngine.mode,
                activeTool = null,
                showDrawingToolbar = false,
                drawings = drawingEngine.getVisibleDrawings().toPersistentList(),
            )
        } else {
            DrawingState(
                drawingMode = drawingEngine.mode,
                activeTool = null,
                showDrawingToolbar = true,
                drawings = drawingEngine.getVisibleDrawings().toPersistentList(),
            )
        }
    }
}
