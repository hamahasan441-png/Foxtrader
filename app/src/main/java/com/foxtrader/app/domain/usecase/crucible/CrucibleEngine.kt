package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.compass.CompassLabeler
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleAnalysis
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleEvidence
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleFinding
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleObservation
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleRule
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleSignal
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crucible — rule discovery that has to survive being tested.
 *
 * The other engines here judge setups someone already designed. Crucible does
 * the harder and far more dangerous thing: it **searches** for conditions under
 * which an outcome is unusually predictable. Searching always succeeds. Try
 * enough conditions on any data, including data with nothing in it, and one
 * will look extraordinary — so the entire engine is built around the question
 * of whether a finding survives the fact that it was found.
 *
 * Four defences, each aimed at a different way this goes wrong.
 *
 * **Out of sample, purged and embargoed.** Outcomes span a horizon, so an
 * ordinary split lets a training observation's label be written by the very
 * bars it will be tested on. Every finding is scored only where that overlap
 * has been removed.
 *
 * **Effective sample, not raw count.** Neighbouring observations describe the
 * same stretch of market. A hundred overlapping outcomes are not a hundred
 * facts, and every bound is computed on the independent-equivalent count
 * instead of the flattering one.
 *
 * **False discovery rate across the whole search.** Testing thousands of rules
 * at 95% each guarantees false findings in proportion to how many were tried.
 * Benjamini-Hochberg bounds the share of published findings expected to be
 * spurious.
 *
 * **The search itself is measured.** Combinatorially symmetric cross-validation
 * asks how often the in-sample winner ranks below median out of sample. That
 * number tends to one as a search widens, whether or not anything real is
 * there. When it is high, nothing is published at all — because the best rule
 * found is then a description of the search, not of the market.
 *
 * What Crucible promises is not that it will find something. It promises that
 * what it reports has survived all four, and that when nothing survives it
 * says so.
 */
@Singleton
class CrucibleEngine @Inject constructor() {

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: CrucibleConfig = CrucibleConfig(),
    ): CrucibleAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) {
            return CrucibleAnalysis.empty(config.target, integrity.reason ?: "Invalid market data.")
        }

        val observations = CrucibleObservations.build(candles, config)
        if (observations.size < MIN_OBSERVATIONS) {
            return CrucibleAnalysis.empty(
                config.target,
                "Crucible: ${observations.size} of $MIN_OBSERVATIONS observations needed to search.",
            )
        }

        val bucketCount = config.cutPoints.size + 1
        val featureCount = CrucibleObservations.FEATURE_NAMES.size
        val rules = CrucibleSearch.enumerate(featureCount, bucketCount, config.target, config.maxConditions)
        if (rules.isEmpty()) return CrucibleAnalysis.empty(config.target, "Crucible: no rules to test.")

        val folds = CrucibleValidation.split(observations, config.folds, config.embargoBars)
        if (folds.isEmpty()) {
            return CrucibleAnalysis.empty(config.target, "Crucible: not enough data to validate out of sample.")
        }

        val overallBaseRate = CrucibleSearch.baseRateOf(observations, config.target, null)
        val effective = observations.sumOf { it.uniqueness }

        // Score every rule out of sample by pooling the held-out folds. Pooling
        // rather than averaging keeps rare rules honest: a rule matching three
        // observations in each of eight folds has 24 observations of evidence,
        // not eight separate perfect scores.
        val pooled = rules.map { rule -> rule to poolOutOfSample(rule, folds, config) }

        // Tally each rule against each block once. Every split the overfitting
        // check considers is then a sum of eight numbers instead of a rescan of
        // the whole series, which is the difference between a check that runs
        // and one that is quietly skipped.
        val blocks = config.folds
        val matched = Array(rules.size) { IntArray(blocks) }
        val hits = Array(rules.size) { IntArray(blocks) }
        val ordered = observations.sortedBy { it.index }
        val blockSize = ordered.size / blocks

        for ((position, observation) in ordered.withIndex()) {
            val block = (position / blockSize).coerceAtMost(blocks - 1)
            for ((r, rule) in rules.withIndex()) {
                if (!rule.matches(observation.buckets)) continue
                matched[r][block]++
                if (CrucibleSearch.hit(observation, rule, config.target)) hits[r][block]++
            }
        }

        val overfitting = CrucibleValidation.overfittingProbability(
            matched = matched,
            hits = hits,
            blocks = blocks,
        )

        val candidates = pooled.filter { (_, evidence) ->
            evidence.effectiveSamples >= config.minEffectiveSample && evidence.pValue != null
        }
        val threshold = CrucibleValidation.benjaminiHochbergThreshold(
            pValues = candidates.mapNotNull { it.second.pValue },
            falseDiscoveryRate = config.falseDiscoveryRate,
        )

        val findings = buildFindings(candidates, threshold, folds, config)
        val blockedByOverfitting = (overfitting.probability ?: 1.0) > config.maxOverfittingProbability
        val published = if (blockedByOverfitting) emptyList() else findings

        val signals = if (published.isEmpty()) {
            emptyList()
        } else {
            emit(symbol, timeframe, candles, observations, published, config)
        }

        return CrucibleAnalysis(
            target = config.target,
            observations = observations.size,
            effectiveObservations = effective,
            baseRate = overallBaseRate,
            rulesTested = rules.size,
            findings = published,
            signals = signals,
            overfitting = overfitting,
            statusText = statusText(observations, effective, overallBaseRate, rules.size, findings, overfitting, blockedByOverfitting, config),
        )
    }

    // ------------------------------------------------------------------

    private fun poolOutOfSample(
        rule: CrucibleRule,
        folds: List<CrucibleValidation.Fold>,
        config: CrucibleConfig,
    ): CrucibleEvidence {
        val hits = ArrayList<Boolean>()
        val uniqueness = ArrayList<Double>()
        var baseRateSum = 0.0
        var baseRateWeight = 0

        for (fold in folds) {
            val matched = fold.test.filter { rule.matches(it.buckets) }
            if (matched.isEmpty()) continue
            matched.forEach {
                hits += CrucibleSearch.hit(it, rule, config.target)
                uniqueness += it.uniqueness
            }
            // The base rate is taken from the same held-out fold, so a rule is
            // compared against what was achievable there rather than against
            // the whole series' average.
            CrucibleSearch.baseRateOf(fold.test, config.target, rule.side)?.let {
                baseRateSum += it * matched.size
                baseRateWeight += matched.size
            }
        }
        if (hits.isEmpty()) return CrucibleEvidence.EMPTY

        return CrucibleEvidence.of(
            hits = hits,
            uniqueness = uniqueness,
            baseRate = if (baseRateWeight == 0) null else baseRateSum / baseRateWeight,
            confidence = config.confidence,
        )
    }

    private fun buildFindings(
        candidates: List<Pair<CrucibleRule, CrucibleEvidence>>,
        threshold: Double?,
        folds: List<CrucibleValidation.Fold>,
        config: CrucibleConfig,
    ): List<CrucibleFinding> {
        if (threshold == null) return emptyList()

        return candidates
            .filter { (_, evidence) ->
                val accuracy = evidence.accuracy ?: return@filter false
                val bound = evidence.accuracyLowerBound ?: return@filter false
                val lift = evidence.lift ?: return@filter false
                val p = evidence.pValue ?: return@filter false
                p <= threshold &&
                    accuracy >= config.minAccuracy &&
                    bound >= config.minAccuracy - BOUND_SLACK &&
                    lift >= config.minLiftOverBaseRate
            }
            .sortedWith(compareBy({ it.second.pValue }, { -(it.second.accuracy ?: 0.0) }))
            .take(config.maxFindings)
            .map { (rule, evidence) ->
                val inSample = CrucibleSearch.evaluate(
                    rule = rule,
                    observations = folds.flatMap { it.train }.distinctBy { it.index },
                    target = config.target,
                    baseRate = CrucibleSearch.baseRateOf(
                        folds.flatMap { it.train }.distinctBy { it.index },
                        config.target,
                        rule.side,
                    ),
                    confidence = config.confidence,
                )
                CrucibleFinding(
                    rule = rule,
                    outOfSample = evidence,
                    inSample = inSample,
                    discoveryThreshold = threshold,
                    reasons = reasonsFor(rule, evidence, inSample),
                )
            }
    }

    private fun reasonsFor(
        rule: CrucibleRule,
        outOfSample: CrucibleEvidence,
        inSample: CrucibleEvidence,
    ): List<String> = buildList {
        add(rule.description)
        add(
            "Out of sample ${percent(outOfSample.accuracy)} over ${outOfSample.samples} " +
                "(${"%.1f".format(outOfSample.effectiveSamples)} independent), base rate " +
                "${percent(outOfSample.baseRate)}",
        )
        add(
            "In sample ${percent(inSample.accuracy)} — the gap to out of sample is what a " +
                "search costs",
        )
    }

    private fun emit(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        observations: List<CrucibleObservation>,
        findings: List<CrucibleFinding>,
        config: CrucibleConfig,
    ): List<CrucibleSignal> {
        // Movement rules say a move is coming, not which way. Turning that into
        // a directional arrow would be inventing the half the rule refused to
        // predict, so they inform rather than fire.
        if (config.target == CrucibleTarget.MOVEMENT) return emptyList()

        val out = ArrayList<CrucibleSignal>()
        val cutoff = if (config.historicalSignals) 0 else candles.lastIndex - config.liveWindowBars + 1

        // A rule describes a market *state*, and a state persists. Emitting on
        // every bar the state holds put an arrow on roughly half the chart —
        // 2 593 of 5 000 bars in one measurement — which is not a signal, it is
        // a shaded region drawn one arrow at a time. Only the bar a rule first
        // becomes true is an event a trader can act on.
        var previous: String? = null
        var lastEmitted: Int? = null

        for (observation in observations) {
            val match = findings.firstOrNull { it.rule.matches(observation.buckets) }
            val entered = match != null && match.rule.key != previous
            previous = match?.rule.let { it?.key }

            if (observation.index < cutoff) continue
            if (!entered) continue
            // One trade at a time. A rule's conditions flicker in and out from
            // bar to bar, and every flicker was becoming an arrow — a thousand
            // of them across five thousand bars. A signal whose own outcome has
            // not been decided yet cannot be replaced by another; holding off
            // for the horizon is what makes each arrow a trade rather than a
            // restatement of the same market state.
            val since = lastEmitted?.let { observation.index - it }
            if (since != null && since < config.horizonBars) continue

            val finding = match ?: continue
            val side = finding.rule.side ?: continue
            val atr = CompassLabeler.atrAt(candles, observation.index, config.atrPeriod)
            if (atr <= 0.0) continue

            out += CrucibleSignal(
                symbol = symbol,
                timeframe = timeframe,
                finding = finding,
                direction = side,
                index = observation.index,
                timestamp = observation.timestamp,
                price = observation.price,
                barrier = atr * config.effectiveBarrierMultiple,
            )
            lastEmitted = observation.index
        }
        return out
    }

    private fun statusText(
        observations: List<CrucibleObservation>,
        effective: Double,
        baseRate: Double?,
        rulesTested: Int,
        findings: List<CrucibleFinding>,
        overfitting: com.foxtrader.app.domain.usecase.crucible.model.CrucibleOverfitReport,
        blocked: Boolean,
        config: CrucibleConfig,
    ): String {
        val scope = "${config.target.label}: ${observations.size} observations " +
            "(${"%.0f".format(effective)} independent), $rulesTested rules tested, " +
            "base rate ${percent(baseRate)}"
        return when {
            blocked -> "Crucible withheld — $scope. ${overfitting.verdict}."
            findings.isEmpty() ->
                "Crucible found nothing — $scope. No rule cleared ${percent(config.minAccuracy)} " +
                    "out of sample at a ${(config.falseDiscoveryRate * 100).toInt()}% false discovery rate."
            else ->
                "Crucible: ${findings.size} rules survived — $scope. " +
                    "Best ${percent(findings.first().outOfSample.accuracy)} out of sample. " +
                    "${overfitting.verdict}."
        }
    }

    private fun percent(value: Double?): String =
        if (value == null) "n/a" else "${(value * 100).toInt()}%"

    // ------------------------------------------------------------------

    /** Backtest function: one pass, since every finding is series-wide. */
    fun backtestFunction(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: CrucibleConfig = CrucibleConfig(),
    ): (List<Candle>, Int) -> StrategySignal? {
        val byIndex = analyze(symbol, timeframe, candles, config).signals.associateBy { it.index }
        return { visible, index ->
            val bar = visible.getOrNull(index)
            byIndex[index]
                ?.takeIf { bar != null && bar.timestamp == it.timestamp }
                ?.let { signal ->
                    val bullish = signal.direction == Direction.BULLISH
                    StrategySignal(
                        index = index,
                        timestamp = signal.timestamp,
                        direction = signal.direction,
                        entry = signal.price,
                        stopLoss = if (bullish) signal.price - signal.barrier else signal.price + signal.barrier,
                        takeProfit = if (bullish) signal.price + signal.barrier else signal.price - signal.barrier,
                        confidence = ((signal.finding.outOfSample.accuracy ?: 0.0) * 100).toInt(),
                        setupType = "Crucible ${signal.finding.rule.description}",
                    )
                }
        }
    }

    fun minimumBars(): Int = MIN_BARS

    companion object {
        /** A search needs enough history that folds are not tiny. */
        const val MIN_BARS = 2_000
        const val MIN_OBSERVATIONS = 400

        /**
         * The bound is allowed to fall slightly short of the accuracy target.
         *
         * Requiring the *bound* to clear the target as well would demand
         * roughly twice the evidence for the same claim, and the false
         * discovery control already limits how often a finding here is
         * spurious. The slack is small and stated rather than hidden.
         */
        private const val BOUND_SLACK = 0.10
    }
}
