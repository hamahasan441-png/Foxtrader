package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.apex.model.ApexAnalysis
import com.foxtrader.app.domain.usecase.apex.model.ApexCandidate
import com.foxtrader.app.domain.usecase.apex.model.ApexOutcome
import com.foxtrader.app.domain.usecase.apex.model.ApexPrecision
import com.foxtrader.app.domain.usecase.apex.model.ApexSignal
import com.foxtrader.app.domain.usecase.apex.model.ApexVote
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Apex — multi-methodology consensus behind a measured precision gate.
 *
 * Two ideas, in order.
 *
 * **Agreement.** Apex adds no new way of reading price. It runs the
 * methodologies this app already implements and publishes only where several
 * of them, independently, arrive at the same trade at the same moment. Members
 * keep their own rules and defaults precisely so that their agreement carries
 * information; a member tuned to agree more often would stop being evidence.
 *
 * **Measurement.** A required hit rate is then enforced against the engine's
 * own record instead of being asserted about it. At every bar the gate consults
 * only trades that had already resolved by then, so the number it acts on is
 * one that was genuinely available at the time.
 *
 * What this does and does not promise is worth stating plainly, because the
 * difference is the whole point. It does **not** promise that published signals
 * win at the configured rate — no method can promise that about the future.
 * What it guarantees is narrower and actually checkable: **while the engine's
 * own recent measured record is below your threshold, it publishes nothing.**
 * A quiet chart is the mechanism working, not failing.
 *
 * Win rate travels with expectancy everywhere it is reported, because a high
 * rate bought with a small target can still lose money, and a gate on the rate
 * alone would happily produce exactly that.
 */
@Singleton
class ApexEngine @Inject constructor(
    private val collector: ApexVoteCollector,
) {

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: ApexConfig = ApexConfig(),
    ): ApexAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) return ApexAnalysis.empty(integrity.reason ?: "Invalid market data.")
        if (config.members.isEmpty()) return ApexAnalysis.empty("Apex: no member methodologies enabled.")

        val votes = collector.collect(symbol, timeframe, candles, config)
        if (votes.isEmpty()) return ApexAnalysis.empty("Apex: no member produced a signal yet.")

        val candidates = buildCandidates(votes, candles, config)
        if (candidates.isEmpty()) {
            return ApexAnalysis(
                votes = votes,
                candidates = emptyList(),
                signals = emptyList(),
                methodPrecision = ApexPrecision.EMPTY,
                publishedPrecision = ApexPrecision.EMPTY,
                statusText = "Apex: ${config.minAgreeingMembers} members have not agreed yet.",
            )
        }

        val signals = publish(symbol, timeframe, candidates, config)

        val published = if (config.historicalSignals) {
            signals
        } else {
            val cutoff = candles.lastIndex - config.liveWindowBars + 1
            signals.filter { it.index >= cutoff }
        }

        val methodPrecision = ApexOutcomeLedger.summarise(candidates)
        val publishedPrecision = ApexOutcomeLedger.summarise(published.map { it.candidate })

        return ApexAnalysis(
            votes = votes,
            candidates = candidates,
            signals = published,
            methodPrecision = methodPrecision,
            publishedPrecision = publishedPrecision,
            statusText = statusText(candidates, published, methodPrecision, config),
        )
    }

    // ------------------------------------------------------------------
    // Consensus
    // ------------------------------------------------------------------

    /**
     * Group votes into candidates, then resolve what each one did.
     *
     * A candidate is stamped on the bar its agreement was **completed** — the
     * vote that brought the number of distinct members up to the requirement.
     * The clustering rule that guarantees this cannot move later lives in
     * [ApexConsensus], where it is tested directly.
     */
    private fun buildCandidates(
        votes: List<ApexVote>,
        candles: List<Candle>,
        config: ApexConfig,
    ): List<ApexCandidate> {
        val out = ArrayList<ApexCandidate>()

        for (direction in listOf(Direction.BULLISH, Direction.BEARISH)) {
            val clusters = ApexConsensus.cluster(
                votes = votes.filter { it.direction == direction },
                minAgreeingMembers = config.minAgreeingMembers,
                agreementWindowBars = config.agreementWindowBars,
            )
            for (contributing in clusters) {
                val index = contributing.maxOf { it.index }
                geometryFor(direction, contributing, index, candles, config)?.let { out += it }
            }
        }

        return out.sortedBy { it.index }
    }

    /**
     * Build the trade the agreeing members imply.
     *
     * The stop is the widest of them: they agree on the idea but not on where
     * it is wrong, and the tightest stop is the one most likely to be inside
     * another member's noise. The target is the nearest, for the mirror reason.
     */
    private fun geometryFor(
        direction: Direction,
        votes: List<ApexVote>,
        index: Int,
        candles: List<Candle>,
        config: ApexConfig,
    ): ApexCandidate? {
        val bullish = direction == Direction.BULLISH
        val entry = candles[index].close
        if (!entry.isFinite() || entry <= 0.0) return null

        val stop = if (bullish) votes.minOf { it.stop } else votes.maxOf { it.stop }
        val risk = if (bullish) entry - stop else stop - entry
        if (risk <= 0.0 || !risk.isFinite()) return null

        val fixed = if (bullish) entry + config.rewardMultiple * risk else entry - config.rewardMultiple * risk
        val nearestMember = votes
            .map { it.target }
            .filter { if (bullish) it > entry else it < entry }
            .minByOrNull { abs(it - entry) }

        val target = when (config.targetMode) {
            TargetMode.FIXED_R -> fixed
            TargetMode.NEAREST_MEMBER -> nearestMember ?: fixed
        }
        val reward = if (bullish) target - entry else entry - target
        if (reward <= 0.0 || reward / risk < config.minRewardMultiple) return null

        val (outcome, resolvedIndex) = ApexOutcomeLedger.resolve(
            candles = candles,
            index = index,
            direction = direction,
            entry = entry,
            stop = stop,
            target = target,
            maxHoldBars = config.maxHoldBars,
        )
        val realisedR = when (outcome) {
            ApexOutcome.WIN -> reward / risk
            ApexOutcome.LOSS -> -1.0
            else -> null
        }

        return ApexCandidate(
            direction = direction,
            index = index,
            timestamp = candles[index].timestamp,
            entry = entry,
            stop = stop,
            target = target,
            votes = votes.sortedBy { it.member.name },
            outcome = outcome,
            resolvedIndex = resolvedIndex,
            realisedR = realisedR,
        )
    }

    // ------------------------------------------------------------------
    // The gate
    // ------------------------------------------------------------------

    private fun publish(
        symbol: String,
        timeframe: Timeframe,
        candidates: List<ApexCandidate>,
        config: ApexConfig,
    ): List<ApexSignal> {
        val out = ArrayList<ApexSignal>()

        for (candidate in candidates) {
            val precision = ApexOutcomeLedger.precisionAt(
                candidates = candidates,
                asOfIndex = candidate.index,
                window = config.precisionWindow,
            )

            val allowed = when {
                precision.resolved >= config.minResolvedSample ->
                    precision.meets(config.minHitRate, config.minResolvedSample, config.useConfidenceBound)

                else -> config.warmupPolicy == WarmupPolicy.PUBLISH_UNMEASURED
            }
            if (!allowed) continue

            out += ApexSignal(
                symbol = symbol,
                timeframe = timeframe,
                candidate = candidate,
                precisionAtPublication = precision,
                reasons = reasonsFor(candidate, precision, config),
            )
        }
        return out
    }

    private fun reasonsFor(
        candidate: ApexCandidate,
        precision: ApexPrecision,
        config: ApexConfig,
    ): List<String> = buildList {
        add("${candidate.members.size} members agreed: ${candidate.members.joinToString { it.label }}")
        add("Target ${"%.2f".format(candidate.rewardMultiple)}R")
        if (precision.resolved >= config.minResolvedSample) {
            add(
                "Measured ${(precision.hitRate!! * 100).toInt()}% over ${precision.resolved} resolved " +
                    "(≥${((precision.hitRateLowerBound ?: 0.0) * 100).toInt()}% at 95% confidence), " +
                    "expectancy ${"%.2f".format(precision.expectancyR ?: 0.0)}R",
            )
        } else {
            add("Published before the record could be measured (${precision.resolved} resolved)")
        }
    }

    private fun statusText(
        candidates: List<ApexCandidate>,
        published: List<ApexSignal>,
        precision: ApexPrecision,
        config: ApexConfig,
    ): String {
        val rate = precision.hitRate
        return when {
            rate == null -> "Apex: ${candidates.size} candidates, none resolved yet"
            published.isEmpty() ->
                "Apex silent — measured ${(rate * 100).toInt()}% over ${precision.resolved} " +
                    "is below the ${(config.minHitRate * 100).toInt()}% required"
            else ->
                "Apex: ${published.size} published · measured ${(rate * 100).toInt()}% " +
                    "· expectancy ${"%.2f".format(precision.expectancyR ?: 0.0)}R"
        }
    }

    // ------------------------------------------------------------------
    // Backtest entry point
    // ------------------------------------------------------------------

    /** The signal published exactly on [index], for the backtester. */
    fun signalAt(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
        config: ApexConfig = ApexConfig(),
    ): StrategySignal? {
        if (index !in candles.indices) return null
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)

        val signal = analyze(symbol, timeframe, visible, config).signals
            .lastOrNull { it.index == index } ?: return null

        return StrategySignal(
            index = index,
            timestamp = signal.timestamp,
            direction = signal.direction,
            entry = signal.entry,
            stopLoss = signal.stop,
            takeProfit = signal.target,
            confidence = ((signal.precisionAtPublication.hitRate ?: 0.0) * 100).toInt(),
            setupType = "Apex ${signal.candidate.members.size}x consensus",
        )
    }

    /**
     * Strategy function for the backtester, bound to a symbol and timeframe.
     *
     * Re-analyses the visible prefix at every bar. Correct, and the same shape
     * every other engine here uses, but Apex runs six member engines per call,
     * which makes this quadratic in a way the others are not — prefer
     * [backtestFunction] for a run of any length.
     */
    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: ApexConfig = ApexConfig(),
    ): (List<Candle>, Int) -> StrategySignal? = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    /**
     * The same strategy function, computed in a single pass over [candles].
     *
     * This is not an approximation of the per-bar version, it is equal to it.
     * The engine is non-repainting in the strict sense: analysing a prefix
     * yields exactly the signals the completed history reports inside that
     * prefix — same bars, same members, no slack — because a cluster is stamped
     * when its agreement completes and cannot be moved by later votes, and
     * because the gate at any bar reads only trades that resolved before it.
     * That equality is asserted directly in `ApexEngineTest`; this method is
     * only worth having because it holds.
     *
     * What it buys is the difference between a research run that finishes and
     * one that does not: one analysis instead of one per bar.
     */
    fun backtestFunction(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: ApexConfig = ApexConfig(),
    ): (List<Candle>, Int) -> StrategySignal? {
        val byIndex = analyze(symbol, timeframe, candles, config).signals
            .associateBy { it.index }

        return { visible, index ->
            // Guard the backtester's contract rather than trusting it: a
            // lookup that silently answered for the wrong bar would be
            // indistinguishable from look-ahead in the results.
            val bar = visible.getOrNull(index)
            byIndex[index]
                ?.takeIf { bar != null && bar.timestamp == it.timestamp }
                ?.let { signal ->
                    StrategySignal(
                        index = index,
                        timestamp = signal.timestamp,
                        direction = signal.direction,
                        entry = signal.entry,
                        stopLoss = signal.stop,
                        takeProfit = signal.target,
                        confidence = ((signal.precisionAtPublication.hitRate ?: 0.0) * 100).toInt(),
                        setupType = "Apex ${signal.candidate.members.size}x consensus",
                    )
                }
        }
    }

    fun minimumBars(): Int = MIN_BARS

    companion object {
        /** Enough bars for the members to have anything to say. */
        const val MIN_BARS = 200
    }
}
