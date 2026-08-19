package com.foxtrader.app.feature.onboarding.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.FavoriteTool
import com.foxtrader.app.domain.model.RiskPreference
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.TraderExperience
import com.foxtrader.app.domain.model.TradingStyle
import com.foxtrader.app.domain.model.WorkspaceProfile
import com.foxtrader.app.ui.components.FoxButton
import com.foxtrader.app.ui.components.FoxButtonStyle
import com.foxtrader.app.ui.components.FoxChip
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme

/**
 * Short personalization after the disclaimer. Skip is always available —
 * trading features never require this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinished: (WorkspaceProfile) -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var experience by remember { mutableStateOf(TraderExperience.INTERMEDIATE) }
    var markets by remember { mutableStateOf(setOf(AssetClass.FOREX)) }
    var timeframe by remember { mutableStateOf(Timeframe.M15) }
    var style by remember { mutableStateOf(TradingStyle.INTRADAY) }
    var risk by remember { mutableStateOf(RiskPreference.BALANCED) }
    var tools by remember { mutableStateOf(setOf(FavoriteTool.CHART, FavoriteTool.SMART_MONEY)) }

    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    fun finish(completed: Boolean) {
        onFinished(
            WorkspaceProfile(
                experience = experience,
                markets = markets.ifEmpty { setOf(AssetClass.FOREX) },
                preferredTimeframe = timeframe,
                style = style,
                risk = risk,
                favoriteTools = tools.ifEmpty { setOf(FavoriteTool.CHART) },
                completed = completed,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xxl, vertical = spacing.huge),
    ) {
        Text("Set up your desk", style = FoxTheme.type.display, color = colors.textPrimary)
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Four useful questions. Skip anytime — nothing here blocks research.",
            style = FoxTheme.type.body,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(spacing.xl))

        when (step) {
            0 -> {
                FoxSectionHeader("Experience")
                Spacer(Modifier.height(spacing.sm))
                ChipFlow(TraderExperience.entries.map { it.name to (it == experience) }) { index ->
                    experience = TraderExperience.entries[index]
                }
            }
            1 -> {
                FoxSectionHeader("Markets you actually watch")
                Spacer(Modifier.height(spacing.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    listOf(
                        AssetClass.FOREX, AssetClass.CRYPTO, AssetClass.STOCKS,
                        AssetClass.INDICES, AssetClass.METALS, AssetClass.ENERGY,
                    ).forEach { asset ->
                        FoxChip(
                            label = asset.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = asset in markets,
                            onClick = {
                                markets = if (asset in markets) markets - asset else markets + asset
                            },
                        )
                    }
                }
            }
            2 -> {
                FoxSectionHeader("Preferred timeframe")
                Spacer(Modifier.height(spacing.sm))
                ChipFlow(Timeframe.entries.map { it.label to (it == timeframe) }) { index ->
                    timeframe = Timeframe.entries[index]
                }
                Spacer(Modifier.height(spacing.lg))
                FoxSectionHeader("Style")
                Spacer(Modifier.height(spacing.sm))
                ChipFlow(TradingStyle.entries.map { it.name to (it == style) }) { index ->
                    style = TradingStyle.entries[index]
                }
            }
            else -> {
                FoxSectionHeader("Risk posture")
                Spacer(Modifier.height(spacing.sm))
                ChipFlow(RiskPreference.entries.map { it.name to (it == risk) }) { index ->
                    risk = RiskPreference.entries[index]
                }
                Spacer(Modifier.height(spacing.lg))
                FoxSectionHeader("Keep nearby")
                Spacer(Modifier.height(spacing.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    FavoriteTool.entries.filterNot { it == FavoriteTool.JOURNAL }.forEach { tool ->
                        FoxChip(
                            label = tool.name.replace('_', ' '),
                            selected = tool in tools,
                            onClick = {
                                tools = if (tool in tools) tools - tool else tools + tool
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(spacing.xxl))
        FoxButton(
            text = if (step < 3) "Continue" else "Open FoxTrader",
            onClick = { if (step < 3) step++ else finish(completed = true) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(spacing.sm))
        FoxButton(
            text = "Skip for now",
            onClick = { finish(completed = true) },
            style = FoxButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs),
    ) {
        items.forEachIndexed { index, (label, selected) ->
            FoxChip(
                label = label.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                selected = selected,
                onClick = { onSelect(index) },
            )
        }
    }
}
