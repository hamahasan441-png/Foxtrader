package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.usecase.performance.PerformanceSnapshot
import com.foxtrader.app.domain.usecase.performance.PerformanceTier
import com.foxtrader.app.domain.usecase.performance.QualityLevel
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxError
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Debug-only HUD showing live render metrics (DEVELOPMENT.md §4.14).
 *
 * Renders FPS, average and p95 frame time against the active budget, the
 * dropped-frame rate, and the current adaptive quality level — the exact
 * figures the §4.15 benchmark table is measured against.
 *
 * `RULE` Debug surface only. It is gated behind `BuildConfig.DEBUG` by the
 * caller so it is stripped from release builds and never costs a release frame.
 */
@Composable
fun PerformanceOverlay(
    snapshot: PerformanceSnapshot?,
    qualityLevel: QualityLevel,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null) return

    val tierColor = when (snapshot.tier) {
        PerformanceTier.EXCELLENT, PerformanceTier.GOOD -> FoxBullishText
        PerformanceTier.ACCEPTABLE -> FoxAmber50
        PerformanceTier.DEGRADED, PerformanceTier.CRITICAL -> FoxError
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FoxNeutral10.copy(alpha = 0.88f))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics {
                contentDescription = "Render performance: ${snapshot.fps.toInt()} frames per " +
                    "second, ${snapshot.tier.name.lowercase()} tier"
            },
    ) {
        Text(
            text = "${snapshot.fps.toInt()} FPS  ·  ${snapshot.tier.name}",
            color = tierColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
        )
        MetricLine("avg", "${format1(snapshot.avgFrameTimeMs)}ms / ${snapshot.targetFps}Hz")
        MetricLine("p95", "${format1(snapshot.p95FrameTimeMs)}ms")
        MetricLine("worst", "${format1(snapshot.worstFrameTimeMs)}ms")
        MetricLine("budget", "${snapshot.budgetUsagePercent.toInt()}%")
        MetricLine("dropped", "${format1(snapshot.droppedFrameRatePercent)}%")
        MetricLine("quality", qualityLevel.name)
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Text(
        text = label.padEnd(LABEL_WIDTH) + value,
        color = FoxNeutral60,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
    )
}

private const val LABEL_WIDTH = 8

private fun format1(value: Float): String = ((value * 10f).toInt() / 10f).toString()
