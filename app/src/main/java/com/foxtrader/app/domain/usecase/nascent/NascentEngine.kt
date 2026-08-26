package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.nascent.confirmation.DirectPullbackConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.EngulfConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.NascentConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.SweepConfirmation
import com.foxtrader.app.domain.usecase.nascent.model.ConfirmationType
import com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel
import com.foxtrader.app.domain.usecase.nascent.model.ExternalKeyLevel
import com.foxtrader.app.domain.usecase.nascent.model.GateResult
import com.foxtrader.app.domain.usecase.nascent.model.KeyLevelType
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityCycle
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityPoint
import com.foxtrader.app.domain.usecase.nascent.model.LiquiditySide
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityType
import com.foxtrader.app.domain.usecase.nascent.model.NascentAnalysis
import com.foxtrader.app.domain.usecase.nascent.model.NascentDiagnostic
import com.foxtrader.app.domain.usecase.nascent.model.NascentGate
import com.foxtrader.app.domain.usecase.nascent.model.NascentSetup
import com.foxtrader.app.domain.usecase.nascent.model.NascentSignal
import com.foxtrader.app.domain.usecase.nascent.model.SetupType
import com.foxtrader.app.domain.usecase.nascent.model.SignalConfidence
import com.foxtrader.app.domain.usecase.nascent.model.SignalState
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.TomState
import com.foxtrader.app.domain.usecase.nascent.msu.Msu1Detector
import com.foxtrader.app.domain.usecase.nascent.msu.Msu2Detector
import com.foxtrader.app.domain.usecase.nascent.msu.Msu3Detector
import com.foxtrader.app.domain.usecase.tradepro.TimeframeResampler
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Nascent FX Primary Analysis engine.
 *
 * Implements the documented hierarchy, in order and without shortcuts:
 * ```
 *   External structure -> liquidity cycle -> key level -> price delivery
 *      -> internal structure -> setup -> entry confirmation -> locked signal
 * ```
 *
 * ### The three properties that matter
 *
 * **1. The key-level gate is hard.** An MSU-shaped geometry appearing in the
 * middle of nowhere produces nothing. No valid external location, no signal.
 *
 * **2. Nothing reads the future.** Every event carries the bar it became
 * knowable on, external levels may only be used once the external bar that
 * produced them has *closed* in internal time, and setups are matched to
 * confirmations by walking bars forward — never by inspecting a finished chart
 * and placing ideal entries retroactively.
 *
 * **3. Replay equals live.** [analyze] is a pure function of the closed-bar
 * prefix it is given, so running it over `candles[0..t]` returns exactly the
 * signals that running it over the whole series reports at bars `<= t`. That is
 * asserted directly by the engine's determinism tests, and it is what allows
 * the chart, the replay view and the backtester to share one implementation.
 *
 * The one intentional exception is [NascentConfig.historyDepthBars], which
 * bounds *how far back* results are reported so a long history cannot make the
 * walk unbounded. It never alters the content of a signal inside the window.
 */
@Singleton
class NascentEngine @Inject constructor(
    private val structureEngine: NascentStructureEngine,
    private val liquidityEngine: NascentLiquidityEngine,
    private val epaEngine: NascentEpaEngine,
    private val directPullbackEngine: NascentDirectPullbackEngine,
    private val tomEngine: NascentTomEngine,
    private val msu1: Msu1Detector,
    private val msu2: Msu2Detector,
    private val msu3: Msu3Detector,
    private val sweepConfirmation: SweepConfirmation,
    private val engulfConfirmation: EngulfConfirmation,
    private val directPullbackConfirmation: DirectPullbackConfirmation,
) {

    /** A setup waiting for one of the Nascent entry confirmations. */
    private data class Pending(val setup: NascentSetup, val keyLevel: ExternalKeyLevel)

    /**
     * Identity of one analysis request.
     *
     * [analyze] is a pure function of exactly these inputs, which is what makes
     * memoising it safe rather than merely convenient.
     */
    private data class CacheKey(
        val symbol: String,
        val timeframe: Timeframe,
        val config: NascentConfig,
        val size: Int,
        val firstTimestamp: Long,
        val lastTimestamp: Long,
        val lastCloseBits: Long,
    )

    /**
     * Bounded result cache.
     *
     * The chart re-evaluates far more often than bars close: a live feed can
     * tick many times a second while the closed-bar prefix the engine is given
     * stays byte-identical. Without this the whole history would be rebuilt on
     * every tick purely to produce the answer it just produced. The cache turns
     * the intra-bar case into a lookup, and a genuinely new closed bar still
     * misses and recomputes, so nothing is ever served stale.
     */
    private val analysisCache = object : LinkedHashMap<CacheKey, NascentAnalysis>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, NascentAnalysis>?): Boolean =
            size > MAX_CACHED_ANALYSES
    }

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: NascentConfig = NascentConfig(),
    ): NascentAnalysis {
        if (candles.isEmpty()) return NascentAnalysis.empty(timeframe, "No candles loaded")
        val cacheKey = CacheKey(
            symbol = symbol,
            timeframe = timeframe,
            config = config,
            size = candles.size,
            firstTimestamp = candles.first().timestamp,
            lastTimestamp = candles.last().timestamp,
            lastCloseBits = candles.last().close.toBits(),
        )
        synchronized(analysisCache) { analysisCache[cacheKey] }?.let { return it }
        val computed = computeAnalysis(symbol, timeframe, candles, config)
        synchronized(analysisCache) { analysisCache[cacheKey] = computed }
        return computed
    }

    private fun computeAnalysis(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: NascentConfig,
    ): NascentAnalysis {

        val externalTimeframe = config.externalTimeframe ?: NascentConfig.externalFor(timeframe)
        ?: return NascentAnalysis.empty(
            timeframe,
            "No external timeframe exists above ${timeframe.label}; Nascent requires external structure",
        )
        if (externalTimeframe.minutes <= timeframe.minutes) {
            return NascentAnalysis.empty(
                timeframe,
                "External timeframe ${externalTimeframe.label} must be higher than ${timeframe.label}",
            )
        }

        val external = closedExternalSeries(candles, timeframe, externalTimeframe)
        if (external.size < MIN_EXTERNAL_BARS) {
            return NascentAnalysis.empty(
                timeframe,
                "Only ${external.size} closed ${externalTimeframe.label} bars available; " +
                    "need $MIN_EXTERNAL_BARS for external structure",
            )
        }

        // ---- External pass: location and liquidity context -------------------
        val externalSwings = structureEngine.swings(
            external,
            config.externalSwingLeftBars,
            config.externalSwingRightBars,
        )
        val cycles = liquidityEngine.cycles(external, externalSwings, config.enableDecisionalSlq)
        val externalDuration = externalTimeframe.minutes.toLong() * MILLIS_PER_MINUTE
        val keyLevels = keyLevels(external, cycles, externalDuration, config)
        if (keyLevels.isEmpty()) {
            return NascentAnalysis(
                signals = emptyList(),
                externalTimeframe = externalTimeframe,
                internalTimeframe = timeframe,
                liquidityCycles = cycles,
                keyLevels = emptyList(),
                diagnostics = emptyList(),
                processedBars = candles.size,
                notes = listOf("No confirmed external liquidity cycle yet — no valid location to trade from"),
            )
        }

        // ---- Internal pass ---------------------------------------------------
        val internalSwings = structureEngine.swings(
            candles,
            config.internalSwingLeftBars,
            config.internalSwingRightBars,
        )
        val breaks = structureEngine.breaks(candles, internalSwings)
        val atr = TechnicalIndicators.calculateATR(candles, config.atrPeriod)

        val swingsByConfirmation = internalSwings.sortedWith(
            compareBy<StructurePoint> { it.confirmationBarIndex }.thenBy { it.pivotBarIndex },
        )
        val levelsByClose = keyLevels.sortedBy { it.externalCloseTimestamp }

        val startIndex = (candles.size - config.historyDepthBars).coerceAtLeast(0)
        val alternating = ArrayList<StructurePoint>(internalSwings.size)
        var swingCursor = 0
        var levelCursor = 0
        val usableLevels = ArrayList<ExternalKeyLevel>(keyLevels.size)
        val pending = ArrayList<Pending>()
        val signals = ArrayList<NascentSignal>()
        val diagnostics = ArrayList<NascentDiagnostic>()
        val diagnosticsFrom = (candles.size - config.liveWindowBars).coerceAtLeast(0)

        for (index in candles.indices) {
            // Advance confirmed internal structure to this bar.
            while (
                swingCursor < swingsByConfirmation.size &&
                swingsByConfirmation[swingCursor].confirmationBarIndex <= index
            ) {
                NascentLiquidityEngine.appendOrMerge(alternating, swingsByConfirmation[swingCursor])
                swingCursor++
            }
            // Admit external levels whose external bar has closed by this bar.
            val now = candles[index].timestamp
            while (
                levelCursor < levelsByClose.size &&
                levelsByClose[levelCursor].externalCloseTimestamp <= now
            ) {
                usableLevels += levelsByClose[levelCursor]
                levelCursor++
            }
            val barAtr = atr.getOrNull(index)?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            if (index < startIndex) continue

            val gates = if (config.collectDiagnostics && index >= diagnosticsFrom) {
                ArrayList<NascentGate>(GATE_CAPACITY)
            } else {
                null
            }

            // ---- Gate 1: external locations must exist ------------------------
            // Whether a setup is *at* one of them is decided by the detector,
            // against the setup's own structure, not against this bar.
            if (usableLevels.isEmpty()) {
                gates?.add(NascentGate("ExternalKeyLevel", GateResult.FAIL, "no confirmed locations"))
                recordDiagnostic(diagnostics, gates, candles, index, "No valid external location")
                expire(pending, index, config)
                continue
            }

            val pivotSnapshot = alternating.toList()
            val confirmedBreaks = breaks.filter { it.confirmationIndex <= index }
            var setupFound = false
            var epaConfirmedAnywhere = false
            var anyCandidateLevel = false
            val dpByDirection =
                HashMap<Direction, com.foxtrader.app.domain.usecase.nascent.model.DirectPullbackState?>(2)
            val contextByDirection = HashMap<Direction, NascentInternalContext>(2)

            // ---- Gate 2: setup families, evaluated per trade direction --------
            for (direction in DIRECTIONS) {
                val candidates = candidateLevels(candles, index, usableLevels, direction, config)
                if (candidates.isEmpty()) continue
                anyCandidateLevel = true
                val context = NascentInternalContext(
                    candles = candles,
                    atIndex = index,
                    externalDirection = direction,
                    candidateLevels = candidates,
                    alternatingPivots = pivotSnapshot,
                    breaks = confirmedBreaks,
                    atr = barAtr,
                    config = config,
                )
                contextByDirection[direction] = context
                val epa = epaEngine.evaluate(candles, pivotSnapshot, direction, index, config)
                val dp = directPullbackEngine.evaluate(
                    candles = candles,
                    alternatingPivots = pivotSnapshot,
                    direction = direction,
                    atIndex = index,
                    atr = barAtr,
                    config = config,
                )
                dpByDirection[direction] = dp
                val tom = tomEngine.evaluate(candles, dp, direction, index, config)
                if (epa.confirmed) epaConfirmedAnywhere = true

                for (setup in detectSetups(context, epa, dp, tom, config)) {
                    if (!config.permits(setup.evidence)) continue
                    if (setup.confirmationIndex != index) continue
                    if (setup.keyLevel == null) continue
                    if (pending.any { it.setup == setup }) continue
                    pending += Pending(setup, setup.keyLevel)
                    setupFound = true
                }
            }
            gates?.add(
                NascentGate(
                    "ExternalKeyLevel",
                    if (anyCandidateLevel) GateResult.PASS else GateResult.FAIL,
                    "${usableLevels.size} confirmed locations",
                ),
            )
            gates?.add(
                NascentGate("EPA", if (epaConfirmedAnywhere) GateResult.PASS else GateResult.FAIL, ""),
            )
            gates?.add(
                NascentGate(
                    "Setup",
                    if (setupFound) GateResult.PASS else GateResult.FAIL,
                    if (setupFound) "matched" else "no Nascent family matched",
                ),
            )

            // ---- Gate 3: entry confirmation on a closed bar ------------------
            val confirmedNow = ArrayList<Pending>(pending.size)
            for (candidate in pending) {
                if (index < candidate.setup.confirmationIndex) continue
                val direction = candidate.setup.direction
                val context = contextByDirection[direction] ?: NascentInternalContext(
                    candles = candles,
                    atIndex = index,
                    externalDirection = direction,
                    candidateLevels = listOf(candidate.keyLevel),
                    alternatingPivots = pivotSnapshot,
                    breaks = confirmedBreaks,
                    atr = barAtr,
                    config = config,
                )
                val dp = dpByDirection.getOrPut(direction) {
                    directPullbackEngine.evaluate(
                        candles = candles,
                        alternatingPivots = pivotSnapshot,
                        direction = direction,
                        atIndex = index,
                        atr = barAtr,
                        config = config,
                    )
                }
                val confirmation = confirm(context, candidate.setup, dp) ?: continue
                confirmedNow += candidate
                val signal = lockSignal(
                    symbol = symbol,
                    timeframe = timeframe,
                    externalTimeframe = externalTimeframe,
                    candles = candles,
                    setup = candidate.setup,
                    keyLevel = candidate.keyLevel,
                    confirmation = confirmation,
                    epa = candidate.setup.epa,
                    tom = candidate.setup.tom,
                    atr = barAtr,
                    config = config,
                ) ?: continue
                if (accepts(signals, signal, config)) signals += signal
            }
            pending.removeAll(confirmedNow.toSet())
            expire(pending, index, config)

            if (gates != null) {
                gates.add(
                    NascentGate(
                        "Confirmation",
                        if (confirmedNow.isEmpty()) GateResult.FAIL else GateResult.PASS,
                        if (confirmedNow.isEmpty()) "awaiting closed-bar confirmation" else "locked",
                    ),
                )
                recordDiagnostic(diagnostics, gates, candles, index, null)
            }
        }

        return NascentAnalysis(
            signals = signals.takeLast(config.maxSignals),
            externalTimeframe = externalTimeframe,
            internalTimeframe = timeframe,
            liquidityCycles = cycles,
            keyLevels = keyLevels,
            diagnostics = diagnostics,
            processedBars = candles.size - startIndex,
            notes = listOf(
                "External ${externalTimeframe.label} -> internal ${timeframe.label}",
                "${cycles.size} confirmed liquidity cycles, ${keyLevels.size} key levels",
            ),
        )
    }

    /**
     * Return a setup only when [index] is the bar on which it first became
     * knowable, so live, replay and backtest share one decision path.
     */
    fun signalAt(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
        config: NascentConfig = NascentConfig(),
    ): StrategySignal? {
        if (index !in candles.indices) return null
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)
        val confirmed = analyze(symbol, timeframe, visible, config).signals
            .filter { it.barIndex == visible.lastIndex }
            .maxByOrNull { it.score }
            ?: return null
        return StrategySignal(
            index = index,
            timestamp = visible[index].timestamp,
            direction = confirmed.direction,
            entry = confirmed.entryPrice,
            stopLoss = confirmed.invalidationPrice ?: return null,
            takeProfit = confirmed.targetPrice ?: return null,
            confidence = confirmed.score,
            setupType = "Nascent ${confirmed.setupType.name} / ${confirmed.confirmationType.name}",
        )
    }

    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: NascentConfig = NascentConfig(),
    ): StrategyFunction = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    // -------------------------------------------------------------------------
    // External context
    // -------------------------------------------------------------------------

    /**
     * External bars that have genuinely closed.
     *
     * Resampling always leaves a trailing partial bucket, and treating it as a
     * finished bar is a direct look-ahead: its high/low keep changing as the
     * internal series advances. Any bucket whose close has not been reached by
     * the last closed internal bar is dropped.
     */
    private fun closedExternalSeries(
        candles: List<Candle>,
        internal: Timeframe,
        external: Timeframe,
    ): List<Candle> {
        val resampled = TimeframeResampler.resample(candles, external)
        if (resampled.isEmpty()) return emptyList()
        val internalDuration = internal.minutes.toLong() * MILLIS_PER_MINUTE
        val externalDuration = external.minutes.toLong() * MILLIS_PER_MINUTE
        val lastInternalClose = candles.last().timestamp + internalDuration
        return resampled.filter { it.timestamp + externalDuration <= lastInternalClose }
    }

    /** Turn confirmed liquidity into the locations Nascent permits trading from. */
    private fun keyLevels(
        external: List<Candle>,
        cycles: List<LiquidityCycle>,
        externalDuration: Long,
        config: NascentConfig,
    ): List<ExternalKeyLevel> {
        val out = ArrayList<ExternalKeyLevel>(cycles.size * 4)
        for (cycle in cycles) {
            val closeTimestamp = external.getOrNull(cycle.confirmationIndex)?.let {
                it.timestamp + externalDuration
            } ?: continue
            val points = buildList {
                cycle.ilq?.let { add(it) }
                cycle.tlq?.let { add(it) }
                addAll(cycle.slq)
            }
            for (point in points) {
                val type = point.type.toKeyLevelType()
                val evidence = point.type.evidence()
                if (!config.permits(evidence)) continue
                out += ExternalKeyLevel(
                    type = type,
                    // A pool resting above price is where sells are found, and
                    // vice versa.
                    direction = if (point.side == LiquiditySide.HIGH) {
                        Direction.BEARISH
                    } else {
                        Direction.BULLISH
                    },
                    price = point.price,
                    timestamp = point.timestamp,
                    externalCloseTimestamp = closeTimestamp,
                    evidence = evidence,
                )
            }
        }
        return out.distinctBy { Triple(it.type, it.price, it.externalCloseTimestamp) }
    }

    private fun LiquidityType.toKeyLevelType(): KeyLevelType = when (this) {
        LiquidityType.ILQ -> KeyLevelType.ILQ
        LiquidityType.SLQ -> KeyLevelType.SLQ
        LiquidityType.DECISIONAL_SLQ -> KeyLevelType.DECISIONAL_SLQ
        LiquidityType.TLQ -> KeyLevelType.TLQ
    }

    /**
     * ILQ/SLQ/TLQ are named and positioned by the source. Decisional SLQ is
     * named but never defined, so it can never present itself as verified.
     */
    private fun LiquidityType.evidence(): EvidenceLevel = when (this) {
        LiquidityType.DECISIONAL_SLQ -> EvidenceLevel.UNRESOLVED
        else -> EvidenceLevel.NASCENT_VERIFIED
    }

    /**
     * External locations supporting [direction] that are still current at
     * [index], nearest first.
     *
     * These are *candidates* only. Whether a setup genuinely formed at one of
     * them is settled by [NascentInternalContext.levelReachedBetween] against
     * the setup's own structure — which is the gate that matters, and the
     * reason this method does not try to pick a winner.
     *
     * Levels older than [NascentConfig.keyLevelMaxAgeBars] are dropped, and the
     * list is bounded so a dense liquidity map cannot make the per-bar cost
     * grow with history.
     */
    private fun candidateLevels(
        candles: List<Candle>,
        index: Int,
        usable: List<ExternalKeyLevel>,
        direction: Direction,
        config: NascentConfig,
    ): List<ExternalKeyLevel> {
        if (usable.isEmpty()) return emptyList()
        val candle = candles[index]
        val oldest = candle.timestamp -
            config.keyLevelMaxAgeBars.toLong() * candles.timeframeMillis()
        return usable
            .asSequence()
            .filter { it.direction == direction }
            .filter { it.price.isFinite() && it.externalCloseTimestamp >= oldest }
            .sortedBy { abs(candle.close - it.price) }
            .take(MAX_ACTIVE_LEVELS)
            .toList()
    }

    /** Bar spacing inferred from the series itself, so no timeframe is assumed. */
    private fun List<Candle>.timeframeMillis(): Long {
        if (size < 2) return DEFAULT_BAR_MILLIS
        val delta = this[1].timestamp - this[0].timestamp
        return if (delta > 0L) delta else DEFAULT_BAR_MILLIS
    }

    // -------------------------------------------------------------------------
    // Setups and confirmation
    // -------------------------------------------------------------------------

    private fun detectSetups(
        context: NascentInternalContext,
        epa: com.foxtrader.app.domain.usecase.nascent.model.EpaState,
        dp: com.foxtrader.app.domain.usecase.nascent.model.DirectPullbackState?,
        tom: TomState,
        config: NascentConfig,
    ): List<NascentSetup> {
        val out = ArrayList<NascentSetup>(SETUP_CAPACITY)
        val one = msu1.detect(context)
        val two = msu2.detect(context)
        val three = msu3.detect(context, epa)
        one?.let { out += it }
        two?.let { out += it }
        three?.let { out += it }

        // MSU + DP refinements are distinct families in Nascent's step 2.
        if (dp?.confirmed == true) {
            one?.let { out += it.copy(type = SetupType.MSU1_DP, directPullback = dp) }
            two?.let { out += it.copy(type = SetupType.MSU2_DP, directPullback = dp) }
        }

        // EPA + DP, and EPA + DP + TOM, stand on their own without an MSU — but
        // still only at a valid external location, like every other family.
        val epaDpLevel = if (epa.confirmed && dp?.confirmed == true) {
            context.levelReachedBetween(dp.sourceLegStart, context.atIndex)
        } else {
            null
        }
        if (epa.confirmed && dp?.confirmed == true && epaDpLevel != null) {
            val base = NascentSetup(
                type = SetupType.EPA_DP,
                direction = context.externalDirection,
                originIndex = dp.sourceLegEnd,
                confirmationIndex = context.atIndex,
                protectedExtreme = if (context.externalDirection == Direction.BULLISH) {
                    dp.rangeLow
                } else {
                    dp.rangeHigh
                },
                referenceRange = null,
                keyLevel = epaDpLevel,
                epa = epa,
                directPullback = dp,
                tom = tom,
                transactions = emptyList(),
                evidence = EvidenceLevel.INFERRED_V1,
                notes = listOf(
                    "EPA confirmed with a direct pullback into the 50% zone",
                    "External ${epaDpLevel.type.name} @ ${epaDpLevel.price}",
                ),
            )
            out += base
            // Only research mode can assert a completed Transfer Of Money; the
            // completion geometry is not established anywhere else.
            if (tom == TomState.COMPLETED && config.permits(EvidenceLevel.RESEARCH_ONLY)) {
                out += base.copy(
                    type = SetupType.EPA_DP_TOM,
                    evidence = EvidenceLevel.RESEARCH_ONLY,
                    notes = base.notes + "TOM completion is experimental",
                )
            }
        }
        return out
    }

    /** Any one of the three source-listed confirmations is sufficient. */
    private fun confirm(
        context: NascentInternalContext,
        setup: NascentSetup,
        dp: com.foxtrader.app.domain.usecase.nascent.model.DirectPullbackState?,
    ): NascentConfirmation? {
        val index = context.atIndex
        val direction = setup.direction
        val reference = when (direction) {
            Direction.BULLISH -> setup.referenceRange?.low ?: setup.protectedExtreme
            Direction.BEARISH -> setup.referenceRange?.high ?: setup.protectedExtreme
        }
        reference?.let { level ->
            sweepConfirmation.detect(context.candles, level, direction, index)?.let { return it }
        }
        engulfConfirmation.detect(context.candles, direction, index, context.config)?.let { return it }
        return directPullbackConfirmation.detect(context.candles, dp, direction, index, context.config)
    }

    private fun expire(pending: MutableList<Pending>, index: Int, config: NascentConfig) {
        pending.removeAll { index - it.setup.confirmationIndex > config.maxSetupToConfirmBars }
    }

    // -------------------------------------------------------------------------
    // Locking and grading
    // -------------------------------------------------------------------------

    private fun lockSignal(
        symbol: String,
        timeframe: Timeframe,
        externalTimeframe: Timeframe,
        candles: List<Candle>,
        setup: NascentSetup,
        keyLevel: ExternalKeyLevel,
        confirmation: NascentConfirmation,
        epa: com.foxtrader.app.domain.usecase.nascent.model.EpaState?,
        tom: TomState,
        atr: Double,
        config: NascentConfig,
    ): NascentSignal? {
        val bar = candles.getOrNull(confirmation.barIndex) ?: return null
        val entry = bar.close
        if (!entry.isFinite() || entry <= 0.0) return null

        val buffer = if (atr > 0.0) atr * config.stopBufferAtr else 0.0
        val protectedExtreme = setup.protectedExtreme ?: return null
        val stop = when (setup.direction) {
            Direction.BULLISH -> minOf(protectedExtreme, bar.low) - buffer
            Direction.BEARISH -> maxOf(protectedExtreme, bar.high) + buffer
        }
        if (!stop.isFinite() || stop <= 0.0) return null
        val risk = abs(entry - stop)
        if (risk <= EPSILON) return null
        if (setup.direction == Direction.BULLISH && stop >= entry) return null
        if (setup.direction == Direction.BEARISH && stop <= entry) return null
        val target = if (setup.direction == Direction.BULLISH) {
            entry + risk * config.rewardRisk
        } else {
            entry - risk * config.rewardRisk
        }
        if (!target.isFinite() || target <= 0.0) return null

        val score = score(setup, keyLevel, confirmation, epa, tom)
        val confidence = grade(score)
        if (confidence.ordinal > config.minConfidence.ordinal) return null

        val normalized = symbol.trim().uppercase().ifBlank { "UNKNOWN" }
        return NascentSignal(
            id = "nascent_${normalized}_${timeframe.name}_${bar.timestamp}_" +
                "${setup.direction.name}_${setup.type.name}",
            symbol = normalized,
            timestamp = bar.timestamp,
            barIndex = confirmation.barIndex,
            direction = setup.direction,
            externalTimeframe = externalTimeframe,
            internalTimeframe = timeframe,
            keyLevelType = keyLevel.type,
            setupType = setup.type,
            confirmationType = confirmation.type,
            entryPrice = entry,
            invalidationPrice = stop,
            targetPrice = target,
            state = SignalState.LOCKED,
            confidence = confidence,
            score = score,
            evidence = setup.evidence,
            reasons = buildList {
                add("External ${keyLevel.type.name} @ ${keyLevel.price} (${externalTimeframe.label})")
                addAll(setup.notes)
                add("Confirmation: ${confirmation.detail}")
                add("Locked on the close of bar ${confirmation.barIndex} — non-repaint")
            },
        )
    }

    /**
     * Grades an *already valid* setup. Scoring can never rescue an invalid one:
     * every gate above has to have passed before this is reached.
     */
    private fun score(
        setup: NascentSetup,
        keyLevel: ExternalKeyLevel,
        confirmation: NascentConfirmation,
        epa: com.foxtrader.app.domain.usecase.nascent.model.EpaState?,
        tom: TomState,
    ): Int {
        var total = BASE_SCORE
        total += when (keyLevel.type) {
            KeyLevelType.ILQ, KeyLevelType.TLQ -> 14
            KeyLevelType.EPA_DP, KeyLevelType.EPA_DP_TOM -> 12
            KeyLevelType.DECISIONAL_SLQ -> 8
            KeyLevelType.SLQ -> 6
        }
        if (keyLevel.fresh) total += 4
        // Nascent states EPA raises MSU3's probability; it is a quality
        // enhancer everywhere, never a validity gate.
        if (epa?.confirmed == true) total += 12
        total += ((epa?.efficiency ?: 0.0) * 8.0).toInt()
        if (setup.directPullback?.confirmed == true) total += 8
        if (tom == TomState.ACTIVE) total += 2
        total += when (confirmation.type) {
            ConfirmationType.SWEEP_OF_HIGH_LOW -> 10
            ConfirmationType.ENGULFING -> 8
            ConfirmationType.DIRECT_PULLBACK_50 -> 9
        }
        total += when (setup.evidence) {
            EvidenceLevel.NASCENT_VERIFIED -> 6
            EvidenceLevel.CORROBORATED -> 4
            EvidenceLevel.INFERRED_V1 -> 2
            EvidenceLevel.UNRESOLVED, EvidenceLevel.RESEARCH_ONLY -> 0
        }
        return total.coerceIn(0, 100)
    }

    private fun grade(score: Int): SignalConfidence = when {
        score >= A_PLUS_SCORE -> SignalConfidence.A_PLUS
        score >= A_SCORE -> SignalConfidence.A
        score >= B_SCORE -> SignalConfidence.B
        score >= WATCH_SCORE -> SignalConfidence.WATCH
        else -> SignalConfidence.INVALID
    }

    /** Deduplicates by event identity and applies the cooldown. */
    private fun accepts(
        signals: List<NascentSignal>,
        candidate: NascentSignal,
        config: NascentConfig,
    ): Boolean {
        if (signals.any { it.id == candidate.id }) return false
        return signals.none {
            it.direction == candidate.direction &&
                candidate.barIndex - it.barIndex in 0..config.cooldownBars
        }
    }

    private fun recordDiagnostic(
        diagnostics: MutableList<NascentDiagnostic>,
        gates: List<NascentGate>?,
        candles: List<Candle>,
        index: Int,
        rejected: String?,
    ) {
        if (gates == null) return
        diagnostics += NascentDiagnostic(
            barIndex = index,
            timestamp = candles[index].timestamp,
            gates = gates,
            rejectedReason = rejected,
        )
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val DEFAULT_BAR_MILLIS = 60_000L
        const val MIN_EXTERNAL_BARS = 8
        const val EPSILON = 1e-12
        const val FALLBACK_TOLERANCE_RANGE_FRACTION = 0.5
        const val BASE_SCORE = 30
        const val A_PLUS_SCORE = 85
        const val A_SCORE = 72
        const val B_SCORE = 58
        const val WATCH_SCORE = 40
        const val GATE_CAPACITY = 4
        const val SETUP_CAPACITY = 6

        /**
         * In-play external locations examined per bar. Bounded so a dense
         * liquidity map cannot make the per-bar cost grow with history.
         */
        const val MAX_ACTIVE_LEVELS = 4

        /** Distinct (symbol, timeframe, config, series) results held at once. */
        const val MAX_CACHED_ANALYSES = 12

        /** Both trade directions are evaluated at every bar. */
        val DIRECTIONS = listOf(Direction.BULLISH, Direction.BEARISH)
    }
}
