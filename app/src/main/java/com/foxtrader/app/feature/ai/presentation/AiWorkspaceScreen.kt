package com.foxtrader.app.feature.ai.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.foxtrader.app.domain.repository.AlertRepository
import com.foxtrader.app.domain.usecase.home.ClassifiedInsight
import com.foxtrader.app.domain.usecase.home.InsightKind
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.ui.components.FoxBadge
import com.foxtrader.app.ui.components.FoxBanner
import com.foxtrader.app.ui.components.FoxBannerTone
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AiWorkspaceState(
    val insights: List<ClassifiedInsight> = emptyList(),
    val alertTitles: List<String> = emptyList(),
)

@HiltViewModel
class AiWorkspaceViewModel @Inject constructor(
    alertRepository: AlertRepository,
    appPreferences: AppPreferences,
) : ViewModel() {

    val uiState = alertRepository.observeAlerts().combine(appPreferences.workspaceProfile) { alerts, profile ->
        val unread = alerts.count { !it.acknowledged }
        AiWorkspaceState(
            insights = buildList {
                add(
                    ClassifiedInsight(
                        InsightKind.FACT,
                        if (unread == 0) "No unread signal alerts." else "$unread unread signal alert${if (unread == 1) "" else "s"}.",
                    ),
                )
                add(
                    ClassifiedInsight(
                        InsightKind.OPINION,
                        "Workspace is tuned for ${profile.greetingFocus} research on ${profile.preferredTimeframe.label}.",
                    ),
                )
            },
            alertTitles = alerts.take(6).map { it.title },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiWorkspaceState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWorkspaceScreen(
    onNavigateBack: () -> Unit = {},
    onOpenChart: () -> Unit = {},
    viewModel: AiWorkspaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    Scaffold(
        containerColor = colors.background,
        topBar = {
            FoxScreenTopBar(
                title = "AI workspace",
                onNavigateBack = onNavigateBack,
            )
        },
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
                    title = "How FoxTrader speaks",
                    text = "Every statement is labelled. Uncertain outlooks are never shown as facts.",
                    tone = FoxBannerTone.Info,
                )
            }
            item {
                FoxPanel {
                    Text("Roles", style = FoxTheme.type.h3, color = colors.accent)
                    Spacer(Modifier.height(spacing.sm))
                    LegendLine("FACT", "Observed in data on this device.")
                    LegendLine("CALCULATION", "Derived from those observations.")
                    LegendLine("ASSUMPTION", "Needs your market context to hold.")
                    LegendLine("PROBABILITY", "Rank or score — not a prediction.")
                    LegendLine("OPINION", "Process guidance. Never an order.")
                }
            }
            item {
                FoxSectionHeader("Research notes", actionLabel = "Chart", onAction = onOpenChart)
            }
            items(state.insights, key = { it.text }) { insight ->
                FoxPanel {
                    FoxBadge(insight.kind.name, color = kindColor(insight.kind))
                    Spacer(Modifier.height(spacing.xxs))
                    Text(insight.text, style = FoxTheme.type.body, color = colors.textPrimary)
                }
            }
            item { FoxSectionHeader("Recent alert headlines") }
            if (state.alertTitles.isEmpty()) {
                item {
                    Text("No alerts stored yet.", style = FoxTheme.type.body, color = colors.textMuted)
                }
            } else {
                items(state.alertTitles, key = { it }) { title ->
                    FoxPanel { Text(title, style = FoxTheme.type.body, color = colors.textPrimary) }
                }
            }
        }
    }
}

@Composable
private fun LegendLine(kind: String, text: String) {
    Column(Modifier.padding(vertical = FoxTheme.spacing.xxs)) {
        FoxBadge(kind)
        Text(text, style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
    }
}

@Composable
private fun kindColor(kind: InsightKind) = when (kind) {
    InsightKind.FACT -> FoxTheme.colors.information
    InsightKind.CALCULATION -> FoxTheme.colors.accent
    InsightKind.ASSUMPTION -> FoxTheme.colors.ai
    InsightKind.PROBABILITY -> FoxTheme.colors.warning
    InsightKind.OPINION -> FoxTheme.colors.textMuted
}
