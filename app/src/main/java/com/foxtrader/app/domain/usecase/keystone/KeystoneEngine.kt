package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneAcceptance
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneAnalysis
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneBiasRead
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneDisplacement
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneDivergence
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneOutcome
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePool
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneRejection
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSignal
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSweep
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneTrade
import com.foxtrader.app.domain.usecase.mtf.MultiTimeframeSeries
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Keystone — liquidity sweep, SMT divergence, displacement retracement.
 *
 * The name is the stone at the top of an arch. Remove any one of the three and
 * the structure does not weaken, it falls: a sweep without divergence is an
 * ordinary reversal attempt, a divergence without a sweep is two markets
 * disagreeing about nothing in particular, and displacement without either is a
 * large candle. Only together do they describe one specific event — liquidity
 * taken by one market that the market beside it did not need to take, followed
 * by an impulse that breaks the structure the trap was built from.
 *
 * ## What it optimises
 *
 * Expectancy and drawdown. Not the win rate. This distinction runs through
 * every decision here and it is worth being blunt about why: a rule that risks
 * one to make two is wrong more than half the time when it is working
 * correctly. Tuning such a rule toward being right more often means shrinking
 * the target, and shrinking the target is how a profitable model is converted
 * into a flattering one. [KeystoneValidation] therefore computes the win rate,
 * reports it, and gives it no part in the verdict.
 *
 * ## The sequence
 *
 * 1. **Bias.** Both timeframes above the execution series must agree, and the
 *    session must be travelling the same way.
 * 2. **Location.** The move must have taken a known pool — previous day, Asian
 *    range, or a confirmed major swing.
 * 3. **SMT.** A correlated market must have failed to confirm the sweep.
 * 4. **Confirmation.** A closed candle must displace and break internal
 *    structure. Never intrabar.
 * 5. **Entry.** The first retracement into the displacement's fair value gap,
 *    or into the 50-62% band when it left none.
 * 6. **Stop.** Beyond the swept extreme, with an ATR buffer.
 * 7. **Exit.** Opposing liquidity when it is far enough away, otherwise a fixed
 *    multiple; breakeven only after a close confirms continuation.
 * 8. **Filters.** Session, spread, news, volatility, one signal per event.
 * 9. **Risk.** A fixed fraction per trade, and a hard stop after two losing
 *    trades in a day.
 *
 * ## Non-repainting, and why that is a property rather than a claim
 *
 * [analyze] is a pure function of the closed-bar prefix: run it over candles
 * truncated at bar `t` and it returns exactly what the full-series run reports
 * for bars at or before `t`. Everything that could break that is handled
 * explicitly — higher-timeframe bars are read through [MultiTimeframeSeries],
 * liquidity pools carry the bar they became knowable, the fair value gap is
 * attached one bar after the impulse rather than on it, the divergence is
 * stamped at the later of its two legs, and the daily loss counter only sees
 * trades that had already resolved.
 *
 * The one thing that is *not* a pure function of the prefix is the validation
 * report, which is a summary of the whole run by construction. It is reported
 * separately from the signals for exactly that reason.
 */
@Singleton
class KeystoneEngine @Inject constructor(
    analyzeStructure: AnalyzeMarketStructureUseCase,
) {

    private val biasStage = KeystoneBias(analyzeStructure)
    private val liquidity = KeystoneLiquidity()
    private val smt = KeystoneSmt()
    private val trigger = KeystoneTrigger()
    private val filters = KeystoneFilters()
    private val validation = KeystoneValidation()

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        peers: List<KeystonePeerSeries> = emptyList(),
        config: KeystoneConfig = KeystoneConfig(),
    ): KeystoneAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) return KeystoneAnalysis.empty(integrity.reason ?: "Invalid market data.")

        val ladder = config.timeframesFor(timeframe)
            ?: return KeystoneAnalysis.empty(
                "Keystone: no higher timeframes mapped for ${timeframe.label}.",
            )
        val higher = MultiTimeframeSeries.from(candles, timeframe, ladder.higher)
        val mid = MultiTimeframeSeries.from(candles, timeframe, ladder.mid)
        if (config.biasMode != KeystoneBiasMode.NONE && (higher.isEmpty || mid.isEmpty)) {
            return KeystoneAnalysis.empty(
                "Keystone: not enough history for ${ladder.higher.label}/${ladder.mid.label} bias.",
            )
        }

        val usablePeers = peers.filter { it.candles.size >= MIN_PEER_BARS }
        if (config.requireSmt && usablePeers.isEmpty()) {
            // Standing down is the honest answer. Dropping the requirement
            // because the data is missing would publish a different strategy
            // under the same name, and the divergence is the part of this model
            // that the primary series cannot supply on its own.
            return KeystoneAnalysis.empty(
                "Keystone: SMT is required and no trustworthy correlated market is available.",
            )
        }

        val atr = KeystoneAtr.series(candles, config.atrPeriod)
        val medianAtr = KeystoneAtr.rollingMedian(atr, config.volatilityMedianWindow)
        val pools = liquidity.pools(candles, config)
        val divergences = if (usablePeers.isEmpty()) {
            emptyList()
        } else {
            smt.detect(candles, usablePeers, config)
        }

        val run = walk(symbol, timeframe, candles, pools, divergences, atr, medianAtr, higher, mid, config)

        val trades = validation.resolve(run.signals, candles, config)
        val performance = validation.performance(trades)
        val report = validation.report(run.signals, trades, candles, config)
        val acceptance = validation.accept(performance, report, config)

        val published = publish(run.signals, candles, acceptance, config)

        return KeystoneAnalysis(
            signals = published,
            trades = trades,
            pools = pools,
            sweeps = run.sweeps,
            performance = performance,
            validation = report,
            acceptance = acceptance,
            rejections = run.rejections,
            peersUsed = usablePeers.map { it.symbol },
            note = when {
                published.isNotEmpty() -> null
                run.signals.isNotEmpty() -> "Keystone: ${run.signals.size} setups found; " +
                    "none published. ${acceptance.summary}"
                else -> "Keystone: no complete sequence formed. ${dominantRejection(run.rejections)}"
            },
        )
    }

    /**
     * The engine as a backtest strategy: one analysis, replayed by index.
     *
     * A single pass rather than one analysis per bar, and legitimate only
     * because the prefix property above is proven rather than assumed — the two
     * are equal, and this is the one that finishes. Keystone runs a bias read,
     * a liquidity scan and a divergence scan over two symbols per call, so a
     * per-bar replay of a five-thousand-bar series would not complete in any
     * useful time.
     *
     * The timestamp is checked as well as the index, so a strategy handed a
     * different slice of the same symbol reports nothing rather than reporting
     * the wrong bar's setup.
     */
    fun backtestFunction(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        peers: List<KeystonePeerSeries> = emptyList(),
        config: KeystoneConfig = KeystoneConfig(),
    ): (List<Candle>, Int) -> StrategySignal? {
        val byIndex = analyze(symbol, timeframe, candles, peers, config).signals.associateBy { it.index }
        return { visible, index ->
            val bar = visible.getOrNull(index)
            byIndex[index]
                ?.takeIf { bar != null && bar.timestamp == it.timestamp }
                ?.let { signal ->
                    StrategySignal(
                        index = index,
                        timestamp = signal.timestamp,
                        direction = signal.direction,
                        entry = signal.entry,
                        stopLoss = signal.stopLoss,
                        takeProfit = signal.takeProfit,
                        confidence = (signal.rewardMultiple * 10).toInt().coerceIn(0, 100),
                        setupType = "Keystone ${signal.sweep.pool.label}",
                    )
                }
        }
    }

    private class Run(
        val signals: List<KeystoneSignal>,
        val sweeps: List<KeystoneSweep>,
        val rejections: Map<KeystoneRejection, Int>,
    )

    /**
     * One forward pass over the series.
     *
     * The order inside a bar is deliberate and each step depends on the one
     * before it: setups armed on the previous bar's displacement are armed
     * first, fills are checked next (never on the arming bar itself, whose own
     * low or high defined the zone), displacement is then tested for sweeps
     * still waiting, and only then is a new sweep looked for. Reversing any two
     * of these would let a bar resolve a setup it also created.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun walk(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        pools: List<KeystonePool>,
        divergences: List<KeystoneDivergence>,
        atr: DoubleArray,
        medianAtr: DoubleArray,
        higher: MultiTimeframeSeries,
        mid: MultiTimeframeSeries,
        config: KeystoneConfig,
    ): Run {
        val signals = ArrayList<KeystoneSignal>()
        val sweeps = ArrayList<KeystoneSweep>()
        val rejections = HashMap<KeystoneRejection, Int>()
        fun reject(reason: KeystoneRejection) {
            rejections[reason] = (rejections[reason] ?: 0) + 1
        }

        // Liquidity that has been collected is gone. Without this a single
        // shelf re-arms on every pullback that touches it, which multiplies the
        // model's frequency without multiplying its edge.
        val consumed = ArrayList<Pair<Boolean, Double>>()
        val waiting = ArrayList<WaitingSweep>()
        val confirmed = ArrayList<ConfirmedSweep>()
        val armed = ArrayList<ArmedSetup>()
        val unresolved = ArrayList<KeystoneTrade>()

        val lossesByDay = HashMap<Int, Int>()
        val signalsByDay = HashMap<Int, Int>()

        val start = MIN_BARS.coerceAtLeast(config.atrPeriod + 1)
        for (i in start..candles.lastIndex) {
            val bar = candles[i]
            val today = filters.dayKey(bar.timestamp)

            // Trades that resolved at or before this bar become part of the
            // record the daily limit is enforced against. Anything still open
            // is invisible here, which is what keeps the limit prefix-safe.
            unresolved.removeAll { trade ->
                if (trade.exitIndex > i) return@removeAll false
                if (trade.rMultiple < 0.0 && trade.outcome != KeystoneOutcome.OPEN) {
                    val day = filters.dayKey(candles[trade.exitIndex].timestamp)
                    lossesByDay[day] = (lossesByDay[day] ?: 0) + 1
                }
                true
            }

            // 1. Arm setups whose displacement closed on the previous bar.
            val armingNow = confirmed.filter { it.displacement.index == i - 1 }
            for (entry in armingNow) {
                val pending = trigger.arm(candles, entry.sweep, entry.displacement, atr, config)
                if (pending == null) {
                    reject(KeystoneRejection.GEOMETRY)
                    continue
                }
                val opposing = liquidity.opposingPool(
                    pools, i, pending.entry, pending.direction, config,
                )?.price
                val target = trigger.target(pending, opposing, config)
                if (target == null) {
                    reject(KeystoneRejection.GEOMETRY)
                    continue
                }
                armed += ArmedSetup(pending, target, entry.biasRead)
            }
            confirmed.removeAll(armingNow.toSet())

            // 2. Fills, never on the arming bar itself.
            val settled = ArrayList<ArmedSetup>()
            for (setup in armed) {
                val pending = setup.pending
                if (i <= pending.armedIndex) continue
                if (i - pending.armedIndex > config.maxEntryWaitBars) {
                    reject(KeystoneRejection.NO_ENTRY)
                    settled += setup
                    continue
                }
                if (trigger.invalidated(pending, candles, i) && !trigger.filled(pending, candles, i)) {
                    reject(KeystoneRejection.NO_ENTRY)
                    settled += setup
                    continue
                }
                if (!trigger.filled(pending, candles, i)) continue

                settled += setup

                // The divergence is demanded here rather than at the sweep or
                // the displacement, because this is the bar the trade is
                // actually taken on and therefore the bar every piece of
                // evidence has to be knowable by. Asking earlier would reject
                // most real events for a reason that has nothing to do with the
                // market: a swing is confirmed some bars after it forms, and
                // the second leg of a divergence routinely confirms after the
                // impulse that follows the sweep.
                val divergence = smt
                    .near(divergences, pending.sweep.index, pending.direction, config)
                    ?.takeIf { it.confirmationIndex <= i }
                if (config.requireSmt && divergence == null) {
                    reject(KeystoneRejection.NO_SMT)
                    continue
                }
                val risk = abs(pending.entry - pending.stopLoss)
                val refusal = refuse(
                    bar = bar,
                    today = today,
                    entry = pending.entry,
                    risk = risk,
                    index = i,
                    atr = atr,
                    medianAtr = medianAtr,
                    lossesByDay = lossesByDay,
                    signalsByDay = signalsByDay,
                    config = config,
                )
                if (refusal != null) {
                    reject(refusal)
                    continue
                }

                val reward = abs(setup.target - pending.entry) / risk
                val signal = KeystoneSignal(
                    symbol = symbol,
                    timeframe = timeframe,
                    direction = pending.direction,
                    index = i,
                    timestamp = bar.timestamp,
                    entry = pending.entry,
                    stopLoss = pending.stopLoss,
                    takeProfit = setup.target,
                    rewardMultiple = reward,
                    riskPercent = config.riskPercent,
                    sweep = pending.sweep,
                    biasRead = setup.biasRead,
                    divergence = divergence,
                    displacement = pending.displacement,
                    session = filters.sessionAt(bar.timestamp),
                    entryFromGap = pending.fromGap,
                    reasons = reasonsFor(setup, divergence, pending.fromGap, reward),
                )
                signals += signal
                signalsByDay[today] = (signalsByDay[today] ?: 0) + 1
                unresolved += validation.resolve(listOf(signal), candles, config).first()

                if (config.oneSignalPerLiquidityEvent) {
                    // Everything else born of the same sweep goes with it.
                    settled += armed.filter { it.pending.sweep.index == pending.sweep.index }
                    confirmed.removeAll { it.sweep.index == pending.sweep.index }
                }
            }
            armed.removeAll(settled.toSet())

            // 3. Displacement for sweeps still waiting.
            val expired = ArrayList<WaitingSweep>()
            for (entry in waiting) {
                if (i - entry.sweep.index > config.maxSweepToDisplacementBars) {
                    reject(KeystoneRejection.NO_DISPLACEMENT)
                    expired += entry
                    continue
                }
                val displacement = trigger.displacementAt(candles, i, entry.sweep, atr, config)
                    ?: continue
                confirmed += ConfirmedSweep(entry.sweep, entry.biasRead, displacement)
                expired += entry
            }
            waiting.removeAll(expired.toSet())

            // 4. A new sweep on this bar.
            val active = liquidity.activeAt(pools, i, consumed, config)
            val sweep = if (active.isEmpty()) {
                null
            } else {
                liquidity.sweepAt(candles, i, active, atr.getOrElse(i) { 0.0 }, config)
            }
            if (sweep != null) {
                sweeps += sweep
                consumed += sweep.pool.aboveMarket to sweep.pool.price

                val read = biasStage.readAt(i, candles, higher, mid, config)
                when {
                    read == null -> reject(KeystoneRejection.NO_BIAS)
                    !biasStage.permits(read, sweep.direction, config) -> {
                        val structureAgrees = read.bias != Bias.NEUTRAL &&
                            (read.bias == Bias.BULLISH) == (sweep.direction == Direction.BULLISH)
                        reject(
                            if (structureAgrees) {
                                // Structure was fine, so the session was what
                                // stood it down.
                                KeystoneRejection.AGAINST_SESSION
                            } else {
                                KeystoneRejection.AGAINST_BIAS
                            },
                        )
                    }
                    else -> waiting += WaitingSweep(sweep, read)
                }
            }
        }

        return Run(signals, sweeps, rejections)
    }

    /** The first filter this fill fails, or null when it passes them all. */
    @Suppress("LongParameterList", "ReturnCount")
    private fun refuse(
        bar: Candle,
        today: Int,
        entry: Double,
        risk: Double,
        index: Int,
        atr: DoubleArray,
        medianAtr: DoubleArray,
        lossesByDay: Map<Int, Int>,
        signalsByDay: Map<Int, Int>,
        config: KeystoneConfig,
    ): KeystoneRejection? {
        if ((lossesByDay[today] ?: 0) >= config.maxDailyLosses) return KeystoneRejection.DAILY_LOSS_LIMIT
        if ((signalsByDay[today] ?: 0) >= config.maxDailySignals) return KeystoneRejection.DAILY_SIGNAL_LIMIT
        if (!filters.sessionAllowed(bar.timestamp, config)) return KeystoneRejection.OUT_OF_SESSION
        if (filters.inNewsWindow(bar.timestamp, config)) return KeystoneRejection.NEWS
        if (!filters.volatilityOk(atr, medianAtr, index, config)) return KeystoneRejection.VOLATILITY
        if (!filters.spreadOk(entry, risk, config)) return KeystoneRejection.SPREAD
        return null
    }

    private fun reasonsFor(
        setup: ArmedSetup,
        divergence: KeystoneDivergence?,
        fromGap: Boolean,
        reward: Double,
    ): List<String> = buildList {
        add("Swept ${setup.pending.sweep.pool.label}")
        add(setup.biasRead.reason)
        divergence?.let { add(it.detail) }
        add("Displacement ${"%.1f".format(setup.pending.displacement.atrMultiple)}x ATR")
        add(if (fromGap) "Entry in displacement FVG" else "Entry at 50-62% retracement")
        add("${"%.1f".format(reward)}R to target")
    }

    /**
     * Which signals reach the chart.
     *
     * When [KeystoneConfig.enforceAcceptance] is on and the record has not
     * cleared step 11, nothing is published — including setups that look
     * perfect — because the whole point of the acceptance test is that
     * individual setups are not the evidence.
     */
    private fun publish(
        signals: List<KeystoneSignal>,
        candles: List<Candle>,
        acceptance: KeystoneAcceptance,
        config: KeystoneConfig,
    ): List<KeystoneSignal> {
        if (config.enforceAcceptance && !acceptance.accepted) return emptyList()
        if (config.historicalSignals) return signals
        val cutoff = candles.lastIndex - config.liveWindowBars
        return signals.filter { it.index >= cutoff }
    }

    private fun dominantRejection(rejections: Map<KeystoneRejection, Int>): String {
        val top = rejections.maxByOrNull { it.value } ?: return "No sweep of tracked liquidity occurred."
        return "Most often: ${top.key.label} (${top.value})."
    }

    /** A sweep that passed the bias test and is waiting for its displacement. */
    private class WaitingSweep(val sweep: KeystoneSweep, val biasRead: KeystoneBiasRead)

    /** A sweep whose displacement has closed, waiting for the next bar to arm. */
    private class ConfirmedSweep(
        val sweep: KeystoneSweep,
        val biasRead: KeystoneBiasRead,
        val displacement: KeystoneDisplacement,
    )

    private class ArmedSetup(
        val pending: KeystoneTrigger.Pending,
        val target: Double,
        val biasRead: KeystoneBiasRead,
    )

    companion object {
        /**
         * Bars before the engine will run at all.
         *
         * Set by the longest thing it has to know: the correlation window the
         * divergence test measures over, plus room for the swings on either
         * side of it. Running earlier would not produce a faster first signal,
         * only an unmeasured one.
         */
        const val MIN_BARS = 220
        const val MIN_PEER_BARS = 120
    }
}
