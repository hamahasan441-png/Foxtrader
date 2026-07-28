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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.BuildConfig
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.ui.theme.FoxWarning
import com.foxtrader.app.feature.chart.presentation.components.CandleChart
import com.foxtrader.app.feature.chart.presentation.components.ConfluenceRibbon
import com.foxtrader.app.feature.chart.presentation.components.AiDecisionPanel
import com.foxtrader.app.feature.chart.presentation.components.DrawingToolbar
import com.foxtrader.app.feature.chart.presentation.components.IndicatorPanel
import com.foxtrader.app.feature.chart.presentation.components.MultiChartSection
import com.foxtrader.app.feature.chart.presentation.components.MultiChartToolbar
import com.foxtrader.app.domain.usecase.performance.PerformanceSnapshot
import com.foxtrader.app.feature.chart.presentation.components.MarketContextPanel
import com.foxtrader.app.feature.chart.presentation.components.PerformanceOverlay
import com.foxtrader.app.feature.chart.presentation.components.ReplayControlBar
import com.foxtrader.app.feature.calculator.presentation.PositionCalculatorSheet
import com.foxtrader.app.feature.chart.presentation.components.SymbolPickerDialog
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxError
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
 * - Debug render-performance HUD (debug builds only)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    modifier: Modifier = Modifier,
    onNavigateToAlerts: () -> Unit = {},
    viewModel: ChartViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val multiChartState by viewModel.multiChartState.collectAsStateWithLifecycle()
    val replayState by viewModel.replayState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val unreadAlerts by viewModel.unreadAlertCount.collectAsStateWithLifecycle()

    // --- Render performance instrumentation (DEVELOPMENT.md §4.14) ---
    val monitor = viewModel.performanceMonitor
    val view = LocalView.current
    var perfSnapshot by remember { mutableStateOf<PerformanceSnapshot?>(null) }

    DisposableEffect(view) {
        // Profile against the *actual* refresh rate of this display: a 120 Hz
        // panel has an 8.33 ms budget, a 60 Hz panel 16.67 ms. Using a fixed
        // target would either under-report jank or over-degrade quality.
        val refreshHz = view.display?.refreshRate?.toInt()?.coerceAtLeast(60) ?: 60
        monitor.start(refreshHz)
        if (BuildConfig.DEBUG) {
            monitor.onSnapshot = { perfSnapshot = it }
        }
        onDispose {
            monitor.onSnapshot = null
            monitor.stop()
        }
    }

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
            unreadAlerts = unreadAlerts,
            onAlertsClick = onNavigateToAlerts,
            onCalculatorClick = viewModel::openCalculator,
            onSymbolClick = viewModel::openSymbolPicker,
            onIndicatorsToggle = viewModel::toggleIndicatorPanel,
            onLiveToggle = viewModel::toggleLive,
            onDrawingToggle = viewModel::toggleDrawingToolbar,
            onReplayStart = { viewModel.startReplay() },
        )

        // --- Synthetic-data warning (not dismissible while active) ---
        SyntheticDataBanner(visible = state.isSyntheticData)

        // --- Timeframe selector row ---
        TimeframeRow(
            selected = state.timeframe,
            onSelect = viewModel::onTimeframeChange,
        )

        MultiChartToolbar(
            layout = multiChartState.layout,
            linkedToPrimary = multiChartState.linkedToPrimary,
            symbolLinkEnabled = multiChartState.symbolLinkEnabled,
            timeframeLinkEnabled = multiChartState.timeframeLinkEnabled,
            crosshairSyncEnabled = multiChartState.crosshairSyncEnabled,
            canAddPanel = multiChartState.panels.size < 4,
            onLayoutChange = viewModel::setMultiChartLayout,
            onToggleLinking = viewModel::toggleMultiChartLinking,
            onToggleSymbolLink = viewModel::toggleMultiChartSymbolLink,
            onToggleTimeframeLink = viewModel::toggleMultiChartTimeframeLink,
            onToggleCrosshairSync = viewModel::toggleMultiChartCrosshairSync,
            onAddPanel = viewModel::addMultiChartPanel,
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
            onAddSymbol = viewModel::addSymbolToWatchlist,
            onRemoveSymbol = viewModel::removeSymbolFromWatchlist,
        )

        if (state.showCalculator) {
            PositionCalculatorSheet(
                symbol = state.symbol,
                lastPrice = state.lastPrice,
                onDismiss = viewModel::closeCalculator,
            )
        }

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
                            seriesKey = "${state.symbol}:${state.timeframe.label}",
                            initialViewportState = viewModel.currentPrimaryViewportState(),
                            onViewportStateChange = viewModel::onPrimaryViewportStateChange,
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
                            marketProfile = state.marketProfile,
                            supportResistanceZones = state.supportResistanceZones,
                            autoFibLevels = state.autoFibLevels,
                            autoFibDirection = state.autoFibDirection,
                            autoFibSwingHigh = state.autoFibSwingHigh,
                            autoFibSwingLow = state.autoFibSwingLow,
                            canLoadOlder = !state.historyEndReached && !replayState.isActive,
                            isLoadingOlder = state.isLoadingOlder && !replayState.isActive,
                            onLoadOlder = viewModel::loadOlderHistory,
                            syncedCrosshairTimestamp = state.syncedCrosshairTimestamp,
                            onCrosshairTimestampChange = viewModel::onPrimaryCrosshairTimestampChange,
                            performanceMonitor = monitor,
                        )
                        state.isLoading -> CircularProgressIndicator(color = FoxAmber50)
                        state.error != null -> Text(
                            text = state.error ?: "",
                            color = FoxBearishText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Text(
                            text = stringResource(R.string.chart_no_data),
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

            // --- Deterministic market context (top-right overlay) ---
            MarketContextPanel(
                explanation = state.marketExplanation,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = 8.dp),
            )

            ConfluenceRibbon(
                result = if (state.indicators.confluence) state.confluence else null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )

            // --- Debug FPS / frame-budget HUD (debug builds only) ---
            if (BuildConfig.DEBUG) {
                PerformanceOverlay(
                    snapshot = perfSnapshot,
                    qualityLevel = monitor.qualityLevel,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp),
                )
            }

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

        MultiChartSection(
            state = multiChartState,
            availableSymbols = state.availableSymbols,
            primarySymbol = state.symbol,
            primaryTimeframe = state.timeframe,
            onPanelActivate = viewModel::setActiveMultiChartPanel,
            onSetPanelSymbol = viewModel::setMultiChartPanelSymbol,
            onSetPanelTimeframe = viewModel::setMultiChartPanelTimeframe,
            onResetPanelToPrimary = viewModel::resetMultiChartPanelToPrimary,
            onMovePanel = viewModel::moveMultiChartPanelToIndex,
            onRemovePanel = viewModel::removeMultiChartPanel,
            onPanelCrosshairTimestampChange = viewModel::onMultiChartPanelCrosshairTimestampChange,
            onPanelViewportStateChange = viewModel::onMultiChartPanelViewportStateChange,
            panelViewportState = viewModel::currentMultiChartPanelViewportState,
        )
    }
}

@Composable
private fun ChartTopBar(
    state: ChartUiState,
    connectionState: ConnectionState,
    unreadAlerts: Int,
    onAlertsClick: () -> Unit,
    onCalculatorClick: () -> Unit,
    onSymbolClick: () -> Unit,
    onIndicatorsToggle: () -> Unit,
    onLiveToggle: () -> Unit,
    onDrawingToggle: () -> Unit,
    onReplayStart: () -> Unit,
) {
    val currentSymbolDescription = stringResource(R.string.chart_current_symbol_cd, state.symbol)
    val live = connectionState == ConnectionState.CONNECTED
    val liveError = connectionState == ConnectionState.ERROR
    val liveLabel = when {
        live -> stringResource(R.string.chart_live_connected)
        liveError -> stringResource(R.string.chart_live_error)
        state.liveEnabled -> stringResource(R.string.chart_live_connecting)
        else -> stringResource(R.string.chart_live_off)
    }
    val liveStateDescription = when {
        live -> stringResource(R.string.chart_live_connected_state)
        liveError -> stringResource(R.string.chart_live_error_state)
        else -> stringResource(R.string.chart_live_disconnected_state)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.chart_brand_short),
            color = FoxAmber50,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = state.symbol,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    onClickLabel = stringResource(R.string.chart_top_symbol_picker_label),
                    role = Role.Button,
                    onClick = onSymbolClick,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .semantics { contentDescription = currentSymbolDescription },
        )
        BiasBadge(state.bias)
        Text(
            text = liveLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (live || liveError) MaterialTheme.colorScheme.background else FoxNeutral60,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        live -> FoxSuccess
                        liveError -> FoxError
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .clickable(
                    onClickLabel = if (state.liveEnabled) stringResource(R.string.chart_disconnect_live_feed) else stringResource(R.string.chart_connect_live_feed),
                    onClick = onLiveToggle,
                )
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .semantics {
                    role = Role.Switch
                    stateDescription = liveStateDescription
                },
        )

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onIndicatorsToggle) {
            Icon(Icons.Default.ShowChart, contentDescription = stringResource(R.string.chart_toggle_indicators_panel), tint = FoxNeutral60)
        }
        IconButton(onClick = onDrawingToggle) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.chart_toggle_drawing_tools), tint = FoxNeutral60)
        }
        IconButton(onClick = onReplayStart) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.chart_start_replay_mode), tint = FoxNeutral60)
        }
        IconButton(onClick = onCalculatorClick) {
            Icon(
                Icons.Default.Calculate,
                contentDescription = stringResource(R.string.chart_open_position_size_calculator),
                tint = FoxNeutral60,
            )
        }
        AlertsBellButton(unreadCount = unreadAlerts, onClick = onAlertsClick)

        state.lastPrice?.let { price ->
            val formattedPrice = formatPrice(price)
            val priceDescription = stringResource(R.string.chart_current_price_cd, formattedPrice)
            Text(
                text = formattedPrice,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics {
                    contentDescription = priceDescription
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
    val timeframeSelectorDescription = stringResource(R.string.chart_timeframe_selector_cd)
    val selectedStateDescription = stringResource(R.string.chart_tab_selected)
    val notSelectedStateDescription = stringResource(R.string.chart_tab_not_selected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = timeframeSelectorDescription },
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
                        onClickLabel = stringResource(R.string.chart_select_timeframe_cd, tf.label),
                        onClick = { onSelect(tf) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics {
                        role = Role.Tab
                        stateDescription = if (isSelected) selectedStateDescription else notSelectedStateDescription
                    },
            )
        }
    }
}

/**
 * Persistent warning shown whenever the chart is rendering generated bars.
 *
 * Deliberately NOT dismissible: the whole failure mode this guards against is a
 * trader forgetting that the provider was unreachable and reading a fabricated
 * random walk as their broker's price feed. It stays until real data arrives.
 */
/** Alerts entry point with an unread-count badge overlaid on the bell. */
@Composable
private fun AlertsBellButton(unreadCount: Int, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = if (unreadCount > 0) {
                    stringResource(R.string.chart_alerts_inbox_with_unread, unreadCount)
                } else {
                    stringResource(R.string.chart_alerts_inbox)
                },
                tint = if (unreadCount > 0) FoxAmber50 else FoxNeutral60,
            )
        }
        if (unreadCount > 0) {
            Text(
                text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                color = MaterialTheme.colorScheme.background,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .clip(CircleShape)
                    .background(FoxAmber50)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SyntheticDataBanner(visible: Boolean) {
    if (!visible) return
    val simulatedWarningDescription = stringResource(R.string.chart_simulated_warning_cd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FoxWarning.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = simulatedWarningDescription
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = FoxWarning,
            modifier = Modifier.height(16.dp),
        )
        Column {
            Text(
                text = stringResource(R.string.chart_simulated_data_title),
                color = FoxWarning,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
            Text(
                text = stringResource(R.string.chart_simulated_data_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun BiasBadge(bias: Bias) {
    val (color, label) = when (bias) {
        Bias.BULLISH -> FoxBullishText to stringResource(R.string.chart_bias_bullish)
        Bias.BEARISH -> FoxBearishText to stringResource(R.string.chart_bias_bearish)
        Bias.NEUTRAL -> FoxNeutral60 to stringResource(R.string.chart_bias_neutral)
    }
    val biasDescription = stringResource(R.string.chart_bias_cd, label)

    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = biasDescription },
    )
}

private fun formatPrice(price: Double): String =
    if (price >= 1000) String.format("%,.2f", price) else String.format("%.5f", price)
