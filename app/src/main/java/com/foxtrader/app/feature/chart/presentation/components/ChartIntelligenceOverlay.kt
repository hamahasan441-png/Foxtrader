package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.theme.FoxTheme

/**
 * Compact institutional HUD rendered *inside* the chart area.
 *
 * LiTX/LiT/SMS/SMT/TradePro can be healthy while waiting for a complete setup.
 * Previously that healthy "waiting" state was invisible, so an empty arrow
 * layer looked like a dead indicator. The HUD surfaces the current engine stage
 * without manufacturing a signal or changing any non-repaint semantics.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartIntelligenceOverlay(
    toggles: IndicatorToggles,
    litXAnalysis: LitXAnalysis?,
    litAnalysis: LitAnalysis?,
    smsAnalysis: SmsAnalysis?,
    smtDivergences: List<SmtDivergenceDetector.SmtDivergence>,
    tradeProAnalysis: TradeProAnalysis?,
    signals: List<ChartSignal>,
    modifier: Modifier = Modifier,
) {
    val items = buildList {
        if (toggles.litX) {
            val signal = litXAnalysis?.signal
            add(
                IntelligenceItem(
                    title = "LiTX",
                    state = signal?.let { "${it.confidence.grade.name.replace('_', '+')} ${it.confidence.score}%" }
                        ?: litXAnalysis?.stage?.name?.pretty()
                        ?: "Warming",
                    direction = signal?.direction,
                    live = signal != null,
                )
            )
        }
        if (toggles.lit) {
            val signal = litAnalysis?.signal
            add(
                IntelligenceItem(
                    title = "LiT",
                    state = signal?.let { "VALIDATED ${it.confidence}%" }
                        ?: litAnalysis?.stage?.name?.pretty()
                        ?: "Warming",
                    direction = signal?.direction,
                    live = signal != null,
                )
            )
        }
        if (toggles.sms) {
            val signal = smsAnalysis?.signal
            add(
                IntelligenceItem(
                    title = "SMS",
                    state = signal?.let { "${it.type.name} ${it.confidence}%" }
                        ?: smsAnalysis?.bias?.name?.pretty()
                        ?: "Warming",
                    direction = signal?.direction,
                    live = signal != null,
                )
            )
        }
        if (toggles.smt) {
            val latest = smtDivergences.maxByOrNull { it.confirmationIndex }
            add(
                IntelligenceItem(
                    title = "SMT",
                    state = latest?.let { "${it.peerSymbol} ${it.confidence.toInt()}%" } ?: "Scanning peers",
                    direction = latest?.direction,
                    live = latest != null,
                )
            )
        }
        if (toggles.tradePro) {
            val setup = tradeProAnalysis?.setup
            add(
                IntelligenceItem(
                    title = "TradePro",
                    state = setup?.let { "${it.stage.name.pretty()} ${it.confidence}%" }
                        ?: tradeProAnalysis?.stage?.name?.pretty()
                        ?: "Warming",
                    direction = setup?.direction,
                    live = setup?.isExecutable == true,
                )
            )
        }
    }

    if (items.isEmpty()) return

    val liveSignalCount = signals.count { it.isLive }
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items.forEach { item -> IntelligencePill(item) }
        }
        if (liveSignalCount > 0) {
            Text(
                text = "$liveSignalCount confirmed signal${if (liveSignalCount == 1) "" else "s"} on the active bar",
                style = FoxTheme.type.caption,
                color = FoxTheme.colors.textSecondary,
                modifier = Modifier
                    .background(
                        color = FoxTheme.colors.surface.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun IntelligencePill(item: IntelligenceItem) {
    val colors = FoxTheme.colors
    val accent: Color = when (item.direction) {
        Direction.BULLISH -> colors.success
        Direction.BEARISH -> colors.danger
        null -> if (item.live) colors.accent else colors.textMuted
    }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .background(colors.surface.copy(alpha = 0.88f), shape)
            .border(1.dp, accent.copy(alpha = 0.42f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.title,
            style = FoxTheme.type.caption,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = item.state,
            style = FoxTheme.type.caption,
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

private data class IntelligenceItem(
    val title: String,
    val state: String,
    val direction: Direction?,
    val live: Boolean,
)

private fun String.pretty(): String = lowercase()
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
