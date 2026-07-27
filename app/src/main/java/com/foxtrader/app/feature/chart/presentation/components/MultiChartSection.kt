package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.chart.ChartLayout
import com.foxtrader.app.feature.chart.presentation.MultiChartPanelUiState
import com.foxtrader.app.feature.chart.presentation.MultiChartUiState
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

@Composable
fun MultiChartToolbar(
    layout: ChartLayout,
    linkedToPrimary: Boolean,
    symbolLinkEnabled: Boolean,
    timeframeLinkEnabled: Boolean,
    crosshairSyncEnabled: Boolean,
    canAddPanel: Boolean,
    onLayoutChange: (ChartLayout) -> Unit,
    onToggleLinking: () -> Unit,
    onToggleSymbolLink: () -> Unit,
    onToggleTimeframeLink: () -> Unit,
    onToggleCrosshairSync: () -> Unit,
    onAddPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Multi-chart",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        LayoutChip("1×1", layout == ChartLayout.SINGLE) { onLayoutChange(ChartLayout.SINGLE) }
        LayoutChip("1×2", layout == ChartLayout.HORIZONTAL_SPLIT) { onLayoutChange(ChartLayout.HORIZONTAL_SPLIT) }
        LayoutChip("1×3", layout == ChartLayout.THREE_TOP) { onLayoutChange(ChartLayout.THREE_TOP) }
        LayoutChip("2×2", layout == ChartLayout.GRID_2X2) { onLayoutChange(ChartLayout.GRID_2X2) }
        Spacer(Modifier.width(6.dp))
        LayoutChip(if (linkedToPrimary) "LINKED" else "UNLINKED", linkedToPrimary) { onToggleLinking() }
        LayoutChip(if (symbolLinkEnabled) "SYM-LINK" else "SYM-FREE", symbolLinkEnabled) { onToggleSymbolLink() }
        LayoutChip(if (timeframeLinkEnabled) "TF-LINK" else "TF-FREE", timeframeLinkEnabled) { onToggleTimeframeLink() }
        LayoutChip(if (crosshairSyncEnabled) "X-SYNC" else "X-OFF", crosshairSyncEnabled) { onToggleCrosshairSync() }
        if (canAddPanel) {
            LayoutChip("ADD", selected = false) { onAddPanel() }
        }
    }
}

@Composable
fun MultiChartSection(
    state: MultiChartUiState,
    availableSymbols: List<String>,
    primarySymbol: String,
    primaryTimeframe: Timeframe,
    onPanelActivate: (String) -> Unit,
    onSetPanelSymbol: (String, String) -> Unit,
    onSetPanelTimeframe: (String, Timeframe) -> Unit,
    onResetPanelToPrimary: (String) -> Unit,
    onMovePanel: (String, Int) -> Unit,
    onRemovePanel: (String) -> Unit,
    onPanelCrosshairTimestampChange: (String, Long?) -> Unit,
    onPanelViewportStateChange: (String, com.foxtrader.app.domain.usecase.chart.ChartViewportState) -> Unit,
    panelViewportState: (String) -> com.foxtrader.app.domain.usecase.chart.ChartViewportState?,
    modifier: Modifier = Modifier,
) {
    if (state.layout == ChartLayout.SINGLE) return

    var symbolEditorPanelId by remember { mutableStateOf<String?>(null) }
    var timeframeEditorPanelId by remember { mutableStateOf<String?>(null) }
    var draggedPanelId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val symbolEditorPanel = state.panels.firstOrNull { it.id == symbolEditorPanelId }
    val timeframeEditorPanel = state.panels.firstOrNull { it.id == timeframeEditorPanelId }

    if (symbolEditorPanel != null) {
        PanelSelectionDialog(
            title = "Select symbol",
            selected = symbolEditorPanel.symbol,
            options = availableSymbols.ifEmpty { listOf(primarySymbol) },
            optionLabel = { it },
            onSelect = {
                onSetPanelSymbol(symbolEditorPanel.id, it)
                symbolEditorPanelId = null
            },
            onDismiss = { symbolEditorPanelId = null },
        )
    }

    if (timeframeEditorPanel != null) {
        PanelSelectionDialog(
            title = "Select timeframe",
            selected = timeframeEditorPanel.timeframe,
            options = Timeframe.entries,
            optionLabel = { it.label },
            onSelect = {
                onSetPanelTimeframe(timeframeEditorPanel.id, it)
                timeframeEditorPanelId = null
            },
            onDismiss = { timeframeEditorPanelId = null },
        )
    }

    val startDrag: (String) -> Unit = { id ->
        draggedPanelId = id
        dragOffset = Offset.Zero
        onPanelActivate(id)
    }
    val updateDrag: (Offset) -> Unit = { delta -> dragOffset += delta }
    val cancelDrag: () -> Unit = {
        draggedPanelId = null
        dragOffset = Offset.Zero
    }
    val finishDrag: (String) -> Unit = { id ->
        val fromIndex = state.panels.indexOfFirst { it.id == id }
        if (fromIndex >= 0) {
            val horizontal = kotlin.math.abs(dragOffset.x) >= kotlin.math.abs(dragOffset.y)
            val delta = when {
                horizontal && dragOffset.x > DRAG_THRESHOLD_PX -> 1
                horizontal && dragOffset.x < -DRAG_THRESHOLD_PX -> -1
                !horizontal && dragOffset.y > DRAG_THRESHOLD_PX -> 2
                !horizontal && dragOffset.y < -DRAG_THRESHOLD_PX -> -2
                else -> 0
            }
            val targetIndex = (fromIndex + delta).coerceIn(0, state.panels.lastIndex)
            if (targetIndex != fromIndex) onMovePanel(id, targetIndex)
        }
        draggedPanelId = null
        dragOffset = Offset.Zero
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Supplemental multi-chart monitor" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.layout) {
            ChartLayout.HORIZONTAL_SPLIT, ChartLayout.VERTICAL_SPLIT -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.panels.take(2).forEach { panel ->
                        MultiChartPanelCard(
                            panel = panel,
                            linkedToPrimary = state.linkedToPrimary,
                            primarySymbol = primarySymbol,
                            primaryTimeframe = primaryTimeframe,
                            onActivate = onPanelActivate,
                            onOpenSymbolEditor = { symbolEditorPanelId = it },
                            onOpenTimeframeEditor = { timeframeEditorPanelId = it },
                            onResetToPrimary = onResetPanelToPrimary,
                            draggedPanelId = draggedPanelId,
                            dragOffset = dragOffset,
                            onDragStart = startDrag,
                            onDrag = updateDrag,
                            onDragEnd = finishDrag,
                            onDragCancel = cancelDrag,
                            onRemovePanel = onRemovePanel,
                            onCrosshairTimestampChange = onPanelCrosshairTimestampChange,
                            onViewportStateChange = onPanelViewportStateChange,
                            initialViewportState = panelViewportState(panel.id),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            ChartLayout.THREE_TOP -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(182.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.panels.take(3).forEach { panel ->
                        MultiChartPanelCard(
                            panel = panel,
                            linkedToPrimary = state.linkedToPrimary,
                            primarySymbol = primarySymbol,
                            primaryTimeframe = primaryTimeframe,
                            onActivate = onPanelActivate,
                            onOpenSymbolEditor = { symbolEditorPanelId = it },
                            onOpenTimeframeEditor = { timeframeEditorPanelId = it },
                            onResetToPrimary = onResetPanelToPrimary,
                            draggedPanelId = draggedPanelId,
                            dragOffset = dragOffset,
                            onDragStart = startDrag,
                            onDrag = updateDrag,
                            onDragEnd = finishDrag,
                            onDragCancel = cancelDrag,
                            onRemovePanel = onRemovePanel,
                            onCrosshairTimestampChange = onPanelCrosshairTimestampChange,
                            onViewportStateChange = onPanelViewportStateChange,
                            initialViewportState = panelViewportState(panel.id),
                            modifier = Modifier.width(220.dp),
                        )
                    }
                }
            }

            ChartLayout.GRID_2X2 -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.panels.chunked(2).take(2).forEach { rowPanels ->
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowPanels.forEach { panel ->
                                MultiChartPanelCard(
                                    panel = panel,
                                    linkedToPrimary = state.linkedToPrimary,
                                    primarySymbol = primarySymbol,
                                    primaryTimeframe = primaryTimeframe,
                                    draggedPanelId = draggedPanelId,
                                    dragOffset = dragOffset,
                                    onActivate = onPanelActivate,
                                    onOpenSymbolEditor = { symbolEditorPanelId = it },
                                    onOpenTimeframeEditor = { timeframeEditorPanelId = it },
                                    onResetToPrimary = onResetPanelToPrimary,
                                    onDragStart = startDrag,
                                    onDrag = updateDrag,
                                    onDragEnd = finishDrag,
                                    onDragCancel = cancelDrag,
                                    onRemovePanel = onRemovePanel,
                                    onCrosshairTimestampChange = onPanelCrosshairTimestampChange,
                                    onViewportStateChange = onPanelViewportStateChange,
                                    initialViewportState = panelViewportState(panel.id),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowPanels.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            ChartLayout.SINGLE -> Unit
        }
    }
}

@Composable
private fun MultiChartPanelCard(
    panel: MultiChartPanelUiState,
    linkedToPrimary: Boolean,
    primarySymbol: String,
    primaryTimeframe: Timeframe,
    draggedPanelId: String?,
    dragOffset: Offset,
    onActivate: (String) -> Unit,
    onOpenSymbolEditor: (String) -> Unit,
    onOpenTimeframeEditor: (String) -> Unit,
    onResetToPrimary: (String) -> Unit,
    onDragStart: (String) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (String) -> Unit,
    onDragCancel: () -> Unit,
    onRemovePanel: (String) -> Unit,
    onCrosshairTimestampChange: (String, Long?) -> Unit,
    onViewportStateChange: (String, com.foxtrader.app.domain.usecase.chart.ChartViewportState) -> Unit,
    initialViewportState: com.foxtrader.app.domain.usecase.chart.ChartViewportState?,
    modifier: Modifier = Modifier,
) {
    val matchesPrimary = panel.symbol == primarySymbol && panel.timeframe == primaryTimeframe
    val borderColor = if (panel.isActive) FoxAmber50 else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val activeDrag = draggedPanelId == panel.id

    Column(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                if (activeDrag) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    alpha = 0.92f
                }
            }
            .zIndex(if (activeDrag) 1f else 0f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onActivate(panel.id) }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (panel.isActive) "ACTIVE PANEL" else "PANEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (panel.isActive) FoxAmber50 else FoxNeutral60,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = panel.symbol,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (panel.isSyntheticData) SourceBadge("SIM")
                panel.lastPrice?.let {
                    Text(
                        text = formatMiniPrice(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelChip(
                label = panel.timeframe.label,
                selected = true,
                enabled = !linkedToPrimary,
                modifier = Modifier.weight(1f),
                onClick = { onOpenTimeframeEditor(panel.id) },
            )
            PanelChip(
                label = panel.bias.name,
                selected = false,
                enabled = false,
                modifier = Modifier.weight(1f),
                onClick = {},
            )
            DragHandleChip(
                active = activeDrag,
                onDragStart = { onDragStart(panel.id) },
                onDrag = onDrag,
                onDragEnd = { onDragEnd(panel.id) },
                onDragCancel = onDragCancel,
            )
        }

        if (!linkedToPrimary) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelChip(
                    label = "SYM ${panel.symbol}",
                    selected = panel.symbol == primarySymbol,
                    enabled = true,
                    onClick = { onOpenSymbolEditor(panel.id) },
                )
                if (!matchesPrimary) {
                    PanelChip(
                        label = "PRIMARY",
                        selected = false,
                        enabled = true,
                        onClick = { onResetToPrimary(panel.id) },
                    )
                }
                PanelChip(
                    label = "REMOVE",
                    selected = false,
                    enabled = true,
                    onClick = { onRemovePanel(panel.id) },
                )
            }
        } else if (panel.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelChip(
                    label = "REMOVE",
                    selected = false,
                    enabled = true,
                    onClick = { onRemovePanel(panel.id) },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            when {
                panel.candles.isNotEmpty() -> CandleChart(
                    candles = panel.candles,
                    timeframe = panel.timeframe,
                    seriesKey = "${panel.symbol}:${panel.timeframe.label}:${panel.id}",
                    initialViewportState = initialViewportState,
                    onViewportStateChange = { onViewportStateChange(panel.id, it) },
                    syncedCrosshairTimestamp = panel.syncedCrosshairTimestamp,
                    onCrosshairTimestampChange = { onCrosshairTimestampChange(panel.id, it) },
                    modifier = Modifier.fillMaxSize(),
                )
                panel.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = FoxAmber50,
                )
                panel.error != null -> Text(
                    text = panel.error,
                    color = FoxBearishText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
                else -> Text(
                    text = "No data",
                    color = FoxNeutral60,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DragHandleChip(
    active: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Text(
        text = if (active) "DRAGGING" else "DRAG",
        style = MaterialTheme.typography.labelSmall,
        color = if (active) FoxAmber50 else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(active) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Drag panel to reorder" },
    )
}

@Composable
private fun <T> PanelSelectionDialog(
    title: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FoxNeutral10)
                .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(options, key = { optionLabel(it) }) { option ->
                    val isSelected = option == selected
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) FoxAmber50 else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PanelChip(
        label = label,
        selected = selected,
        enabled = true,
        onClick = onClick,
    )
}

@Composable
private fun PanelChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            selected -> FoxAmber50
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> FoxNeutral60
        },
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun SourceBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.background,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(FoxAmber50)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatMiniPrice(price: Double): String =
    if (price >= 1000) String.format("%,.2f", price) else String.format("%.4f", price)

private const val DRAG_THRESHOLD_PX = 48f
