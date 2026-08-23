package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Computes the external portion of a strategy package from real peer/HTF data.
 *
 * This class intentionally does not fetch data. The caller supplies provider-
 * consistent series through [StrategyMarketContext], then this analyzer applies
 * one causal closed-bar boundary and reuses the canonical SMT and market-
 * structure engines already present in the domain layer.
 */
class StrategyExternalContextAnalyzer @Inject constructor(
    private val smtDetector: SmtDivergenceDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
) {
    data class Evidence(
        val source: String,
        val direction: Direction?,
        val score: Int,
        val detail: String,
    )

    data class Analysis(
        val context: StrategyMarketContext,
        val smtDivergences: List<SmtDivergenceDetector.SmtDivergence>,
        val higherTimeframeBiases: Map<Timeframe, Bias>,
        val evidence: List<Evidence>,
    )

    fun analyze(
        primarySymbol: String,
        primaryTimeframe: Timeframe,
        primaryCandles: List<Candle>,
        context: StrategyMarketContext = StrategyMarketContext.EMPTY,
    ): Analysis {
        if (primaryCandles.isEmpty()) {
            return Analysis(
                context = context.copy(peerCandles = emptyMap(), higherTimeframeCandles = emptyMap()),
                smtDivergences = emptyList(),
                higherTimeframeBiases = emptyMap(),
                evidence = providerEvidence(context),
            )
        }

        val decisionBarOpen = primaryCandles.last().timestamp
        val causal = context.causalAt(
            decisionBarOpenTimestamp = decisionBarOpen,
            primaryTimeframe = primaryTimeframe,
        )
        val peers = causal.peerCandles.filterKeys { !it.equals(primarySymbol, ignoreCase = true) }
        val smt = if (peers.isEmpty()) {
            emptyList()
        } else {
            smtDetector.detect(
                primarySymbol = primarySymbol,
                primaryCandles = primaryCandles,
                correlatedCandles = peers,
            )
        }

        val htfBiases = causal.higherTimeframeCandles
            .asSequence()
            .filter { (timeframe, candles) ->
                timeframe.minutes > primaryTimeframe.minutes && candles.size >= MIN_STRUCTURE_BARS
            }
            .associate { (timeframe, candles) -> timeframe to analyzeStructure(candles).bias }

        val evidence = buildList {
            addAll(providerEvidence(causal))

            smt.maxByOrNull { it.confirmationIndex }?.let { divergence ->
                add(
                    Evidence(
                        source = "SMT",
                        direction = divergence.direction,
                        score = divergence.confidence.roundToInt().coerceIn(60, 95),
                        detail = "${divergence.peerSymbol} · ${divergence.type.name} · ${"%.2f".format(java.util.Locale.US, divergence.correlation)} corr",
                    ),
                )
            }

            val directional = htfBiases.filterValues { it != Bias.NEUTRAL }
            if (directional.isNotEmpty()) {
                val bullish = directional.count { it.value == Bias.BULLISH }
                val bearish = directional.count { it.value == Bias.BEARISH }
                val direction = when {
                    bullish > bearish -> Direction.BULLISH
                    bearish > bullish -> Direction.BEARISH
                    else -> null
                }
                if (direction != null) {
                    val aligned = maxOf(bullish, bearish)
                    val score = (60.0 + aligned.toDouble() / directional.size * 25.0)
                        .roundToInt()
                        .coerceIn(60, 85)
                    add(
                        Evidence(
                            source = "HTF_STRUCTURE",
                            direction = direction,
                            score = score,
                            detail = "$aligned/${directional.size} higher timeframes aligned",
                        ),
                    )
                }
            }
        }

        return Analysis(
            context = causal,
            smtDivergences = smt,
            higherTimeframeBiases = htfBiases,
            evidence = evidence,
        )
    }

    private fun providerEvidence(context: StrategyMarketContext): List<Evidence> = buildList {
        context.provider?.let { provider ->
            add(Evidence("PROVIDER", null, 55, provider.displayName))
        }
        context.freshness?.let { freshness ->
            val score = when (freshness) {
                com.foxtrader.app.domain.model.MarketDataFreshness.LIVE -> 65
                com.foxtrader.app.domain.model.MarketDataFreshness.DELAYED -> 45
                com.foxtrader.app.domain.model.MarketDataFreshness.CACHED -> 30
                com.foxtrader.app.domain.model.MarketDataFreshness.SIMULATED -> 0
            }
            add(Evidence("FRESHNESS", null, score, freshness.name))
        }
    }

    companion object {
        private const val MIN_STRUCTURE_BARS = 11
    }
}
