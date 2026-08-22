package com.foxtrader.app.feature.strategies.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.IndicatorStudioPreset
import com.foxtrader.app.domain.model.Phase5StudioCatalog
import com.foxtrader.app.domain.model.SignalManagerPolicy
import com.foxtrader.app.domain.model.SignalVisibility
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.ui.components.FoxBanner
import com.foxtrader.app.ui.components.FoxBannerTone
import com.foxtrader.app.ui.components.FoxButton
import com.foxtrader.app.ui.components.FoxChip
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.components.FoxSliderRow
import com.foxtrader.app.ui.theme.FoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class Phase5StudioViewModel @Inject constructor(
    private val preferences: AppPreferences,
) : ViewModel() {
    val blueprints: StateFlow<List<StrategyBlueprint>> = preferences.strategyBlueprints.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    fun installTemplate(template: StrategyBlueprint) {
        preferences.upsertStrategyBlueprint(template.copy(id = java.util.UUID.randomUUID().toString(), createdAt = System.currentTimeMillis()))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Phase5StudioScreen(
    onNavigateBack: () -> Unit,
    onOpenBuilder: () -> Unit,
    onOpenBacktest: () -> Unit,
    viewModel: Phase5StudioViewModel = hiltViewModel(),
) {
    val saved by viewModel.blueprints.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(0) }
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Pro Studio · Phase 5", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                FoxBanner(
                    title = "Non-repaint research workspace",
                    text = "Build strategy templates, configure indicator presets and control signal visibility. Signal filters never manufacture entries; they only gate confirmed engine output.",
                    tone = FoxBannerTone.Info,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    listOf("Strategy", "Indicators", "Signals").forEachIndexed { index, label ->
                        FoxChip(label = label, selected = section == index, onClick = { section = index })
                    }
                }
            }
            when (section) {
                0 -> {
                    item { FoxSectionHeader("Strategy Studio") }
                    items(Phase5StudioCatalog.strategyPresets, key = { it.id }) { template ->
                        StrategyPresetCard(template, onInstall = { viewModel.installTemplate(template) })
                    }
                    item {
                        FoxButton(text = "Open visual strategy builder", onClick = onOpenBuilder, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        FoxButton(
                            text = "Open Backtesting Lab",
                            onClick = onOpenBacktest,
                            style = com.foxtrader.app.ui.components.FoxButtonStyle.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { Text("Saved custom templates: ${saved.size}", style = FoxTheme.type.caption, color = colors.textMuted) }
                }
                1 -> {
                    item { FoxSectionHeader("Indicator Studio") }
                    items(Phase5StudioCatalog.indicatorPresets, key = { it.id }) { preset -> IndicatorPresetCard(preset) }
                }
                else -> {
                    item { FoxSectionHeader("Signal Manager") }
                    item { SignalManagerEditor() }
                }
            }
        }
    }
}

@Composable
private fun StrategyPresetCard(template: StrategyBlueprint, onInstall: () -> Unit) {
    FoxPanel {
        Text(template.name, style = FoxTheme.type.h3, color = FoxTheme.colors.textPrimary)
        Text(template.summary(), style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
        Text("${template.timeframe} · risk ${"%.2f".format(template.action.riskPercent)}%", style = FoxTheme.type.caption, color = FoxTheme.colors.accent)
        FoxButton(text = "Add to my templates", onClick = onInstall, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun IndicatorPresetCard(preset: IndicatorStudioPreset) {
    var enabled by remember(preset.id) { mutableStateOf(preset.enabled) }
    FoxPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = FoxTheme.type.h3, color = FoxTheme.colors.textPrimary)
                Text("${preset.indicatorId} · ${preset.pane}", style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Text(
            preset.parameters.entries.joinToString(" · ") { "${it.key}=${it.value}" },
            style = FoxTheme.type.caption,
            color = FoxTheme.colors.textMuted,
        )
    }
}

@Composable
private fun SignalManagerEditor() {
    var visibility by remember { mutableStateOf(SignalVisibility.LIVE_ONLY) }
    var minConfidence by remember { mutableStateOf(65f) }
    var confirmedOnly by remember { mutableStateOf(true) }
    var phase4 by remember { mutableStateOf(true) }
    val policy = SignalManagerPolicy(visibility, minConfidence.toInt(), confirmedOnly, phase4)

    FoxPanel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs)) {
            SignalVisibility.entries.forEach { option ->
                FoxChip(label = option.name.replace('_', ' '), selected = visibility == option, onClick = { visibility = option })
            }
        }
        FoxSliderRow(
            label = "Minimum confidence",
            value = minConfidence,
            range = 0f..100f,
            onValueChange = { minConfidence = it },
            valueLabel = "${policy.minConfidence}%",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Confirmed bar only", style = FoxTheme.type.body, color = FoxTheme.colors.textPrimary)
            Switch(checked = confirmedOnly, onCheckedChange = { confirmedOnly = it })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Require Phase 4 confluence", style = FoxTheme.type.body, color = FoxTheme.colors.textPrimary)
            Switch(checked = phase4, onCheckedChange = { phase4 = it })
        }
        Text("Policy preview: ${policy.visibility} · ${policy.minConfidence}% · max ${policy.maxVisibleSignals}", style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
    }
}
