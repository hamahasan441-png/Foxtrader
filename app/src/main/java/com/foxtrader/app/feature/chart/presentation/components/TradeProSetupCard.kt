package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Compact overlay summarising the active TRADEPRO setup and, prominently, whether it is confirmed by
 * higher-timeframe bias — the framework's "HTF defines bias, LTF provides entry" rule made visible.
 *
 * Shown only when the read is actionable or explicitly blocked:
 *  - EXECUTE / CONFIRMATION setups → direction, stage, confidence, entry & R:R, and an HTF-aligned badge.
 *  - A setup demoted by an opposing HTF ([TradeProSetup.note] carries "BLOCKED") → a CONFLICT badge so
 *    the trader understands *why* nothing is triggering, rather than seeing a silent, empty chart.
 *
 * Alignment is read from [com.foxtrader.app.domain.model.tradepro.TradeProSetup.confluences], which the
 * MTF engine tags with `HTF_ALIGNED_<tf>` when the LTF setup agrees with higher-timeframe structure.
 */
@Composable
fun TradeProSetupCard(
    analysis: TradeProAnalysis?,
    modifier: Modifier = Modifier,
) {
    val setup = analysis?.setup ?: return
    val blocked = setup.note.contains(BLOCKED_MARKER, ignoreCase = true)
    val actionable = setup.stage == SetupStage.EXECUTE || setup.stage == SetupStage.CONFIRMATION
    if (!actionable && !blocked) return

    val isLong = setup.direction == Direction.BULLISH
    val directionColor = if (isLong) FoxBullishText else FoxBearishText
    val htfConfluence = setup.confluences.firstOrNull { it.startsWith(HTF_ALIGNED_PREFIX) }
    val htfTimeframe = htfConfluence?.substringAfterLast('_').orEmpty()

    Column(
        modifier = modifier
            .widthIn(max = 196.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FoxNeutral10.copy(alpha = 0.94f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics {
                contentDescription = "TRADEPRO ${if (isLong) "long" else "short"} setup, " +
                    "${setup.confidence} percent confidence, ${htfStatusText(htfConfluence != null, blocked, htfTimeframe)}"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isLong) "LONG" else "SHORT",
                color = directionColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stageLabel(setup.stage, blocked),
                color = FoxNeutral60,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${setup.confidence}%",
                color = FoxAmber50,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        HtfBadge(
            aligned = htfConfluence != null,
            conflict = blocked && htfConfluence == null,
            timeframe = htfTimeframe,
        )

        if (setup.isExecutable) {
            Text(
                text = "Entry ${price(setup.entry)}  \u00B7  R:R ${"%.1f".format(setup.riskReward)}",
                color = FoxNeutral60,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun HtfBadge(aligned: Boolean, conflict: Boolean, timeframe: String) {
    val text = htfStatusText(aligned, conflict, timeframe)
    val color = when {
        aligned -> FoxBullishText
        conflict -> FoxBearishText
        else -> FoxNeutral60
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun htfStatusText(aligned: Boolean, conflict: Boolean, timeframe: String): String = when {
    aligned -> if (timeframe.isNotEmpty()) "HTF ALIGNED \u00B7 $timeframe" else "HTF ALIGNED"
    conflict -> "HTF CONFLICT"
    else -> "HTF NEUTRAL"
}

private fun stageLabel(stage: SetupStage, blocked: Boolean): String = when {
    blocked -> "STAND ASIDE"
    stage == SetupStage.EXECUTE -> "EXECUTE"
    stage == SetupStage.CONFIRMATION -> "CONFIRMING"
    else -> stage.name
}

private fun price(value: Double): String = if (value.isFinite()) "%.2f".format(value) else "--"

private const val HTF_ALIGNED_PREFIX = "HTF_ALIGNED"
private const val BLOCKED_MARKER = "BLOCKED"
