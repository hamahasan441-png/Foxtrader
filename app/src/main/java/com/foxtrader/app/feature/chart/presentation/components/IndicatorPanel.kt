package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Indicator toggle panel — a horizontally scrollable row of chips.
 * Tapping a chip toggles that indicator on/off on the chart.
 */
@Composable
fun IndicatorPanel(
    visible: Boolean,
    toggles: IndicatorToggles,
    onToggle: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
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
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(stringResource(R.string.chart_indicator_ema), toggles.ema) { onToggle { it.copy(ema = !it.ema) } }
            Chip(stringResource(R.string.chart_indicator_bollinger), toggles.bollinger) { onToggle { it.copy(bollinger = !it.bollinger) } }
            Chip(stringResource(R.string.chart_indicator_supertrend), toggles.superTrend) { onToggle { it.copy(superTrend = !it.superTrend) } }
            Chip(stringResource(R.string.chart_indicator_psar), toggles.parabolicSar) { onToggle { it.copy(parabolicSar = !it.parabolicSar) } }
            Chip(stringResource(R.string.chart_indicator_vwap), toggles.vwap) { onToggle { it.copy(vwap = !it.vwap) } }
            Chip("Anchored VWAP", toggles.anchoredVwap) { onToggle { it.copy(anchoredVwap = !it.anchoredVwap) } }
            Chip("RSI", toggles.rsi) { onToggle { it.copy(rsi = !it.rsi) } }
            Chip("MACD", toggles.macd) { onToggle { it.copy(macd = !it.macd) } }
            Chip(stringResource(R.string.chart_pane_volume_title), toggles.volume) { onToggle { it.copy(volume = !it.volume) } }
            Chip(stringResource(R.string.chart_indicator_ichimoku), toggles.ichimoku) { onToggle { it.copy(ichimoku = !it.ichimoku) } }
            Chip(stringResource(R.string.chart_indicator_volume_profile), toggles.volumeProfile) { onToggle { it.copy(volumeProfile = !it.volumeProfile) } }
            Chip(stringResource(R.string.chart_indicator_market_profile), toggles.marketProfile) { onToggle { it.copy(marketProfile = !it.marketProfile) } }
            Chip(stringResource(R.string.chart_indicator_support_resistance), toggles.supportResistance) { onToggle { it.copy(supportResistance = !it.supportResistance) } }
            Chip(stringResource(R.string.chart_indicator_fibonacci), toggles.fibonacci) { onToggle { it.copy(fibonacci = !it.fibonacci) } }
            Chip(stringResource(R.string.chart_indicator_confluence), toggles.confluence) { onToggle { it.copy(confluence = !it.confluence) } }
            Chip(stringResource(R.string.chart_indicator_order_blocks), toggles.orderBlocks) { onToggle { it.copy(orderBlocks = !it.orderBlocks) } }
            Chip(stringResource(R.string.chart_indicator_fvg), toggles.fairValueGaps) { onToggle { it.copy(fairValueGaps = !it.fairValueGaps) } }
            Chip(stringResource(R.string.chart_indicator_liquidity), toggles.liquidity) { onToggle { it.copy(liquidity = !it.liquidity) } }
            Chip(stringResource(R.string.chart_indicator_sessions), toggles.sessions) { onToggle { it.copy(sessions = !it.sessions) } }
            Chip(stringResource(R.string.chart_indicator_structure), toggles.structure) { onToggle { it.copy(structure = !it.structure) } }
            Chip("LIT X", toggles.litX) { onToggle { it.copy(litX = !it.litX) } }
            Chip("SMT", toggles.smt) { onToggle { it.copy(smt = !it.smt) } }
            Chip("TradePro", toggles.tradePro) { onToggle { it.copy(tradePro = !it.tradePro) } }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) FoxAmber50 else FoxNeutral60,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
