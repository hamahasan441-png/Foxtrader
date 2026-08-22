package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Phase 4 confirmation layer for scanner opportunities.
 *
 * The base scanner still answers "is there a setup on this chart?". This layer answers the
 * higher-quality execution question: "does that setup agree with higher-timeframe direction,
 * correlated-market behaviour and the current risk regime?".
 *
 * It never invents context. Missing HTF or peer data simply reduces confidence and prevents the
 * stricter [ScreenerResult.actionable] flag. This makes the scanner fail closed instead of turning
 * missing data into a positive confirmation.
 */
class Phase4ConfluenceEngine @Inject constructor(
    private val smtDetector: SmtDivergenceDetector,
) {

    fun enrich(
        base: ScreenerResult,
        baseCandles: List<Candle>,
        higherTimeframeCandles: Map<Timeframe, List<Candle>>,
        correlatedCandles: Map<String, List<Candle>>,
        dataTrustworthy: Boolean = true,
    ): ScreenerResult {
        val mtf = evaluateMtf(base.direction, higherTimeframeCandles)
        val smt = evaluateSmt(base, baseCandles, correlatedCandles)

        var score = base.score
        score += when {
            mtf.checked == 0 -> 0
            mtf.alignment >= 0.999 -> MTF_FULL_BONUS
            mtf.alignment >= 0.5 -> MTF_PARTIAL_BONUS
            else -> -MTF_CONFLICT_PENALTY
        }
        score += when {
            smt.confirmed -> SMT_CONFIRM_BONUS
            smt.opposed -> -SMT_CONFLICT_PENALTY
            else -> 0
        }
        if (base.riskLevel == ScannerRiskLevel.HIGH) score -= HIGH_RISK_PENALTY
        score = score.coerceIn(0, 100)

        val riskMultiplier = adaptiveRiskMultiplier(
            alignment = mtf.alignment,
            checkedHtf = mtf.checked,
            smtConfirmed = smt.confirmed,
            smtOpposed = smt.opposed,
            riskLevel = base.riskLevel,
            score = score,
        )

        val actionable = dataTrustworthy &&
            score >= ACTIONABLE_SCORE &&
            mtf.checked > 0 &&
            mtf.alignment >= MIN_ACTIONABLE_MTF_ALIGNMENT &&
            !smt.opposed &&
            base.riskLevel != ScannerRiskLevel.HIGH

        val phase4Tags = buildList {
            if (mtf.checked == 0) {
                add("MTF unavailable")
            } else {
                add("MTF ${mtf.aligned}/${mtf.checked}")
                if (mtf.alignment < MIN_ACTIONABLE_MTF_ALIGNMENT) add("MTF conflict")
            }
            when {
                smt.confirmed -> add("SMT ${smt.peer ?: "confirmed"}")
                smt.opposed -> add("SMT conflict")
            }
            if (!dataTrustworthy) add("P4 data blocked")
            if (actionable) add("P4 confirmed")
            if (riskMultiplier < 0.99) add("Risk x${formatMultiplier(riskMultiplier)}")
        }

        val contextText = buildString {
            append(" Phase 4: ")
            if (mtf.checked == 0) {
                append("HTF context unavailable")
            } else {
                append("MTF ${mtf.aligned}/${mtf.checked} aligned")
            }
            when {
                smt.confirmed -> append(", SMT confirmed${smt.peer?.let { " by $it" } ?: ""}")
                smt.opposed -> append(", SMT conflicts${smt.peer?.let { " via $it" } ?: ""}")
                else -> append(", no fresh SMT confirmation")
            }
            append(", adaptive risk ${formatMultiplier(riskMultiplier)}x")
            if (!dataTrustworthy) append(", execution blocked by untrusted/simulated data")
            append(".")
        }

        return base.copy(
            score = score,
            tags = (base.tags + phase4Tags).distinct().take(MAX_TAGS),
            rationale = base.rationale + contextText,
            mtfAlignment = mtf.alignment,
            smtConfirmed = smt.confirmed,
            smtPeer = smt.peer,
            actionable = actionable,
            riskMultiplier = if (dataTrustworthy) riskMultiplier else MIN_RISK_MULTIPLIER,
        )
    }

    private fun evaluateMtf(
        direction: Direction,
        higherTimeframeCandles: Map<Timeframe, List<Candle>>,
    ): MtfEvaluation {
        val reads = higherTimeframeCandles.entries
            .sortedBy { it.key.minutes }
            .mapNotNull { (_, candles) ->
                if (candles.size < MIN_HTF_BARS) return@mapNotNull null
                val ema20 = TechnicalIndicators.calculateEMA(candles, 20).lastOrNull() ?: return@mapNotNull null
                val ema50 = TechnicalIndicators.calculateEMA(candles, 50).lastOrNull() ?: return@mapNotNull null
                val close = candles.last().close
                when {
                    ema20 > ema50 && close >= ema20 -> Bias.BULLISH
                    ema20 < ema50 && close <= ema20 -> Bias.BEARISH
                    else -> Bias.NEUTRAL
                }
            }

        if (reads.isEmpty()) return MtfEvaluation(checked = 0, aligned = 0, alignment = 0.0)
        val aligned = reads.count { bias ->
            when (direction) {
                Direction.BULLISH -> bias == Bias.BULLISH
                Direction.BEARISH -> bias == Bias.BEARISH
            }
        }
        return MtfEvaluation(
            checked = reads.size,
            aligned = aligned,
            alignment = aligned.toDouble() / reads.size,
        )
    }

    private fun evaluateSmt(
        base: ScreenerResult,
        baseCandles: List<Candle>,
        correlatedCandles: Map<String, List<Candle>>,
    ): SmtEvaluation {
        if (baseCandles.size < MIN_SMT_BARS || correlatedCandles.isEmpty()) return SmtEvaluation()
        val divergences = smtDetector.detect(
            primarySymbol = base.symbol,
            primaryCandles = baseCandles,
            correlatedCandles = correlatedCandles,
        )
        val latest = divergences
            .filter { baseCandles.lastIndex - it.confirmationIndex in 0..MAX_SMT_AGE_BARS }
            .maxByOrNull { it.confirmationIndex }
            ?: return SmtEvaluation()

        return SmtEvaluation(
            confirmed = latest.direction == base.direction,
            opposed = latest.direction != base.direction,
            peer = latest.peerSymbol,
        )
    }

    private fun adaptiveRiskMultiplier(
        alignment: Double,
        checkedHtf: Int,
        smtConfirmed: Boolean,
        smtOpposed: Boolean,
        riskLevel: ScannerRiskLevel,
        score: Int,
    ): Double {
        var multiplier = when {
            checkedHtf == 0 -> 0.60
            alignment >= 0.999 -> 1.00
            alignment >= 0.5 -> 0.75
            else -> 0.50
        }
        if (smtConfirmed) multiplier += 0.10
        if (smtOpposed) multiplier *= 0.65
        multiplier *= when (riskLevel) {
            ScannerRiskLevel.LOW -> 1.0
            ScannerRiskLevel.MODERATE -> 0.80
            ScannerRiskLevel.HIGH -> 0.50
        }
        if (score < 60) multiplier *= 0.60
        return multiplier.coerceIn(MIN_RISK_MULTIPLIER, 1.0)
    }

    private fun formatMultiplier(value: Double): String =
        ((value * 100.0).roundToInt() / 100.0).toString()

    private data class MtfEvaluation(
        val checked: Int,
        val aligned: Int,
        val alignment: Double,
    )

    private data class SmtEvaluation(
        val confirmed: Boolean = false,
        val opposed: Boolean = false,
        val peer: String? = null,
    )

    private companion object {
        const val MIN_HTF_BARS = 50
        const val MIN_SMT_BARS = 40
        const val MAX_SMT_AGE_BARS = 18
        const val MTF_FULL_BONUS = 12
        const val MTF_PARTIAL_BONUS = 5
        const val MTF_CONFLICT_PENALTY = 12
        const val SMT_CONFIRM_BONUS = 10
        const val SMT_CONFLICT_PENALTY = 10
        const val HIGH_RISK_PENALTY = 8
        const val ACTIONABLE_SCORE = 70
        const val MIN_ACTIONABLE_MTF_ALIGNMENT = 0.5
        const val MIN_RISK_MULTIPLIER = 0.25
        const val MAX_TAGS = 8
    }
}

/**
 * Conservative, explicit peer selection for SMT. Passing every market in the watchlist to a
 * correlation detector can find accidental short-window correlations; Phase 4 only permits peers
 * with an economic/market-structure relationship known to the product.
 */
object Phase4SmtPeerResolver {
    private val peers: Map<String, List<String>> = mapOf(
        "EURUSD" to listOf("GBPUSD"),
        "GBPUSD" to listOf("EURUSD"),
        "AUDUSD" to listOf("NZDUSD"),
        "NZDUSD" to listOf("AUDUSD"),
        "USDJPY" to listOf("USDCHF"),
        "USDCHF" to listOf("USDJPY"),
        "NAS100" to listOf("US500", "US30"),
        "US500" to listOf("NAS100", "US30"),
        "US30" to listOf("US500", "NAS100"),
        "BTCUSDT" to listOf("ETHUSDT"),
        "ETHUSDT" to listOf("BTCUSDT"),
        "SOLUSDT" to listOf("BTCUSDT", "ETHUSDT"),
        "XAUUSD" to listOf("XAGUSD"),
        "XAGUSD" to listOf("XAUUSD"),
    )

    fun peersFor(symbol: String): List<String> = peers[symbol.uppercase()].orEmpty()
}
