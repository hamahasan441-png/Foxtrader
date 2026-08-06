package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlinx.coroutines.delay

private const val AUTO_HIDE_MS = 3500L

/**
 * Floating, edge-docked, auto-hiding drawing tool palette (TradingView-style).
 *
 * Replaces the old inline dropdown that pushed the chart down. This is a
 * vertical rail floated over the left edge of the chart canvas. After a few
 * seconds of inactivity it auto-collapses to a compact pencil handle so it
 * stops covering price action; tapping the handle (or selecting a tool) brings
 * it back. Selecting a tool or interacting resets the auto-hide timer.
 */
@Composable
fun DrawingPalette(
    visible: Boolean,
    activeTool: DrawingToolType?,
    onToolSelect: (DrawingToolType) -> Unit,
    onClearAll: () -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
        modifier = modifier,
    ) {
        var collapsed by remember { mutableStateOf(false) }
        // Bumped on every interaction to restart the auto-hide countdown.
        var interactionTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(interactionTick, visible) {
            if (visible) {
                collapsed = false
                delay(AUTO_HIDE_MS)
                collapsed = true
            }
        }

        val paletteCd = stringResource(R.string.chart_drawing_palette_cd)

        if (collapsed) {
            // Compact handle — tap to re-open the full rail.
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = paletteCd,
                tint = FoxAmber50,
                modifier = Modifier
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(FoxNeutral10.copy(alpha = 0.92f))
                    .clickable(role = Role.Button) {
                        collapsed = false
                        interactionTick++
                    }
                    .padding(8.dp)
                    .size(22.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .width(ChartDimens.drawingRailWidth)
                    .clip(RoundedCornerShape(14.dp))
                    .background(FoxNeutral10.copy(alpha = 0.94f))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val select: (DrawingToolType) -> Unit = { tool ->
                    onToolSelect(tool)
                    interactionTick++
                }
                ToolIcon(Icons.Default.ShowChart, "Trend line", activeTool == DrawingToolType.TREND_LINE) {
                    select(DrawingToolType.TREND_LINE)
                }
                ToolIcon(Icons.Default.Remove, "Horizontal line", activeTool == DrawingToolType.HORIZONTAL_LINE) {
                    select(DrawingToolType.HORIZONTAL_LINE)
                }
                ToolIcon(Icons.Default.Timeline, "Fibonacci retracement", activeTool == DrawingToolType.FIBONACCI_RETRACEMENT) {
                    select(DrawingToolType.FIBONACCI_RETRACEMENT)
                }
                ToolIcon(Icons.Default.ShowChart, "Ray", activeTool == DrawingToolType.RAY) {
                    select(DrawingToolType.RAY)
                }
                ToolIcon(Icons.Default.ShowChart, "Rectangle", activeTool == DrawingToolType.RECTANGLE) {
                    select(DrawingToolType.RECTANGLE)
                }
                ToolIcon(Icons.Default.Timeline, "Fibonacci extension", activeTool == DrawingToolType.FIBONACCI_EXTENSION) {
                    select(DrawingToolType.FIBONACCI_EXTENSION)
                }
                ToolIcon(Icons.Default.TrendingUp, "Long position", activeTool == DrawingToolType.LONG_POSITION) {
                    select(DrawingToolType.LONG_POSITION)
                }
                ToolIcon(Icons.Default.StackedLineChart, "Short position", activeTool == DrawingToolType.SHORT_POSITION) {
                    select(DrawingToolType.SHORT_POSITION)
                }
                ToolIcon(Icons.Default.StackedLineChart, "Measured move", activeTool == DrawingToolType.MEASURED_MOVE) {
                    select(DrawingToolType.MEASURED_MOVE)
                }
                ToolIcon(Icons.Default.FormatListBulleted, stringResource(R.string.chart_drawing_manage), isActive = false) {
                    onManage()
                    interactionTick++
                }
                ToolIcon(Icons.Default.Delete, "Delete all drawings", isActive = false) {
                    onClearAll()
                    interactionTick++
                }
                ToolIcon(Icons.Default.Close, stringResource(R.string.chart_drawing_palette_close), isActive = false) {
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (isActive) FoxAmber50 else FoxNeutral60,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(9.dp)
            .size(22.dp),
    )
}
