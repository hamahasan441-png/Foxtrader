package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.usecase.ai.MarketVolatilityRegime
import com.foxtrader.app.domain.usecase.ai.MarketValueZone
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/** Compact deterministic market-context overlay for the chart. */
@Composable
fun MarketContextPanel(
    explanation: MarketExplanation?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = explanation != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        explanation?.let { context ->
            Column(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FoxNeutral10.copy(alpha = 0.88f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chart_market_context_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FoxAmber50,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = context.directionalContext.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    ContextChip(context.valueZone.label, context.valueZone.color())
                    Spacer(Modifier.width(4.dp))
                    ContextChip(context.volatilityRegime.label.shortVolatilityLabel(), context.volatilityRegime.color())
                    Spacer(Modifier.width(4.dp))
                    ContextChip("HTF ${context.htfAlignmentScore}%", FoxNeutral60)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = context.nextObjective,
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxNeutral60,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                context.warnings.firstOrNull()?.let { warning ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠ $warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = FoxBearishText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private fun MarketValueZone.color() = when (this) {
    MarketValueZone.DISCOUNT -> FoxBullishText
    MarketValueZone.EQUILIBRIUM -> FoxAmber50
    MarketValueZone.PREMIUM -> FoxBearishText
}

private fun MarketVolatilityRegime.color() = when (this) {
    MarketVolatilityRegime.COMPRESSED -> FoxAmber50
    MarketVolatilityRegime.NORMAL -> FoxBullishText
    MarketVolatilityRegime.HIGH -> FoxBearishText
}

private fun String.shortVolatilityLabel(): String = when {
    contains("Compressed", ignoreCase = true) -> "Compressed"
    contains("High", ignoreCase = true) -> "High Vol"
    else -> "Normal Vol"
}
