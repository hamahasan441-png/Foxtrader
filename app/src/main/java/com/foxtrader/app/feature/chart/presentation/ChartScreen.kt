package com.foxtrader.app.feature.chart.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.chart.presentation.components.CandleChart
import com.foxtrader.app.feature.chart.presentation.components.AiDecisionPanel
import com.foxtrader.app.feature.chart.presentation.components.DrawingToolbar
import com.foxtrader.app.feature.chart.presentation.components.IndicatorPanel
import com.foxtrader.app.feature.chart.presentation.components.ReplayControlBar
import com.foxtrader.app.feature.chart.presentation.components.SymbolPickerDialog
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxSuccess

/**
 * The Chart screen — the heart of FoxTrader.
 *
 * Integrates:
 * - Professional candlestick chart with all overlays
 * - Interactive timeframe selector
 * - Drawing tools toolbar
 * - Replay mode controls
 * - Connection state indicator
 * - Pull-to-refresh
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    modifier: Modifier = Modifier,
    viewModel: ChartViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val replayState by viewModel.replayState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 24.dp),
    ) {
        // --- Top bar with symbol, bias, price, and action buttons ---
        ChartTopBar(
            state = state,
            connectionState = connectionState,
            onSymbolClick = viewModel::openSymbolPicker,
            onIndicatorsToggle = viewModel::toggleIndicatorPanel,
            onLiveToggle = viewModel::toggleLive,
            onDrawingToggle = viewModel::toggleDrawingToolbar,
            onReplayStart = { viewModel.startReplay() },
        )

        // --- Timeframe selector row ---
        TimeframeRow(
            selected = state.timeframe,
            onSelect = viewModel::onTimeframeChange,
        )

        // --- Indicator toggle panel (slides in when active) ---
        IndicatorPanel(
            visible = state.showIndicatorPanel,
            toggles = state.indicators,
            onToggle = viewModel::updateIndicators,
        )

        // --- Drawing toolbar (slides in when active) ---
        DrawingToolbar(
            visible = state.showDrawingToolbar,
            activeMode = state.drawingMode,
            activeTool = state.activeTool,
            onToolSelect = viewModel::startDrawing,
            onClearAll = viewModel::clearAllDrawings,
            onClose = viewModel::toggleDrawingToolbar,
        )

        Spacer(Modifier.height(1.dp))

        // --- Symbol picker dialog ---
        SymbolPickerDialog(
            visible = state.showSymbolPicker,
            symbols = state.availableSymbols,
            selected = state.symbol,
            onSelect = viewModel::onSymbolChange,
            onDismiss = viewModel::closeSymbolPicker,
        )

        // --- Chart area with pull-to-refresh ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.hasData -> CandleChart(
                            candles = if (replayState.isActive) replayState.visibleCandles else state.candles,
                            modifier = Modifier.fillMaxSize(),
                            structureBreaks = state.structureBreaks,
                            timeframe = state.timeframe,
                            emaShort = state.emaShort,
                            emaLong = state.emaLong,
                            bollingerUpper = state.bollingerUpper,
                            bollingerMiddle = state.bollingerMiddle,
                            bollingerLower = state.bollingerLower,
                            superTrendValues = state.superTrendValues,
                            superTrendDir = state.superTrendDir,
                            parabolicSar = state.parabolicSar,
                            vwap = state.vwap,
                            ichimokuTenkan = state.ichimokuTenkan,
                            ichimokuKijun = state.ichimokuKijun,
                            ichimokuSenkouA = state.ichimokuSenkouA,
                            ichimokuSenkouB = state.ichimokuSenkouB,
                            ichimokuChikou = state.ichimokuChikou,
                            orderBlocks = state.orderBlocks,
                            fairValueGaps = state.fairValueGaps,
                            liquidityPools = state.liquidityPools,
                            sessions = state.sessions,
                            drawings = state.drawings,
                            volumeProfile = state.volumeProfile,
                        )
                        state.isLoading -> CircularProgressIndicator(color = FoxAmber50)
                        state.error != null -> Text(
                            text = state.error ?: "",
                            color = FoxBearishText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Text(
                            text = "No data",
                            color = FoxNeutral60,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // --- AI Decision badge (top-left overlay) ---
            AiDecisionPanel(
                decision = state.aiDecision,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp),
            )

            // --- Replay control bar (bottom overlay) ---
            ReplayControlBar(
                state = replayState,
                onPlayPause = viewModel::toggleReplayPlayPause,
                onStepForward = viewModel::replayStepForward,
                onStepBackward = viewModel::replayStepBackward,
                onCycleSpeed = viewModel::replayCycleSpeed,
                onClose = viewModel::stopReplay,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ChartTopBar(
    state: ChartUiState,
    connectionState: ConnectionState,
    onSymbolClick: () -> Unit,
    onIndicatorsToggle: () -> Unit,
    onLiveToggle: () -> Unit,
    onDrawingToggle: () -> Unit,
    onReplayStart: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Fox", color = FoxAmber50, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        // Tappable symbol badge → opens the symbol picker.
        Text(
            text = state.symbol,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    onClickLabel = "Change symbol",
                    role = Role.Button,
                    onClick = onSymbolClick,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        BiasBadge(state.bias)

        // LIVE toggle — green when connected, tap to connect/disconnect.
        val live = connectionState == ConnectionState.CONNECTED
        val liveLabel = if (live) "Disconnect live feed" else "Connect live feed"
        Text(
            text = liveLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (live) MaterialTheme.colorScheme.background else FoxNeutral60,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (live) FoxSuccess else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(
                    onClickLabel = liveLabel,
                    role = Role.Switch,
                    onClick = onLiveToggle,
                )
                .semantics { contentDescription = liveLabel }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )

        Spacer(Modifier.weight(1f))

        // Indicators toggle
        IconButton(onClick = onIndicatorsToggle) {
            Icon(Icons.Default.ShowChart, contentDescription = "Toggle indicators panel", tint = FoxNeutral60)
        }
        // Drawing tools toggle
        IconButton(onClick = onDrawingToggle) {
            Icon(Icons.Default.Edit, contentDescription = "Toggle drawing tools", tint = FoxNeutral60)
        }
        // Replay button
        IconButton(onClick = onReplayStart) {
            Icon(Icons.Default.Refresh, contentDescription = "Start replay mode", tint = FoxNeutral60)
        }

        // Price
        state.lastPrice?.let { price ->
            Text(
                text = formatPrice(price),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics {
                    contentDescription = "Current price: ${formatPrice(price)}"
                },
            )
        }
    }
}

@Composable
private fun TimeframeRow(
    selected: Timeframe,
    onSelect: (Timeframe) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Timeframe selector" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Timeframe.entries.forEach { tf ->
            val isSelected = tf == selected
            Text(
                text = tf.label,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(
                        onClickLabel = "Select ${tf.label} timeframe",
                        role = Role.Tab,
                        onClick = { onSelect(tf) },
                    )
                    .semantics {
                        contentDescription = "${tf.label} timeframe${if (isSelected) ", selected" else ""}"
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                )
        }
    }
}

@Composable
private fun BiasBadge(bias: Bias) {
    val (color, label) = when (bias) {
        Bias.BULLISH -> FoxBullishText to "BULLISH"
        Bias.BEARISH -> FoxBearishText to "BEARISH"
        Bias.NEUTRAL -> FoxNeutral60 to "NEUTRAL"
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "Market bias: $label" },
    )
}

private fun formatPrice(price: Double): String =
    if (price >= 1000) String.format("%,.2f", price) else String.format("%.5f", price)
