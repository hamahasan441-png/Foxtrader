package com.foxtrader.app.feature.release.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foxtrader.app.BuildConfig
import com.foxtrader.app.domain.model.ReadinessCheck
import com.foxtrader.app.domain.model.ReadinessSeverity
import com.foxtrader.app.domain.model.ReleaseReadinessInput
import com.foxtrader.app.domain.usecase.release.ReleaseReadinessEvaluator
import com.foxtrader.app.ui.components.FoxBadge
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme

/** Phase 8 production gate dashboard. It reports facts; it never marks unrun QA as passed. */
@Composable
fun Phase8ReleaseReadinessScreen(
    onNavigateBack: () -> Unit,
) {
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing
    val report = remember {
        ReleaseReadinessEvaluator().evaluate(
            ReleaseReadinessInput(
                productionBuild = !BuildConfig.DEBUG,
                releaseSigningConfigured = BuildConfig.FOXTRADER_RELEASE_SIGNING_READY,
                cleartextTrafficAllowed = false,
                releaseHttpLoggingEnabled = false,
                encryptedCredentialStorage = true,
                unattendedLiveExecutionPossible = false,
                backendBaseUrl = BuildConfig.FOXTRADER_BASE_URL,
                certificatePinsConfigured = BuildConfig.FOXTRADER_CERT_PINS.isNotBlank(),
                crashReportingConfigured = BuildConfig.CRASH_REPORTING_DSN.isNotBlank(),
                unitTestsPassed = null,
                lintPassed = null,
            ),
        )
    }

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Release readiness", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                FoxPanel {
                    Text("PHASE 8 · PRODUCTION GATE", style = FoxTheme.type.caption, color = colors.textMuted)
                    Text(
                        if (report.releasable) "No runtime blockers detected" else "Release blocked",
                        style = FoxTheme.type.h2,
                        color = colors.textPrimary,
                    )
                    Text(
                        "${report.passCount} pass · ${report.warningCount} warning · ${report.blockerCount} blocker",
                        style = FoxTheme.type.caption,
                        color = colors.textMuted,
                    )
                    Text(
                        "Unit tests and Android lint remain WARNING until CI/Gradle actually executes them; this screen never fabricates a pass.",
                        style = FoxTheme.type.caption,
                        color = colors.textMuted,
                    )
                }
            }
            item { FoxSectionHeader("Checks") }
            items(report.checks, key = { it.id }) { check -> ReadinessRow(check) }
            item {
                FoxPanel {
                    Text("Release command", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text(
                        "Run scripts/release_preflight.sh, then ./gradlew clean testDebugUnitTest lintRelease assembleRelease (or bundleRelease) in a networked Android build environment.",
                        style = FoxTheme.type.caption,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(check: ReadinessCheck) {
    val colors = FoxTheme.colors
    FoxPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(check.title, style = FoxTheme.type.h3, color = colors.textPrimary)
                Text(check.detail, style = FoxTheme.type.caption, color = colors.textMuted)
            }
            FoxBadge(
                text = when (check.severity) {
                    ReadinessSeverity.PASS -> "PASS"
                    ReadinessSeverity.WARNING -> "WARN"
                    ReadinessSeverity.BLOCKER -> "BLOCK"
                },
                color = when (check.severity) {
                    ReadinessSeverity.PASS -> colors.success
                    ReadinessSeverity.WARNING -> colors.warning
                    ReadinessSeverity.BLOCKER -> colors.danger
                },
            )
        }
    }
}
