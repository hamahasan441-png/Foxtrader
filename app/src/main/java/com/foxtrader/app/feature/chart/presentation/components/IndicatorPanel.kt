package com.foxtrader.app.feature.chart.presentation.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.feature.chart.presentation.ProductionAnalysisSystem
import com.foxtrader.app.feature.chart.presentation.productionAnalysisSystem
import com.foxtrader.app.feature.chart.presentation.withProductionAnalysisSystem

/**
 * Production FOXTRADER analysis selector.
 *
 * The chart intentionally exposes only the four canonical trading systems plus
 * OFF. Lower-level calculations (structure, liquidity, order blocks, FVG, RSI,
 * volatility, etc.) may still run as implementation primitives inside an
 * approved system, but they are not independently selectable public products.
 *
 * This remains a floating [Popup], so opening it cannot reduce the chart's
 * usable drawing area.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndicatorPanel(
    visible: Boolean,
    toggles: IndicatorToggles,
    strategyBlueprints: List<StrategyBlueprint> = emptyList(),
    onToggle: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val selected = toggles.productionAnalysisSystem()
    val density = LocalDensity.current
    val popupOffset = with(density) { IntOffset(0, POPUP_OFFSET_DP.dp.roundToPx()) }
    var lastToggleAt by remember { mutableLongStateOf(0L) }

    val selectSystem: (ProductionAnalysisSystem?) -> Unit = { system ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt >= TOGGLE_DEBOUNCE_MS) {
            lastToggleAt = now
            onToggle { current -> current.withProductionAnalysisSystem(system) }
        }
    }

    Popup(
        alignment = Alignment.TopCenter,
        offset = popupOffset,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = true,
        ),
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = 300.dp, max = 620.dp)
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "FOXTRADER ANALYSIS",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "One primary system at a time. Confirmed signals use closed-bar logic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnalysisChip(
                        label = "OFF",
                        selected = selected == null,
                        onClick = { selectSystem(null) },
                    )
                    ProductionAnalysisSystem.entries.forEach { system ->
                        AnalysisChip(
                            label = system.label,
                            selected = selected == system,
                            onClick = { selectSystem(system) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

private const val TOGGLE_DEBOUNCE_MS = 180L
private const val POPUP_OFFSET_DP = 104
