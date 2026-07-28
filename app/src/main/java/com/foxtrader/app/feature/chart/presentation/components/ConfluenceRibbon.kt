package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral60

@Composable
fun ConfluenceRibbon(
    result: ConfluenceEngine.ConfluenceResult?,
    modifier: Modifier = Modifier,
) {
    if (result == null || result.analyses.isEmpty()) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics {
                contentDescription =
                    "Multi-timeframe confluence ${result.confluenceScore} percent, overall ${result.overallBias}"
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SummaryChip(
            label = "HTF ${result.confluenceScore}%",
            value = result.overallBias.name,
            bias = result.overallBias,
            strong = true,
        )
        result.analyses
            .sortedBy { it.timeframe.minutes }
            .forEach { analysis ->
                SummaryChip(
                    label = analysis.timeframe.label,
                    value = analysis.bias.name.take(4),
                    bias = analysis.bias,
                    strong = analysis.structureIntact,
                )
            }
    }
}

@Composable
private fun SummaryChip(
    label: String,
    value: String,
    bias: Bias,
    strong: Boolean,
) {
    val accent = when (bias) {
        Bias.BULLISH -> FoxBullishText
        Bias.BEARISH -> FoxBearishText
        Bias.NEUTRAL -> FoxNeutral60
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (strong) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .wrapContentWidth(),
    ) {
        Text(
            text = "$label · $value",
            color = if (strong) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
