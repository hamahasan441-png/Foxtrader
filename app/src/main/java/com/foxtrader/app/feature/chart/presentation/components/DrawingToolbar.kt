package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Drawing tools toolbar — slides in from the top when activated.
 *
 * Shows available drawing tools as tappable icons.
 * Active tool is highlighted. Includes delete-all and close buttons.
 */
@Composable
fun DrawingToolbar(
    visible: Boolean,
    activeMode: DrawingMode,
    activeTool: DrawingToolType?,
    onToolSelect: (DrawingToolType) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DrawingToolButton(
                icon = Icons.Default.ShowChart,
                label = "Trend",
                isActive = activeTool == DrawingToolType.TREND_LINE,
                onClick = { onToolSelect(DrawingToolType.TREND_LINE) },
            )
            DrawingToolButton(
                icon = Icons.Default.Remove,
                label = "H-Line",
                isActive = activeTool == DrawingToolType.HORIZONTAL_LINE,
                onClick = { onToolSelect(DrawingToolType.HORIZONTAL_LINE) },
            )
            DrawingToolButton(
                icon = Icons.Default.Timeline,
                label = "Fib",
                isActive = activeTool == DrawingToolType.FIBONACCI_RETRACEMENT,
                onClick = { onToolSelect(DrawingToolType.FIBONACCI_RETRACEMENT) },
            )
            DrawingToolButton(
                icon = Icons.Default.ShowChart,
                label = "Ray",
                isActive = activeTool == DrawingToolType.RAY,
                onClick = { onToolSelect(DrawingToolType.RAY) },
            )
            DrawingToolButton(
                icon = Icons.Default.ShowChart,
                label = "Rect",
                isActive = activeTool == DrawingToolType.RECTANGLE,
                onClick = { onToolSelect(DrawingToolType.RECTANGLE) },
            )

            // Spacer
            Box(Modifier.weight(1f))

            // Delete all
            IconButton(onClick = onClearAll, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete all drawings",
                    tint = FoxNeutral60,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Close toolbar
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close drawing tools",
                    tint = FoxNeutral60,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawingToolButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (isActive) FoxAmber50 else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) FoxAmber50 else FoxNeutral60,
        )
    }
}
