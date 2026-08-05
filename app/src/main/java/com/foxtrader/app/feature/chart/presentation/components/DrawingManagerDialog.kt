package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.DrawingToolType
import kotlinx.collections.immutable.ImmutableList

/**
 * List-based per-object drawing manager (TradingView "Objects tree" analogue).
 *
 * Shows every drawing currently on the chart and lets the user remove them
 * individually. Deletion routes through [onDelete] which removes the drawing
 * from the engine + Room; the reactive drawings flow then updates this list.
 */
@Composable
fun DrawingManagerDialog(
    drawings: ImmutableList<ChartDrawing>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.chart_drawing_manage_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (drawings.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chart_drawing_manage_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(items = drawings, key = { it.id }) { drawing ->
                            DrawingRow(drawing = drawing, onDelete = { onDelete(drawing.id) })
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.chart_drawing_manage_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawingRow(
    drawing: ChartDrawing,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = drawing.label ?: drawing.type.displayLabel(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (drawing.label != null) {
                Text(
                    text = drawing.type.displayLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = stringResource(R.string.chart_drawing_manage_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun DrawingToolType.displayLabel(): String = when (this) {
    DrawingToolType.TREND_LINE -> "Trend line"
    DrawingToolType.HORIZONTAL_LINE -> "Horizontal line"
    DrawingToolType.VERTICAL_LINE -> "Vertical line"
    DrawingToolType.FIBONACCI_RETRACEMENT -> "Fibonacci retracement"
    DrawingToolType.RECTANGLE -> "Rectangle"
    DrawingToolType.RAY -> "Ray"
}
