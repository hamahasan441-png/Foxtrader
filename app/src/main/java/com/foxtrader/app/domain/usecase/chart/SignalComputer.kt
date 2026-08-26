package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.SignalIdentity
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.SignalFusionResult
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.SignalEvidenceReducer
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import javax.inject.Inject

/**
 * Builds a unified [ChartSignal] list from current engine analysis results.
 *
 * A signal is live only when its setup/confirmation belongs to the current
 * confirmed chart bar. Historical entries stay historical even if they are the
 * newest signal available from that source.
 */
class SignalComputer @Inject constructor(
    private val evidenceReducer: SignalEvidenceReducer,
) {

    fun computeSignals(
        litXAnalysis: LitXAnalysis?,
        tradeProAnalysis: TradeProAnalysis?,
        smtDivergences: List<SmtDivergenceDetector.SmtDivergence>,
        candles: List<Candle>,
        currentTimeMillis: Long = System.currentTimeMillis(),
        strategySignals: List<ChartSignal> = emptyList(),
        litAnalysis: LitAnalysis? = null,
        smsAnalysis: SmsAnalysis? = null,
        rsiOrderFlowSignals: List<RsiOrderFlowSignalEngine.Signal> = emptyList(),
        latestConfirmedIndex: Int = candles.lastIndex,
        fusion: SignalFusionResult? = null,
    ): List<ChartSignal> {
        if (candles.isEmpty()) return emptyList()

        val signals = mutableListOf<ChartSignal>()

        // Strategy-library signals are already fully-formed ChartSignals with
        // correct bar indices and live/history flags.
        signals += strategySignals

        // LIT X signal. Plot on its explicit confirmation bar when available.
        litXAnalysis?.signal?.let { signal ->
            val barIndex = signal.confirmationIndex.takeIf { it in candles.indices }
                ?: latestConfirmedIndex.takeIf { it in candles.indices }
                ?: candles.lastIndex
            val rawMode = signal.confirmations
                .firstOrNull { it.startsWith("MODE_") }
                ?.removePrefix("MODE_")
            val adventureVariant = litAdventureVariant(rawMode)
            val adventureLabel = litAdventureLabel(adventureVariant)
            signals.add(
                ChartSignal(
                    // Preserve the legacy public/chart ID contract. Semantic
                    // deduplication is carried separately by eventKey.
                    id = "litx_${signal.timestamp}",
                    source = SignalSource.LITX,
                    direction = signal.direction,
                    entry = signal.entry,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit1,
                    barIndex = barIndex,
                    timestamp = signal.timestamp,
                    confidence = signal.confidence.score.toDouble(),
                    isLive = barIndex == latestConfirmedIndex,
                    label = buildSignalLabel("LiTX $adventureLabel", signal.confidence.score, signal.confirmations),
                    // Production LiT Adventure profiles map their internal
                    // structural rule set to the three trader-facing indicator
                    // variants. This makes history/outcome statistics separate
                    // Fast Scalp, Balanced Trade and Power Trade instead of
                    // exposing implementation names such as SWEEP_REVERSAL.
                    variant = adventureVariant,
                    eventKey = SignalIdentity.litX(
                        symbol = signal.symbol,
                        timeframe = signal.timeframe,
                        timestamp = signal.timestamp,
                        direction = signal.direction,
                        confirmationIndex = barIndex,
                    ),
                )
            )
        }

        // First-class LIT institutional sequence.
        litAnalysis?.signal?.let { signal ->
            val barIndex = signal.confirmationIndex.takeIf { it in candles.indices } ?: return@let
            signals.add(
                ChartSignal(
                    // Preserve the legacy public/chart ID contract. Semantic
                    // deduplication is carried separately by eventKey.
                    id = "lit_${signal.symbol}_${signal.timestamp}_${signal.direction}",
                    source = SignalSource.LIT,
                    direction = signal.direction,
                    entry = signal.entry,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit,
                    barIndex = barIndex,
                    timestamp = signal.timestamp,
                    confidence = signal.confidence.toDouble(),
                    isLive = barIndex == latestConfirmedIndex,
                    label = buildSignalLabel("LiT", signal.confidence, signal.confirmations),
                    eventKey = SignalIdentity.lit(
                        symbol = signal.symbol,
                        timeframe = signal.timeframe,
                        timestamp = signal.timestamp,
                        direction = signal.direction,
                        confirmationIndex = barIndex,
                    ),
                )
            )
        }

        // Smart Money Structure context marker belongs to confirmation bar.
        smsAnalysis?.signal?.let { signal ->
            val barIndex = signal.confirmationIndex.takeIf { it in candles.indices } ?: return@let
            val entry = candles[barIndex].close
            signals.add(
                ChartSignal(
                    id = "sms_${signal.symbol}_${signal.type}_${signal.eventIndex}_${signal.confirmationIndex}",
                    source = SignalSource.SMS,
                    direction = signal.direction,
                    entry = entry,
                    sl = 0.0,
                    tp = 0.0,
                    barIndex = barIndex,
                    timestamp = candles[barIndex].timestamp,
                    confidence = signal.confidence.toDouble(),
                    isLive = barIndex == latestConfirmedIndex,
                    label = buildSignalLabel("SMS ${signal.type}", signal.confidence, signal.confirmations),
                )
            )
        }

        // TradePro signal (only EXECUTE stage).
        tradeProAnalysis?.setup?.let { setup ->
            if (setup.stage == SetupStage.EXECUTE) {
                val barIndex = latestConfirmedIndex.takeIf { it in candles.indices } ?: candles.lastIndex
                signals.add(
                    ChartSignal(
                        id = "tradepro_${setup.symbol}_${setup.entry}_${candles[barIndex].timestamp}",
                        source = SignalSource.TRADEPRO,
                        direction = setup.direction,
                        entry = setup.entry,
                        sl = setup.stopLoss,
                        tp = setup.target1,
                        barIndex = barIndex,
                        timestamp = candles[barIndex].timestamp,
                        confidence = setup.confidence.toDouble(),
                        isLive = latestConfirmedIndex in candles.indices,
                        label = buildSignalLabel(
                            "TradePro",
                            setup.confidence,
                            fusion?.confirmations ?: setup.confluences.takeLast(5),
                        ),
                    )
                )
            }
        }

        // RSI Orderflow Candle becomes actionable only on the divergence's
        // explicit confirmation bar. Unlike context-only SMT markers, these
        // signals carry causal entry/SL/TP geometry from the shared engine.
        for (signal in rsiOrderFlowSignals) {
            val confirmationCandle = candles.getOrNull(signal.confirmationIndex) ?: continue
            if (signal.timestamp != confirmationCandle.timestamp) continue
            signals.add(
                ChartSignal(
                    id = "rsi_of_${signal.symbol}_${signal.timestamp}_${signal.direction.name}",
                    source = SignalSource.RSI_ORDERFLOW,
                    direction = signal.direction,
                    entry = signal.entry,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit,
                    barIndex = signal.confirmationIndex,
                    timestamp = signal.timestamp,
                    confidence = signal.confidence.toDouble(),
                    isLive = signal.confirmationIndex == latestConfirmedIndex,
                    label = buildSignalLabel(
                        "RSI Orderflow",
                        signal.confidence,
                        signal.reasons.take(4),
                    ),
                    eventKey = SignalIdentity.rsiOrderFlow(
                        symbol = signal.symbol,
                        timeframe = signal.timeframe,
                        timestamp = signal.timestamp,
                        direction = signal.direction,
                        confirmationIndex = signal.confirmationIndex,
                    ),
                )
            )
        }

        // SMT divergences become actionable on their confirmation bar, never on
        // the hindsight swing origin.
        for (div in smtDivergences) {
            val confirmationCandle = candles.getOrNull(div.confirmationIndex) ?: continue
            if (div.primaryIndex !in candles.indices || div.confirmationIndex < div.primaryIndex) continue
            signals.add(
                ChartSignal(
                    id = "smt_${div.primarySymbol}_${div.peerSymbol}_${div.type.name}_" +
                        "${div.primaryIndex}_${div.confirmationIndex}",
                    source = SignalSource.SMT,
                    direction = div.direction,
                    entry = confirmationCandle.close,
                    sl = 0.0,
                    tp = 0.0,
                    barIndex = div.confirmationIndex,
                    timestamp = confirmationCandle.timestamp,
                    confidence = div.confidence,
                    isLive = div.confirmationIndex == latestConfirmedIndex,
                    label = "SMT ${div.peerSymbol} · ${"%.0f".format(div.confidence)}",
                )
            )
        }

        // Treat every upstream engine as untrusted at this boundary. A malformed
        // NaN price, wrong-side stop, stale index, or invalid SMT marker must not
        // reach Canvas math or confidence confluence.
        val renderable = signals
            .filter { it.isRenderable(candles) }
            .map { it.copy(confidence = normalizeConfidence(it.confidence)) }

        // Canonical engine outputs and their StrategyLibrary mirrors share one
        // semantic eventKey while preserving their legacy public IDs. Collapse
        // those mirrors before evidence-family math so one market event cannot
        // draw two arrows or inflate confluence through two integration paths.
        val deduplicated = deduplicateStableSignals(renderable)
        return applyConfluence(deduplicated, phase13TradeProAlreadyFused = fusion != null)
    }

    /**
     * Deterministically collapse semantic duplicates by eventKey when available;
     * otherwise retain the historical ID-based behavior.
     *
     * Direct engine output wins over the generic STRATEGY mirror. Distinct
     * strategy IDs without a shared eventKey are never merged, even if they fire
     * on the same bar in the same direction.
     */
    private fun deduplicateStableSignals(signals: List<ChartSignal>): List<ChartSignal> {
        if (signals.size < 2) return signals

        val byKey = LinkedHashMap<String, ChartSignal>(signals.size)
        for (candidate in signals) {
            val key = candidate.eventKey ?: candidate.id
            val existing = byKey[key]
            if (existing == null || shouldReplaceDuplicate(existing, candidate)) {
                byKey[key] = candidate
            }
        }
        return byKey.values.toList()
    }

    private fun shouldReplaceDuplicate(existing: ChartSignal, candidate: ChartSignal): Boolean {
        val existingPriority = sourcePriority(existing.source)
        val candidatePriority = sourcePriority(candidate.source)
        return candidatePriority > existingPriority ||
            (candidatePriority == existingPriority && candidate.confidence > existing.confidence)
    }

    private fun sourcePriority(source: SignalSource): Int = when (source) {
        SignalSource.LIT -> 100
        SignalSource.LITX -> 95
        SignalSource.SMS -> 90
        SignalSource.RSI_ORDERFLOW -> 88
        SignalSource.PIVOT_SWEEP_DIVERGENCE -> 92
        SignalSource.VALUE_AREA_LIQUIDITY_REJECTION -> 93
        SignalSource.ACCUMULATION_MANIPULATION_DISTRIBUTION -> 91
        SignalSource.NASCENT -> 94
        SignalSource.SMT -> 85
        SignalSource.TRADEPRO -> 80
        SignalSource.BINARY3M -> 70
        SignalSource.STRATEGY -> 10
    }

    /**
     * Reinforce confidence only when independent evidence families agree.
     *
     * LiTX, LiT and SMS share structure/liquidity primitives. They therefore
     * belong to one family and cannot boost one another merely because they are
     * exposed as different [SignalSource] values. SMT is a divergence family,
     * RSI Orderflow is momentum/orderflow-proxy evidence, and TradePro is
     * composite. This mirrors [SignalEvidenceReducer] used by the upstream
     * fusion layer and prevents UI-only confidence inflation.
     */
    private fun applyConfluence(
        signals: List<ChartSignal>,
        phase13TradeProAlreadyFused: Boolean,
    ): List<ChartSignal> {
        if (signals.size < 2) return signals

        // Only live signals represent the current market read. Binary3m is
        // excluded because its confidence is part of a fixed-expiry strategy
        // contract shared with its backtester.
        val liveFamiliesByDirection: Map<Direction, Set<SignalEvidenceReducer.Family>> =
            signals
                .filter { it.isLive && it.source != SignalSource.BINARY3M }
                .groupBy { it.direction }
                .mapValues { (_, group) -> group.map { evidenceReducer.family(it.source) }.toSet() }

        return signals.map { signal ->
            if (!signal.isLive) return@map signal
            if (signal.source == SignalSource.BINARY3M) return@map signal
            // TradePro confidence has already been adjusted by the Phase-13
            // fusion engine. Do not apply a second chart-level boost.
            if (phase13TradeProAlreadyFused && signal.source == SignalSource.TRADEPRO) return@map signal

            val agreeingFamilies = liveFamiliesByDirection[signal.direction].orEmpty()
            val ownFamily = evidenceReducer.family(signal.source)
            val otherFamilies = (agreeingFamilies - ownFamily).size
            if (otherFamilies <= 0) {
                signal
            } else {
                val boost = (otherFamilies * CONFLUENCE_BOOST_PER_FAMILY)
                    .coerceAtMost(CONFLUENCE_BOOST_MAX)
                signal.copy(confidence = (signal.confidence + boost).coerceAtMost(1.0))
            }
        }
    }

    /** Normalize historical 0..100 and 0..1 confidence scales at UI boundary. */
    private fun normalizeConfidence(value: Double): Double = when {
        !value.isFinite() -> 0.0
        value > 1.0 -> value / 100.0
        else -> value
    }.coerceIn(0.0, 1.0)

    private fun ChartSignal.isRenderable(candles: List<Candle>): Boolean {
        if (barIndex !in candles.indices || !entry.isFinite() || entry <= 0.0) return false
        if (!confidence.isFinite()) return false
        if ((source == SignalSource.STRATEGY || source == SignalSource.BINARY3M) &&
            timestamp != candles[barIndex].timestamp
        ) return false

        if (source == SignalSource.SMT || source == SignalSource.SMS || source == SignalSource.BINARY3M) {
            return sl == 0.0 && tp == 0.0
        }

        if (!sl.isFinite() || !tp.isFinite() || sl <= 0.0 || tp <= 0.0) return false
        return when (direction) {
            Direction.BULLISH -> sl < entry && tp > entry
            Direction.BEARISH -> sl > entry && tp < entry
        }
    }

    private fun litAdventureVariant(rawMode: String?): String? = when (rawMode) {
        "SWEEP_REVERSAL" -> "FAST_SCALP"
        "PRECISION" -> "BALANCED_TRADE"
        "SNIPER" -> "POWER_TRADE"
        "MOMENTUM" -> "FAST_SCALP_MOMENTUM"
        null -> null
        else -> rawMode
    }

    private fun litAdventureLabel(variant: String?): String = when (variant) {
        "FAST_SCALP" -> "Fast Scalp"
        "BALANCED_TRADE" -> "Balanced"
        "POWER_TRADE" -> "Power"
        "FAST_SCALP_MOMENTUM" -> "Fast Momentum"
        null -> "Signal"
        else -> variant.replace('_', ' ')
    }

    private fun buildSignalLabel(name: String, score: Int, confirmations: List<String>): String {
        val compact = confirmations.take(4).joinToString(" · ")
        return if (compact.isBlank()) "$name $score" else "$name $score · $compact"
    }

    private companion object {
        const val CONFLUENCE_BOOST_PER_FAMILY = 0.04
        const val CONFLUENCE_BOOST_MAX = 0.08
    }
}
