package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.feature.chart.presentation.LiveSignalPerformanceStats
import com.foxtrader.app.ui.theme.FoxTheme
import java.util.Locale

/** Compact, non-obstructive summary for forward-observed live signals only. */
@Composable
fun LiveSignalPerformanceOverlay(
    stats: LiveSignalPerformanceStats,
    modifier: Modifier = Modifier,
) {
    if (stats.totalObserved <= 0) return
    val colors = FoxTheme.colors
    val shape = RoundedCornerShape(9.dp)
    val rateText = stats.winRatePercent?.let {
        String.format(Locale.US, "%.1f%%", it)
    } ?: "--"

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceElevated.copy(alpha = 0.90f))
            .border(1.dp, colors.borderStrong.copy(alpha = 0.78f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = "LIVE SIGNALS · $rateText",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Text(
            text = "${stats.wins}W ${stats.losses}L · ${stats.decided} decided / ${stats.totalObserved} observed",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        if (stats.unresolved > 0 || stats.ties > 0 || stats.notEvaluable > 0) {
            val pieces = buildList {
                if (stats.unresolved > 0) add("${stats.unresolved} open")
                if (stats.ties > 0) add("${stats.ties} tie")
                if (stats.notEvaluable > 0) add("${stats.notEvaluable} N/A")
            }
            Text(
                text = pieces.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
