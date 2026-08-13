package com.foxtrader.app.feature.strategies.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.LogicOp
import com.foxtrader.app.domain.model.StrategyAction
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyCondition
import com.foxtrader.app.domain.model.StrategyConditionCatalog
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.ui.components.FoxBanner
import com.foxtrader.app.ui.components.FoxBannerTone
import com.foxtrader.app.ui.components.FoxButton
import com.foxtrader.app.ui.components.FoxChip
import com.foxtrader.app.ui.components.FoxEmptyState
import com.foxtrader.app.ui.components.FoxIconButton
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.components.FoxSegmentedControl
import com.foxtrader.app.ui.components.FoxSliderRow
import com.foxtrader.app.ui.components.FoxTextField
import com.foxtrader.app.ui.theme.FoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StrategyBuilderViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {
    val blueprints: StateFlow<List<StrategyBlueprint>> = appPreferences.strategyBlueprints.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun save(blueprint: StrategyBlueprint) = appPreferences.upsertStrategyBlueprint(blueprint)

    fun delete(id: String) = appPreferences.deleteStrategyBlueprint(id)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StrategyBuilderScreen(
    onNavigateBack: () -> Unit = {},
    onOpenLab: () -> Unit = {},
    viewModel: StrategyBuilderViewModel = hiltViewModel(),
) {
    val saved by viewModel.blueprints.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("Untitled template") }
    var combinator by remember { mutableStateOf(LogicOp.AND) }
    var conditions by remember { mutableStateOf(listOf<StrategyCondition>()) }
    var risk by remember { mutableStateOf(1.0) }
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    val draft = StrategyBlueprint(
        name = name,
        combinator = combinator,
        conditions = conditions,
        action = StrategyAction(riskPercent = risk),
    )

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Strategy builder", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                FoxBanner(
                    title = "Research template",
                    text = "This constructs logic you can test. It is not a live order and it does not promise an edge.",
                    tone = FoxBannerTone.Info,
                )
            }
            item {
                FoxTextField(value = name, onValueChange = { name = it }, label = "Template name")
            }
            item {
                FoxSectionHeader("Join conditions with")
                Spacer(Modifier.height(spacing.xs))
                FoxSegmentedControl(
                    options = listOf("AND", "OR"),
                    selectedIndex = if (combinator == LogicOp.AND) 0 else 1,
                    onSelect = { combinator = if (it == 0) LogicOp.AND else LogicOp.OR },
                )
            }
            item {
                FoxSectionHeader("IF")
                Spacer(Modifier.height(spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    StrategyConditionCatalog.defaults.forEach { candidate ->
                        val selected = conditions.any { it.label == candidate.label }
                        FoxChip(
                            label = candidate.label,
                            selected = selected,
                            onClick = {
                                conditions = if (selected) {
                                    conditions.filterNot { it.label == candidate.label }
                                } else {
                                    conditions + candidate.copy(id = java.util.UUID.randomUUID().toString())
                                }
                            },
                        )
                    }
                }
            }
            item {
                FoxPanel {
                    Text("THEN", style = FoxTheme.type.caption, color = colors.textMuted)
                    Text(draft.summary(), style = FoxTheme.type.body, color = colors.textPrimary)
                }
            }
            item {
                FoxSliderRow(
                    label = "Risk per trade",
                    value = risk.toFloat(),
                    range = 0.25f..2.5f,
                    onValueChange = { risk = it.toDouble() },
                    valueLabel = "${"%.2f".format(risk)}%",
                )
            }
            item {
                FoxButton(
                    text = if (draft.isValid) "Save template" else "Add a condition to save",
                    onClick = { if (draft.isValid) viewModel.save(draft) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FoxButton(
                    text = "Test in Backtesting Lab",
                    onClick = onOpenLab,
                    style = com.foxtrader.app.ui.components.FoxButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { FoxSectionHeader("Saved templates") }
            if (saved.isEmpty()) {
                item {
                    FoxEmptyState(
                        title = "No templates yet",
                        subtitle = "Compose IF / AND / OR conditions above. Templates stay on this device.",
                        modifier = Modifier.height(FoxTheme.spacing.huge * 3),
                    )
                }
            } else {
                items(saved, key = { it.id }) { blueprint ->
                    FoxPanel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(blueprint.name, style = FoxTheme.type.h3, color = colors.textPrimary)
                                Text(blueprint.summary(), style = FoxTheme.type.caption, color = colors.textMuted)
                            }
                            FoxIconButton(
                                icon = Icons.Outlined.Delete,
                                contentDescription = "Delete ${blueprint.name}",
                                onClick = { viewModel.delete(blueprint.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
