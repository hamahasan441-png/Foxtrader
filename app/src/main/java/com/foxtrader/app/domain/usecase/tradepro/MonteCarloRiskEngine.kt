package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.tradepro.RiskSimulationInput
import com.foxtrader.app.domain.model.tradepro.RiskSimulationResult
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

/**
 * Runs a Monte Carlo simulation of an edge to estimate **risk of ruin** and the distribution of
 * outcomes, using compounding position sizing (risk a fixed fraction of *current* equity per trade).
 *
 * This answers the question the backtest can't: "given this win rate and payoff, how often does a
 * random ordering of trades blow the account, and what does the spread of results look like?" Trade
 * *order* matters under compounding, so sampling many random sequences reveals the tail risk a single
 * historical path hides.
 *
 * Deterministic: a [seed] fully determines the output, so results are reproducible and unit-testable.
 * Pure (no I/O, no framework state).
 */
class MonteCarloRiskEngine @Inject constructor() {

    fun simulate(input: RiskSimulationInput, seed: Long = DEFAULT_SEED): RiskSimulationResult {
        if (!input.isValid) {
            return RiskSimulationResult.empty("Adjust the inputs \u2014 they're outside the valid range.")
        }

        val random = Random(seed)
        val endMultiples = DoubleArray(input.runs)
        val maxDrawdowns = DoubleArray(input.runs)
        var ruinCount = 0
        var profitableCount = 0

        val sampleCurves = ArrayList<List<Double>>(SAMPLE_CURVES)
        val sampleStride = (input.runs / SAMPLE_CURVES).coerceAtLeast(1)

        for (run in 0 until input.runs) {
            val captureCurve = sampleCurves.size < SAMPLE_CURVES && run % sampleStride == 0
            val curve = if (captureCurve) ArrayList<Double>(input.tradesPerRun + 1) else null

            var equity = 1.0
            var peak = 1.0
            var maxDd = 0.0
            var ruined = false
            curve?.add(equity)

            for (t in 0 until input.tradesPerRun) {
                val win = random.nextDouble() < input.winRate
                val deltaR = if (win) input.avgWinR else -input.avgLossR
                // Risk a fixed fraction of current equity; P&L = riskAmount * R.
                equity += equity * input.riskPerTradeFraction * deltaR
                if (equity < 0.0) equity = 0.0
                if (equity > peak) peak = equity
                val dd = if (peak > 0.0) (peak - equity) / peak else 0.0
                if (dd > maxDd) maxDd = dd
                curve?.add(equity)
                if (!ruined && equity <= input.ruinThresholdFraction) {
                    ruined = true
                }
            }

            if (ruined) ruinCount++
            if (equity > 1.0) profitableCount++
            endMultiples[run] = equity
            maxDrawdowns[run] = maxDd
            if (curve != null) sampleCurves += curve
        }

        endMultiples.sort()
        maxDrawdowns.sort()

        val result = RiskSimulationResult(
            expectancyR = input.expectancyR,
            riskOfRuinFraction = ruinCount.toDouble() / input.runs,
            medianEndMultiple = percentile(endMultiples, 0.50),
            meanEndMultiple = endMultiples.average(),
            p5EndMultiple = percentile(endMultiples, 0.05),
            p25EndMultiple = percentile(endMultiples, 0.25),
            p75EndMultiple = percentile(endMultiples, 0.75),
            p95EndMultiple = percentile(endMultiples, 0.95),
            profitableRunFraction = profitableCount.toDouble() / input.runs,
            medianMaxDrawdownFraction = percentile(maxDrawdowns, 0.50),
            p95MaxDrawdownFraction = percentile(maxDrawdowns, 0.95),
            sampleEquityCurves = sampleCurves,
            runsSimulated = input.runs,
            narrative = narrative(input, ruinCount.toDouble() / input.runs, percentile(endMultiples, 0.50)),
        )
        return result
    }

    /**
     * Linear-interpolated percentile of an already-sorted ascending array. [p] in 0..1.
     */
    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val frac = rank - low
        return sorted[low] + (sorted[high] - sorted[low]) * frac
    }

    private fun narrative(input: RiskSimulationInput, riskOfRuin: Double, medianEnd: Double): String {
        val edge = if (input.expectancyR > 0.0) "positive (${fmt(input.expectancyR)}R/trade)" else "negative (${fmt(input.expectancyR)}R/trade)"
        val medianPct = (medianEnd - 1.0) * 100.0
        val ror = riskOfRuin * 100.0
        val verdict = when {
            input.expectancyR <= 0.0 -> "No edge \u2014 no amount of sizing fixes a negative expectancy."
            ror <= 1.0 -> "Robust: ruin is very unlikely at this risk level."
            ror <= 5.0 -> "Acceptable, but tighten sizing if you want more margin."
            ror <= 20.0 -> "Fragile \u2014 this risk-per-trade invites blow-up. Cut size."
            else -> "Reckless \u2014 ruin is probable. Cut risk-per-trade sharply."
        }
        return buildString {
            append("Edge is $edge over ${input.tradesPerRun} trades, ")
            append("risking ${fmt(input.riskPerTradeFraction * 100)}% each. ")
            append("Median outcome ${fmtSigned(medianPct)}%, ")
            append("risk of ruin ${fmt(ror)}%. ")
            append(verdict)
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmtSigned(v: Double): String = String.format(Locale.US, "%+.1f", v)

    companion object {
        private const val DEFAULT_SEED = 20260803L
        private const val SAMPLE_CURVES = 12
    }
}
