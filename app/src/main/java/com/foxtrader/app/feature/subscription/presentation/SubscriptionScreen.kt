package com.foxtrader.app.feature.subscription.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.SubscriptionPlan
import com.foxtrader.app.domain.model.SubscriptionState
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.ui.components.FoxBadge
import com.foxtrader.app.ui.components.FoxBanner
import com.foxtrader.app.ui.components.FoxBannerTone
import com.foxtrader.app.ui.components.FoxButton
import com.foxtrader.app.ui.components.FoxButtonStyle
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.theme.FoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PlanFeature(
    val name: String,
    val free: Boolean,
    val pro: Boolean,
)

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<SubscriptionState> = appPreferences.subscription.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SubscriptionState(),
    )

    fun startTrial() = appPreferences.startProTrial()

    fun restoreFree() = appPreferences.setSubscription(SubscriptionState(plan = SubscriptionPlan.FREE))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SubscriptionViewModel = hiltViewModel(),
) {
    val subscription by viewModel.state.collectAsStateWithLifecycle()
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing
    val features = listOf(
        PlanFeature("Chart, structure, drawings", true, true),
        PlanFeature("Scanner and alerts", true, true),
        PlanFeature("Backtesting lab", true, true),
        PlanFeature("Cloud sync backup", false, true),
        PlanFeature("Strategy optimizer", false, true),
        PlanFeature("Monte Carlo lab", false, true),
        PlanFeature("Multi-provider AI narration", false, true),
    )

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "FoxTrader Pro", onNavigateBack = onNavigateBack) },
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
                    title = "No store billing in this build",
                    text = "The trial is a local product flag so the upgrade path can exist without locking working tools.",
                    tone = FoxBannerTone.Info,
                )
            }
            item {
                FoxPanel {
                    Text("Current plan", style = FoxTheme.type.caption, color = colors.textMuted)
                    Text(subscription.label(), style = FoxTheme.type.h1, color = colors.textPrimary)
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        "Free already includes the professional chart, scanner, alerts and lab. Pro marks research surfaces that are expensive to run at scale.",
                        style = FoxTheme.type.body,
                        color = colors.textSecondary,
                    )
                }
            }
            item {
                FoxPanel {
                    Text("Comparison", style = FoxTheme.type.h3, color = colors.accent)
                    Spacer(Modifier.height(spacing.sm))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Feature", style = FoxTheme.type.caption, color = colors.textMuted, modifier = Modifier.weight(1f))
                        Text("Free", style = FoxTheme.type.caption, color = colors.textMuted)
                        Text("Pro", style = FoxTheme.type.caption, color = colors.textMuted, modifier = Modifier.padding(start = spacing.lg))
                    }
                    features.forEach { feature ->
                        Spacer(Modifier.height(spacing.sm))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(feature.name, style = FoxTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            Text(if (feature.free) "Yes" else "—", style = FoxTheme.type.label, color = if (feature.free) colors.success else colors.textMuted)
                            Text(
                                if (feature.pro) "Yes" else "—",
                                style = FoxTheme.type.label,
                                color = if (feature.pro) colors.accent else colors.textMuted,
                                modifier = Modifier.padding(start = spacing.lg),
                            )
                        }
                    }
                }
            }
            item {
                if (subscription.isPro()) {
                    FoxBadge("TRIAL / PRO ACTIVE", color = colors.ai)
                    Spacer(Modifier.height(spacing.sm))
                    FoxButton(
                        text = "Return to Free",
                        onClick = viewModel::restoreFree,
                        style = FoxButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    FoxButton(
                        text = "Start 14-day trial",
                        onClick = viewModel::startTrial,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
