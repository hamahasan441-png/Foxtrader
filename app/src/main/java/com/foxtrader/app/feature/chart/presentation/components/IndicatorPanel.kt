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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            Chip("EMA", toggles.ema) { onToggle { it.copy(ema = !it.ema) } }
            Chip("BOLL", toggles.bollinger) { onToggle { it.copy(bollinger = !it.bollinger) } }
            Chip("SuperTrend", toggles.superTrend) { onToggle { it.copy(superTrend = !it.superTrend) } }
            Chip("PSAR", toggles.parabolicSar) { onToggle { it.copy(parabolicSar = !it.parabolicSar) } }
            Chip("VWAP", toggles.vwap) { onToggle { it.copy(vwap = !it.vwap) } }
            Chip("Ichimoku", toggles.ichimoku) { onToggle { it.copy(ichimoku = !it.ichimoku) } }
            Chip("Vol Profile", toggles.volumeProfile) { onToggle { it.copy(volumeProfile = !it.volumeProfile) } }
            Chip("Order Blocks", toggles.orderBlocks) { onToggle { it.copy(orderBlocks = !it.orderBlocks) } }
            Chip("FVG", toggles.fairValueGaps) { onToggle { it.copy(fairValueGaps = !it.fairValueGaps) } }
            Chip("Liquidity", toggles.liquidity) { onToggle { it.copy(liquidity = !it.liquidity) } }
            Chip("Sessions", toggles.sessions) { onToggle { it.copy(sessions = !it.sessions) } }
            Chip("Structure", toggles.structure) { onToggle { it.copy(structure = !it.structure) } }
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
