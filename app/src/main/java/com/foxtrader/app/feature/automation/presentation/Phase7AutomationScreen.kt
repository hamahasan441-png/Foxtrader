package com.foxtrader.app.feature.automation.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import com.foxtrader.app.domain.model.AutomationMode
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme

/** Phase 7 operator cockpit. It configures routing policy; live execution still requires Phase 6 approval. */
@Composable
fun Phase7AutomationScreen(
    onNavigateBack: () -> Unit,
    onOpenTrading: () -> Unit,
    onOpenStudio: () -> Unit,
) {
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing
    var mode by remember { mutableStateOf(AutomationMode.REVIEW_QUEUE) }
    var requirePhase4 by remember { mutableStateOf(true) }
    var confirmedOnly by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Phase 7 · Automation", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                FoxPanel {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = colors.accent)
                    Text("Signal → review → execution", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text(
                        "Phase 7 connects research signals to an execution queue without bypassing Phase 6. Live-money orders can never auto-fire: they always require explicit review, fresh confirmation and the existing execution-safety gates.",
                        style = FoxTheme.type.body,
                        color = colors.textMuted,
                    )
                }
            }

            item { FoxSectionHeader("Automation mode") }
            item {
                FoxPanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        FilterChip(
                            selected = mode == AutomationMode.OFF,
                            onClick = { mode = AutomationMode.OFF },
                            label = { Text("Off") },
                        )
                        FilterChip(
                            selected = mode == AutomationMode.REVIEW_QUEUE,
                            onClick = { mode = AutomationMode.REVIEW_QUEUE },
                            label = { Text("Review") },
                        )
                        FilterChip(
                            selected = mode == AutomationMode.AUTO_PAPER_DEMO,
                            onClick = { mode = AutomationMode.AUTO_PAPER_DEMO },
                            label = { Text("Paper/Demo Auto") },
                        )
                    }
                    Text(
                        if (mode == AutomationMode.AUTO_PAPER_DEMO) "Automatic routing is limited to paper and broker-demo environments." else "Signals are queued for operator review before any order workflow.",
                        style = FoxTheme.type.caption,
                        color = colors.textMuted,
                    )
                }
            }

            item { FoxSectionHeader("Hard gates") }
            item {
                FoxPanel {
                    GateRow("Phase 4 actionable only", requirePhase4) { requirePhase4 = it }
                    GateRow("Confirmed bars only", confirmedOnly) { confirmedOnly = it }
                    Text("Trustworthy/live provenance and minimum-confidence checks remain mandatory in the policy engine.", style = FoxTheme.type.caption, color = colors.textMuted)
                }
            }

            item { FoxSectionHeader("Operator workflow") }
            item {
                FoxPanel {
                    Icon(Icons.Outlined.PlaylistAddCheck, contentDescription = null, tint = colors.accent)
                    Text("Review queue", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text("Accepted research candidates enter a bounded, deduplicated review workflow. Rejected candidates keep their reasons; ambiguous broker outcomes remain UNKNOWN and are reconciled instead of retried.", style = FoxTheme.type.caption, color = colors.textMuted)
                    OutlinedButton(onClick = onOpenTrading, modifier = Modifier.fillMaxWidth()) { Text("Open Phase 6 trading") }
                }
            }
            item {
                FoxPanel {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = colors.accent)
                    Text("Strategy source", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text("Tune strategies, indicators and signal visibility in Pro Studio; Phase 7 only routes signals that upstream engines already produced.", style = FoxTheme.type.caption, color = colors.textMuted)
                    OutlinedButton(onClick = onOpenStudio, modifier = Modifier.fillMaxWidth()) { Text("Open Pro Studio") }
                }
            }
        }
    }
}

@Composable
private fun GateRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = FoxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = FoxTheme.type.body, color = colors.textPrimary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
