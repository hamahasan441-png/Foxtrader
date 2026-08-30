package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.math.NormalDistribution
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneAcceptance
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneCosts
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneOutcome
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePerformance
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSignal
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneTrade
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneValidationReport
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Steps 10 and 11 — what the record has to survive, and what it has to show.
 *
 * The most important line in this file is the one that is not here: no
 * acceptance test reads the win rate. A model that wins 70% of the time at 0.4R
 * and loses 30% at 1R loses money, and it looks excellent by the only statistic
 * most strategies are ever judged on. Expectancy, profit factor and drawdown
 * decide here. The win rate is computed so it can be seen, and given no vote.
 *
 * Every figure is net of the costs in [KeystoneCosts] — spread, commission,
 * slippage on both fills, and any configured fill latency. A gross backtest is
 * not an optimistic version of a net one; it is a different strategy, and the
 * difference is routinely larger than the edge being measured.
 */
class KeystoneValidation {

    /**
     * Carry every signal forward to its resolution.
     *
     * Where one bar touches both the stop and the target, the stop is taken.
     * That is not pessimism for its own sake: without tick data the order
     * inside the bar is unknowable, and taking the favourable reading is how a
     * backtest quietly awards itself the benefit of every ambiguity it holds.
     */
    fun resolve(
        signals: List<KeystoneSignal>,
        candles: List<Candle>,
        config: KeystoneConfig,
    ): List<KeystoneTrade> = signals.map {
        resolveWith(it, candles, config, it.takeProfit, config.breakevenAfterRMultiple)
    }

    private fun resolveWith(
        signal: KeystoneSignal,
        candles: List<Candle>,
        config: KeystoneConfig,
        takeProfit: Double,
        breakevenAfterR: Double,
    ): KeystoneTrade {
        val bullish = signal.direction == Direction.BULLISH
        val risk = abs(signal.entry - signal.stopLoss)
        val fillIndex = signal.index + config.latencyBars

        if (risk <= 0.0 || fillIndex > candles.lastIndex) {
            return KeystoneTrade(signal, KeystoneOutcome.OPEN, signal.index, signal.entry, 0.0, 0)
        }

        // Latency means the planned price is not the price obtained: the fill
        // happens at whatever the market opens at once the decision has
        // travelled. At zero latency this is the planned entry.
        val basePrice = if (config.latencyBars == 0) signal.entry else candles[fillIndex].open
        val entryFill = applyCosts(basePrice, bullish, entering = true, config = config)

        var stop = signal.stopLoss
        var movedToBreakeven = false
        val last = minOf(candles.lastIndex, fillIndex + config.maxHoldBars)

        for (i in (fillIndex + 1)..last) {
            val bar = candles[i]
            val hitStop = if (bullish) bar.low <= stop else bar.high >= stop
            if (hitStop) {
                val outcome = if (movedToBreakeven) KeystoneOutcome.BREAKEVEN else KeystoneOutcome.LOSS
                return close(signal, entryFill, stop, i, fillIndex, risk, bullish, outcome, config)
            }
            val hitTarget = if (bullish) bar.high >= takeProfit else bar.low <= takeProfit
            if (hitTarget) {
                return close(signal, entryFill, takeProfit, i, fillIndex, risk, bullish, KeystoneOutcome.WIN, config)
            }

            // Breakeven only after confirmed continuation, and continuation is
            // measured on the close. A wick that reaches the trigger and
            // reverses has confirmed nothing, and moving the stop on it is how
            // an edge is converted into a run of scratches.
            if (!movedToBreakeven && breakevenAfterR > 0.0) {
                val progress = if (bullish) {
                    (bar.close - signal.entry) / risk
                } else {
                    (signal.entry - bar.close) / risk
                }
                if (progress >= breakevenAfterR) {
                    stop = signal.entry
                    movedToBreakeven = true
                }
            }
        }

        // A trade that ran out of series is not a resolved trade. Marking it
        // expired would make the answer depend on where the data happens to
        // end: the same setup would be a small loss today and a full winner
        // once another hundred bars exist, and every figure computed from it
        // would move underneath a chart that had merely scrolled.
        if (last <= fillIndex || fillIndex + config.maxHoldBars > candles.lastIndex) {
            return KeystoneTrade(signal, KeystoneOutcome.OPEN, signal.index, signal.entry, 0.0, 0)
        }
        return close(
            signal, entryFill, candles[last].close, last, fillIndex, risk, bullish,
            KeystoneOutcome.EXPIRED, config,
        )
    }

    private fun close(
        signal: KeystoneSignal,
        entryFill: Double,
        exitPrice: Double,
        exitIndex: Int,
        fillIndex: Int,
        risk: Double,
        bullish: Boolean,
        outcome: KeystoneOutcome,
        config: KeystoneConfig,
    ): KeystoneTrade {
        val exitFill = applyCosts(exitPrice, bullish, entering = false, config = config)
        val gross = if (bullish) exitFill - entryFill else entryFill - exitFill
        val commission = config.commissionFraction * (abs(entryFill) + abs(exitFill))
        val net = gross - commission
        return KeystoneTrade(
            signal = signal,
            outcome = outcome,
            exitIndex = exitIndex,
            exitPrice = exitPrice,
            rMultiple = net / risk,
            holdingBars = exitIndex - fillIndex,
        )
    }

    /** Spread and slippage, always against the trade, on both fills. */
    private fun applyCosts(
        price: Double,
        bullish: Boolean,
        entering: Boolean,
        config: KeystoneConfig,
    ): Double {
        val against = if (bullish == entering) 1.0 else -1.0
        val spread = if (entering) abs(price) * config.assumedSpreadFraction else 0.0
        val slippage = abs(price) * config.slippageFraction
        return price + against * (spread + slippage)
    }

    // --- Performance ---------------------------------------------------------

    fun performance(trades: List<KeystoneTrade>): KeystonePerformance {
        val r = trades.filter { it.outcome != KeystoneOutcome.OPEN }.map { it.rMultiple }
        return performanceOf(r)
    }

    private fun performanceOf(r: List<Double>): KeystonePerformance {
        if (r.isEmpty()) return KeystonePerformance.EMPTY
        val wins = r.count { it > 0.0 }
        val profit = r.filter { it > 0.0 }.sum()
        val loss = -r.filter { it < 0.0 }.sum()
        val mean = r.average()
        val variance = r.sumOf { (it - mean) * (it - mean) } / r.size
        return KeystonePerformance(
            trades = r.size,
            winRate = wins.toDouble() / r.size,
            expectancyR = mean,
            profitFactor = when {
                loss > 0.0 -> profit / loss
                profit > 0.0 -> UNBOUNDED_PROFIT_FACTOR
                else -> 0.0
            },
            maxDrawdownR = maxDrawdown(r),
            totalR = r.sum(),
            standardDeviationR = sqrt(variance),
        )
    }

    private fun maxDrawdown(r: List<Double>): Double {
        var equity = 0.0
        var peak = 0.0
        var worst = 0.0
        for (value in r) {
            equity += value
            if (equity > peak) peak = equity
            val drawdown = peak - equity
            if (drawdown > worst) worst = drawdown
        }
        return worst
    }

    // --- The full report -----------------------------------------------------

    /**
     * Everything step 10 asks for, over the trades this run produced.
     *
     * [signals] is passed alongside the trades because two of the tests need to
     * re-resolve the same setups under different exit rules. That is the
     * configuration space the overfitting and deflation figures are computed
     * over — the exit is the parameter a strategy like this is most often
     * tuned on, and a robustness number that varies nothing is not a
     * robustness number.
     */
    fun report(
        signals: List<KeystoneSignal>,
        trades: List<KeystoneTrade>,
        candles: List<Candle>,
        config: KeystoneConfig,
    ): KeystoneValidationReport {
        val resolved = trades.filter { it.outcome != KeystoneOutcome.OPEN }
        val costs = KeystoneCosts(
            spreadFraction = config.assumedSpreadFraction,
            commissionFraction = config.commissionFraction,
            slippageFraction = config.slippageFraction,
            latencyBars = config.latencyBars,
        )
        if (resolved.isEmpty()) {
            return KeystoneValidationReport.EMPTY.copy(
                costsApplied = costs,
                notes = listOf("No trade resolved; nothing to validate."),
            )
        }

        val returns = resolved.map { it.rMultiple }
        val notes = ArrayList<String>()

        // Purged split. The gap discards the trades whose holding period spans
        // the boundary, so no out-of-sample trade was open while an in-sample
        // one was being counted.
        val split = returns.size / 2
        val embargo = purgeCount(resolved, split)
        val inSample = performanceOf(returns.take(split))
        val outOfSample = performanceOf(returns.drop(split + embargo))
        if (embargo > 0) notes += "Purged $embargo overlapping trades at the split."

        val folds = walkForward(returns, config.validationFolds)
        val monteCarlo = monteCarlo(returns, config)

        val variants = variantReturns(signals, candles, config)
        val overfitting = overfittingProbability(variants)
        val deflated = deflatedSharpe(returns, variants)
        if (resolved.size < CSCV_BLOCKS * 2) {
            // Reported as 1.0 rather than omitted, so nothing downstream reads a
            // missing measurement as a passing one — but it is not a finding
            // about this strategy, it is a statement that the record is too
            // short for the question to be asked.
            notes += "Overfitting probability is not computable at ${resolved.size} trades; " +
                "reported at its worst value until the record reaches ${CSCV_BLOCKS * 2}."
        }

        if (resolved.size < config.minValidationTrades) {
            notes += "Sample is ${resolved.size} trades; the acceptance test asks for " +
                "${config.minValidationTrades} before the figures are evidence rather than noise."
        }

        return KeystoneValidationReport(
            inSample = inSample,
            outOfSample = outOfSample,
            walkForwardExpectancyR = folds,
            positiveFolds = folds.count { it > 0.0 },
            monteCarloDrawdownR95 = monteCarlo.first,
            monteCarloLossProbability = monteCarlo.second,
            overfittingProbability = overfitting,
            deflatedSharpe = deflated,
            costsApplied = costs,
            notes = notes,
        )
    }

    /**
     * Trades at the boundary whose holding period reaches past it.
     *
     * A trade opened before the split and closed after it was resolved by bars
     * the out-of-sample half is being judged on, so counting it in the
     * in-sample half leaks the same bars into both.
     */
    private fun purgeCount(trades: List<KeystoneTrade>, split: Int): Int {
        if (split <= 0 || split >= trades.size) return 0
        val boundary = trades[split].signal.index
        var count = 0
        while (split + count < trades.size && trades[split + count].signal.index <= boundary) count++
        val lastInSampleExit = trades.take(split).maxOfOrNull { it.exitIndex } ?: return 0
        while (split + count < trades.size && trades[split + count].signal.index <= lastInSampleExit) count++
        return count.coerceAtMost(trades.size - split)
    }

    /** Expectancy in each chronological fold, oldest first. */
    private fun walkForward(returns: List<Double>, folds: Int): List<Double> {
        if (returns.size < folds) return emptyList()
        val size = returns.size / folds
        return (0 until folds).map { fold ->
            val from = fold * size
            val to = if (fold == folds - 1) returns.size else from + size
            returns.subList(from, to).average()
        }
    }

    /**
     * Bootstrap the trade sequence and read the drawdown off the distribution.
     *
     * Resampled **with replacement**, not reshuffled. Reshuffling preserves the
     * total, so every reordering ends at the same profit and the question "how
     * often does this lose" answers itself trivially. Drawing with replacement
     * asks the question that matters instead: what does a different but equally
     * plausible run of this same edge look like.
     *
     * @return the 95th-percentile drawdown in R, and the share of runs that
     *         finished below zero.
     */
    private fun monteCarlo(returns: List<Double>, config: KeystoneConfig): Pair<Double, Double> {
        if (config.monteCarloRuns <= 0 || returns.isEmpty()) return 0.0 to 0.0
        val random = Random(config.monteCarloSeed)
        val drawdowns = DoubleArray(config.monteCarloRuns)
        var losing = 0
        val draw = DoubleArray(returns.size)
        for (run in 0 until config.monteCarloRuns) {
            var total = 0.0
            for (i in returns.indices) {
                val value = returns[random.nextInt(returns.size)]
                draw[i] = value
                total += value
            }
            drawdowns[run] = maxDrawdown(draw.toList())
            if (total < 0.0) losing++
        }
        drawdowns.sort()
        val index = (ceil(0.95 * config.monteCarloRuns).toInt() - 1).coerceIn(0, config.monteCarloRuns - 1)
        return drawdowns[index] to losing.toDouble() / config.monteCarloRuns
    }

    /**
     * The same setups resolved under a grid of exit rules.
     *
     * Four reward floors and breakeven on or off. These are the knobs a model
     * like this actually gets tuned on, so they are the ones the overfitting
     * probability should be measured across. The engine's own configuration is
     * one point in this grid rather than a privileged case.
     */
    private fun variantReturns(
        signals: List<KeystoneSignal>,
        candles: List<Candle>,
        config: KeystoneConfig,
    ): List<DoubleArray> {
        if (signals.isEmpty()) return emptyList()
        val result = ArrayList<DoubleArray>(REWARD_GRID.size * 2)
        for (reward in REWARD_GRID) {
            for (breakeven in listOf(0.0, 1.0)) {
                val values = ArrayList<Double>(signals.size)
                for (signal in signals) {
                    val risk = abs(signal.entry - signal.stopLoss)
                    if (risk <= 0.0) continue
                    val target = if (signal.direction == Direction.BULLISH) {
                        signal.entry + risk * reward
                    } else {
                        signal.entry - risk * reward
                    }
                    val trade = resolveWith(signal, candles, config, target, breakeven)
                    if (trade.outcome != KeystoneOutcome.OPEN) values += trade.rMultiple
                }
                if (values.isNotEmpty()) result += values.toDoubleArray()
            }
        }
        // Only variants that resolved the same set of trades can be compared
        // rank-for-rank on a shared split.
        val length = result.minOfOrNull { it.size } ?: return emptyList()
        return result.map { it.copyOf(length) }
    }

    /**
     * Probability of backtest overfitting, by combinatorially symmetric
     * cross-validation.
     *
     * The trade sequence is cut into blocks; every balanced split of those
     * blocks into a training and a testing half is formed; the configuration
     * that looked best on the training half is found, and its rank on the
     * testing half is recorded. The share of splits where that winner lands
     * below the median is the answer.
     *
     * The number this produces is a property of the *selection procedure*, not
     * of any one configuration. A high value does not mean the strategy is bad;
     * it means picking a configuration by past performance would not have
     * worked, and the configuration in use should therefore be justified by the
     * reasoning behind it rather than by its backtest rank.
     */
    private fun overfittingProbability(variants: List<DoubleArray>): Double {
        if (variants.size < 2) return 1.0
        val length = variants.first().size
        if (length < CSCV_BLOCKS * 2) return 1.0

        val blockSize = length / CSCV_BLOCKS
        val sums = Array(variants.size) { DoubleArray(CSCV_BLOCKS) }
        val counts = IntArray(CSCV_BLOCKS)
        for (block in 0 until CSCV_BLOCKS) {
            val from = block * blockSize
            val to = if (block == CSCV_BLOCKS - 1) length else from + blockSize
            counts[block] = to - from
            for (v in variants.indices) {
                var sum = 0.0
                for (i in from until to) sum += variants[v][i]
                sums[v][block] = sum
            }
        }

        val half = CSCV_BLOCKS / 2
        var splits = 0
        var belowMedian = 0
        for (mask in 0 until (1 shl CSCV_BLOCKS)) {
            if (Integer.bitCount(mask) != half) continue
            splits++

            val trainScore = DoubleArray(variants.size)
            val testScore = DoubleArray(variants.size)
            var trainCount = 0
            var testCount = 0
            for (block in 0 until CSCV_BLOCKS) {
                val inTrain = (mask shr block) and 1 == 1
                if (inTrain) trainCount += counts[block] else testCount += counts[block]
                for (v in variants.indices) {
                    if (inTrain) trainScore[v] += sums[v][block] else testScore[v] += sums[v][block]
                }
            }
            if (trainCount == 0 || testCount == 0) continue

            var best = 0
            for (v in variants.indices) {
                if (trainScore[v] / trainCount > trainScore[best] / trainCount) best = v
            }
            val bestTest = testScore[best] / testCount
            val worse = variants.indices.count { testScore[it] / testCount < bestTest }
            // Relative rank in [0,1]; below 0.5 means the in-sample winner was
            // in the weaker half out of sample.
            val relativeRank = worse.toDouble() / (variants.size - 1)
            if (relativeRank < 0.5) belowMedian++
        }
        return if (splits == 0) 1.0 else belowMedian.toDouble() / splits
    }

    /**
     * Sharpe ratio deflated for the number of configurations tried and for the
     * shape of the return distribution.
     *
     * A Sharpe ratio is a claim about a distribution that a trading record
     * rarely satisfies: returns here are skewed by construction, since a
     * capped-reward, fixed-risk rule produces many small identical losses and
     * fewer large wins. The deflation corrects for that shape and for the fact
     * that the best of several configurations was reported — the expected
     * maximum Sharpe among unrelated configurations is well above zero, and
     * beating zero therefore proves nothing.
     */
    private fun deflatedSharpe(returns: List<Double>, variants: List<DoubleArray>): Double {
        val n = returns.size
        if (n < MIN_SHARPE_TRADES || variants.size < 2) return 0.0
        val mean = returns.average()
        val sd = sqrt(returns.sumOf { (it - mean) * (it - mean) } / n)
        if (sd <= 0.0) return 0.0
        val sharpe = mean / sd

        val skew = returns.sumOf { val d = (it - mean) / sd; d * d * d } / n
        val kurtosis = returns.sumOf { val d = (it - mean) / sd; d * d * d * d } / n

        val trialSharpes = variants.mapNotNull { series ->
            val m = series.average()
            val s = sqrt(series.sumOf { (it - m) * (it - m) } / series.size)
            if (s > 0.0) m / s else null
        }
        if (trialSharpes.size < 2) return 0.0
        val trialMean = trialSharpes.average()
        val trialVariance = trialSharpes.sumOf { (it - trialMean) * (it - trialMean) } / trialSharpes.size
        if (trialVariance <= 0.0) return 0.0

        val trials = trialSharpes.size.toDouble()
        // Expected maximum Sharpe across independent trials (Bailey & López de
        // Prado): the bar an observed Sharpe has to clear to mean anything.
        val expectedMax = sqrt(trialVariance) * (
            (1.0 - EULER_MASCHERONI) * NormalDistribution.quantile(1.0 - 1.0 / trials) +
                EULER_MASCHERONI * NormalDistribution.quantile(1.0 - 1.0 / (trials * exp(1.0)))
            )

        val denominator = 1.0 - skew * sharpe + (kurtosis - 1.0) / 4.0 * sharpe * sharpe
        if (denominator <= 0.0) return 0.0
        val z = (sharpe - expectedMax) * sqrt(n - 1.0) / sqrt(denominator)
        return NormalDistribution.cdf(z).coerceIn(0.0, 1.0)
    }

    // --- Acceptance ----------------------------------------------------------

    /**
     * Step 11 — the verdict.
     *
     * Judged out of sample wherever an out-of-sample figure exists, on the
     * drawdown the bootstrap says to expect rather than the single one this
     * particular ordering happened to produce, and never on the win rate.
     */
    fun accept(
        performance: KeystonePerformance,
        report: KeystoneValidationReport,
        config: KeystoneConfig,
    ): KeystoneAcceptance {
        if (performance.trades == 0) return KeystoneAcceptance.UNMEASURED

        val judged = if (report.outOfSample.trades > 0) report.outOfSample else performance
        val drawdown = maxOf(report.monteCarloDrawdownR95, performance.maxDrawdownR)

        val expectancyPassed = judged.expectancyR > config.minExpectancyR
        val profitFactorPassed = judged.profitFactor > config.minProfitFactor
        val drawdownPassed = drawdown <= config.maxDrawdownR
        val samplePassed = performance.trades >= config.minValidationTrades
        val requiredFolds = ceil(report.walkForwardExpectancyR.size / 2.0).toInt()
        val stabilityPassed = report.walkForwardExpectancyR.isNotEmpty() &&
            report.positiveFolds >= requiredFolds &&
            report.overfittingProbability <= MAX_ACCEPTABLE_OVERFITTING

        val accepted = expectancyPassed && profitFactorPassed && drawdownPassed &&
            samplePassed && stabilityPassed

        val summary = buildString {
            append("Expectancy ${"%.3f".format(judged.expectancyR)}R")
            append(" · PF ${"%.2f".format(judged.profitFactor)}")
            append(" · DD ${"%.1f".format(drawdown)}R")
            append(" · ${performance.trades} trades")
            append(" · ${report.positiveFolds}/${report.walkForwardExpectancyR.size} folds positive")
            append(" · PBO ${"%.0f".format(report.overfittingProbability * 100)}%")
            if (!accepted) {
                val missing = buildList {
                    if (!expectancyPassed) add("expectancy")
                    if (!profitFactorPassed) add("profit factor")
                    if (!drawdownPassed) add("drawdown")
                    if (!samplePassed) add("sample size")
                    if (!stabilityPassed) add("stability")
                }
                append(" — short on ${missing.joinToString(", ")}")
            }
            // Reported last and deliberately outside the verdict: it is the
            // number this engine refuses to be judged on.
            append(" (win rate ${"%.0f".format(judged.winRate * 100)}%, not a criterion)")
        }

        return KeystoneAcceptance(
            accepted = accepted,
            expectancyPassed = expectancyPassed,
            profitFactorPassed = profitFactorPassed,
            drawdownPassed = drawdownPassed,
            samplePassed = samplePassed,
            stabilityPassed = stabilityPassed,
            summary = summary,
        )
    }

    private companion object {
        /** Reported in place of an infinite profit factor when nothing lost. */
        const val UNBOUNDED_PROFIT_FACTOR = 999.0
        const val MIN_SHARPE_TRADES = 20
        const val CSCV_BLOCKS = 8
        const val MAX_ACCEPTABLE_OVERFITTING = 0.5
        const val EULER_MASCHERONI = 0.5772156649015329
        val REWARD_GRID = listOf(1.5, 2.0, 2.5, 3.0)
    }
}
