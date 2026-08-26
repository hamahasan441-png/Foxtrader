package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Causal Accumulation-Manipulation-Distribution (AMD / ICT "Judas swing") engine.
 *
 * The classic teaching anchors AMD to the Asia/London/New-York session clock,
 * but the underlying idea — a compressed range, a liquidity sweep beyond it,
 * then a displaced move back through the whole range in the opposite direction
 * — is fractal: it recurs on every timeframe, not only intraday. This engine
 * therefore detects the cycle structurally (range compression relative to ATR)
 * rather than by wall-clock session, so it fires identically on M1 and on
 * D1/W1/MN. On sub-daily timeframes an accumulation range that also lines up
 * with the Asian session window earns a small confidence bonus, matching the
 * textbook case, without gating the engine to intraday charts.
 *
 * Every index consulted by [buildSignal] is at or before the confirmation bar
 * the signal is stamped on, so appending future candles can never move,
 * delete, or otherwise rewrite a previously emitted signal.
 */
@Singleton
class AccumulationManipulationDistributionEngine @Inject constructor() {

    enum class Mode { FAST, PRECISION, POWER }

    data class Config(
        val mode: Mode = Mode.PRECISION,
        val atrPeriod: Int = 14,
        /** Shortest run of closed bars that may count as an accumulation range. */
        val minAccumulationBars: Int = 6,
        /** Longest lookback scanned for a compressed range ending before the sweep. */
        val maxAccumulationBars: Int = 40,
        /** A window counts as "accumulation" when its high-low span is within this many ATRs. */
        val accumulationRangeAtrMultiple: Double = 1.6,
        val minSweepAtr: Double = 0.15,
        val minRejectionWickFraction: Double = 0.20,
        val minCloseLocation: Double = 0.55,
        /** Bars allowed between the sweep and the closing reclaim of the range. */
        val maxReclaimBars: Int = 2,
        val maxConfirmBars: Int = 4,
        val displacementAtrMultiple: Double = 0.45,
        val stopBufferAtr: Double = 0.25,
        val rewardRisk: Double = 2.0,
        val minScore: Int = 66,
        val cooldownBars: Int = 6,
        /** Only used for the intraday Asian-session confidence bonus; never gates detection. */
        val sessionOffsetMinutes: Int = 0,
        val maxSignals: Int = 160,
    ) {
        init {
            require(atrPeriod >= 2)
            require(minAccumulationBars >= 3)
            require(maxAccumulationBars >= minAccumulationBars)
            require(accumulationRangeAtrMultiple.isFinite() && accumulationRangeAtrMultiple > 0.0)
            require(minSweepAtr.isFinite() && minSweepAtr >= 0.0)
            require(minRejectionWickFraction in 0.0..1.0)
            require(minCloseLocation in 0.50..1.0)
            require(maxReclaimBars in 0..25)
            require(maxConfirmBars >= 0)
            require(displacementAtrMultiple.isFinite() && displacementAtrMultiple >= 0.0)
            require(stopBufferAtr.isFinite() && stopBufferAtr >= 0.0)
            require(rewardRisk.isFinite() && rewardRisk > 0.0)
            require(minScore in 0..100)
            require(cooldownBars >= 0)
            require(sessionOffsetMinutes in -720..840)
            require(maxSignals >= 1)
        }
    }

    data class Signal(
        val symbol: String,
        val timeframe: Timeframe,
        val direction: Direction,
        val mode: Mode,
        /** Accumulation range consumed by this cycle, for on-chart zone rendering. */
        val accumulationStartIndex: Int,
        val accumulationEndIndex: Int,
        val accumulationHigh: Double,
        val accumulationLow: Double,
        /** Manipulation bar: the liquidity sweep beyond the accumulation range. */
        val sweepIndex: Int,
        /** Distribution confirmation bar; the signal is stamped here. */
        val confirmationIndex: Int,
        val timestamp: Long,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val confidence: Int,
        val reasons: List<String>,
    )

    data class Analysis(val signals: List<Signal>)

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: Config = Config(),
    ): Analysis {
        if (candles.isEmpty()) return Analysis(emptyList())
        val minStart = maxOf(config.atrPeriod, config.minAccumulationBars) + 1
        if (candles.size <= minStart) return Analysis(emptyList())

        val atr = TechnicalIndicators.calculateATR(candles, config.atrPeriod)
        val normalizedSymbol = symbol.trim().uppercase().ifBlank { "UNKNOWN" }

        val candidates = ArrayList<Signal>()
        for (sweepIndex in minStart until candles.size) {
            val zone = findAccumulationZone(candles, atr, sweepIndex, config) ?: continue
            val rejection = sweepRejectionAt(candles, atr, sweepIndex, zone, config) ?: continue
            val reclaimIndex = findReclaim(candles, sweepIndex, zone, rejection.direction, config) ?: continue
            val confirmationIndex = findConfirmation(candles, atr, reclaimIndex, zone, rejection.direction, config)
                ?: continue
            buildSignal(
                symbol = normalizedSymbol,
                timeframe = timeframe,
                candles = candles,
                atr = atr,
                zone = zone,
                sweepIndex = sweepIndex,
                rejection = rejection,
                confirmationIndex = confirmationIndex,
                config = config,
            )?.let(candidates::add)
        }

        val sorted = candidates.sortedWith(compareBy<Signal> { it.confirmationIndex }.thenByDescending { it.confidence })
        val accepted = ArrayList<Signal>()
        val usedZones = HashSet<String>()
        for (signal in sorted) {
            val zoneKey = "${signal.accumulationStartIndex}|${signal.direction}"
            if (!usedZones.add(zoneKey)) continue
            val tooClose = accepted.any {
                it.direction == signal.direction &&
                    signal.confirmationIndex - it.confirmationIndex in 0..config.cooldownBars
            }
            if (!tooClose) accepted += signal
        }

        return Analysis(signals = accepted.takeLast(config.maxSignals))
    }

    /**
     * Return a setup only when [index] is the bar on which it first became
     * knowable, so live/replay/backtest share one causal decision path.
     */
    fun signalAt(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
        config: Config = Config(),
    ): StrategySignal? {
        if (index !in candles.indices) return null
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)
        val confirmed = analyze(symbol, timeframe, visible, config).signals
            .filter { it.confirmationIndex == visible.lastIndex }
            .maxByOrNull { it.confidence }
            ?: return null

        return StrategySignal(
            index = index,
            timestamp = visible[index].timestamp,
            direction = confirmed.direction,
            entry = confirmed.entry,
            stopLoss = confirmed.stopLoss,
            takeProfit = confirmed.takeProfit,
            confidence = confirmed.confidence,
            setupType = "AMD · ${confirmed.mode.name}",
        )
    }

    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: Config = Config(),
    ): StrategyFunction = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    private data class Zone(val startIndex: Int, val endIndex: Int, val high: Double, val low: Double)

    private data class Rejection(val direction: Direction, val wickFraction: Double, val overshootAtr: Double)

    /**
     * Longest closed-bar window ending immediately before [sweepIndex] whose
     * high-low span is tight enough (relative to ATR) to call an accumulation
     * range. A longer, tighter range is a more convincing accumulation, so the
     * largest qualifying window wins rather than the first (shortest) one.
     */
    private fun findAccumulationZone(
        candles: List<Candle>,
        atr: DoubleArray,
        sweepIndex: Int,
        config: Config,
    ): Zone? {
        val volAtr = atr.getOrNull(sweepIndex - 1)?.takeIf { it.isFinite() && it > EPSILON } ?: return null
        val scale = when (config.mode) {
            Mode.FAST -> 1.35
            Mode.PRECISION -> 1.0
            Mode.POWER -> 0.75
        }
        val maxRange = volAtr * config.accumulationRangeAtrMultiple * scale
        if (maxRange <= EPSILON) return null

        var k = config.maxAccumulationBars
        while (k >= config.minAccumulationBars) {
            val start = sweepIndex - k
            if (start < 0) {
                k--
                continue
            }
            val window = candles.subList(start, sweepIndex)
            val high = window.maxOf { it.high }
            val low = window.minOf { it.low }
            if (high.isFinite() && low.isFinite() && high - low <= maxRange) {
                return Zone(start, sweepIndex - 1, high, low)
            }
            k--
        }
        return null
    }

    /** The manipulation leg: a wick-rejected pierce beyond the accumulation range. */
    private fun sweepRejectionAt(
        candles: List<Candle>,
        atr: DoubleArray,
        sweepIndex: Int,
        zone: Zone,
        config: Config,
    ): Rejection? {
        val candle = candles[sweepIndex]
        val range = candle.high - candle.low
        if (!range.isFinite() || range <= EPSILON) return null
        val sweepAtr = atr.getOrNull(sweepIndex)?.takeIf { it.isFinite() && it > EPSILON } ?: return null

        val lowerWick = (minOf(candle.open, candle.close) - candle.low) / range
        val upperWick = (candle.high - maxOf(candle.open, candle.close)) / range
        val lowOvershoot = zone.low - candle.low
        val highOvershoot = candle.high - zone.high
        val sweptLow = lowOvershoot >= sweepAtr * config.minSweepAtr && lowerWick >= config.minRejectionWickFraction
        val sweptHigh = highOvershoot >= sweepAtr * config.minSweepAtr && upperWick >= config.minRejectionWickFraction

        return when {
            sweptLow && (!sweptHigh || lowOvershoot >= highOvershoot) ->
                Rejection(Direction.BULLISH, lowerWick, lowOvershoot / sweepAtr)
            sweptHigh -> Rejection(Direction.BEARISH, upperWick, highOvershoot / sweepAtr)
            else -> null
        }
    }

    /** First closed bar at or after [sweepIndex] that closes back inside the accumulation range. */
    private fun findReclaim(
        candles: List<Candle>,
        sweepIndex: Int,
        zone: Zone,
        direction: Direction,
        config: Config,
    ): Int? {
        val end = (sweepIndex + config.maxReclaimBars).coerceAtMost(candles.lastIndex)
        for (index in sweepIndex..end) {
            val bar = candles[index]
            val range = bar.high - bar.low
            if (!range.isFinite() || range <= EPSILON) continue
            val closeLocation = (bar.close - bar.low) / range
            val reclaimed = if (direction == Direction.BULLISH) {
                bar.close > zone.low && closeLocation >= config.minCloseLocation
            } else {
                bar.close < zone.high && closeLocation <= 1.0 - config.minCloseLocation
            }
            if (reclaimed) return index
        }
        return null
    }

    /** The distribution leg: displaced close through the *opposite* edge of the accumulation range. */
    private fun findConfirmation(
        candles: List<Candle>,
        atr: DoubleArray,
        reclaimIndex: Int,
        zone: Zone,
        direction: Direction,
        config: Config,
    ): Int? {
        val end = (reclaimIndex + config.maxConfirmBars).coerceAtMost(candles.lastIndex)
        for (index in reclaimIndex..end) {
            val candle = candles[index]
            val structure = if (direction == Direction.BULLISH) candle.close > zone.high else candle.close < zone.low
            val displacement = displacementScore(candle, atr.getOrElse(index) { Double.NaN }, config)
            val qualifies = when (config.mode) {
                Mode.FAST -> displacement >= 0.20
                Mode.PRECISION -> (structure && displacement >= 0.35) || displacement >= 0.70
                Mode.POWER -> structure && displacement >= 0.65
            }
            if (qualifies) return index
        }
        return null
    }

    private fun buildSignal(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        atr: DoubleArray,
        zone: Zone,
        sweepIndex: Int,
        rejection: Rejection,
        confirmationIndex: Int,
        config: Config,
    ): Signal? {
        val sweep = candles[sweepIndex]
        val confirmation = candles[confirmationIndex]
        val sweepAtr = atr.getOrNull(sweepIndex)?.takeIf { it.isFinite() && it > EPSILON } ?: return null
        val direction = rejection.direction

        val entry = confirmation.close
        val stop = when (direction) {
            Direction.BULLISH -> sweep.low - sweepAtr * config.stopBufferAtr
            Direction.BEARISH -> sweep.high + sweepAtr * config.stopBufferAtr
        }
        if (!entry.isFinite() || entry <= 0.0 || !stop.isFinite() || stop <= 0.0) return null
        val risk = abs(entry - stop)
        if (risk <= EPSILON) return null
        if (direction == Direction.BULLISH && stop >= entry) return null
        if (direction == Direction.BEARISH && stop <= entry) return null
        val target = if (direction == Direction.BULLISH) entry + risk * config.rewardRisk else entry - risk * config.rewardRisk
        if (!target.isFinite() || target <= 0.0) return null

        val compressionScale = when (config.mode) {
            Mode.FAST -> 1.35
            Mode.PRECISION -> 1.0
            Mode.POWER -> 0.75
        }
        val compressionThreshold = sweepAtr * config.accumulationRangeAtrMultiple * compressionScale
        val compressionQuality = if (compressionThreshold > EPSILON) {
            (1.0 - (zone.high - zone.low) / compressionThreshold).coerceIn(0.0, 1.0)
        } else 0.0
        val accumulationBars = zone.endIndex - zone.startIndex + 1
        val span = (config.maxAccumulationBars - config.minAccumulationBars).coerceAtLeast(1)
        val durationQuality = ((accumulationBars - config.minAccumulationBars).toDouble() / span).coerceIn(0.0, 1.0)
        val wickQuality = rejection.wickFraction.coerceIn(0.0, 1.0)
        val sweepDepthQuality = (rejection.overshootAtr / 3.0).coerceIn(0.0, 1.0)
        val structureBreak = if (direction == Direction.BULLISH) confirmation.close > zone.high else confirmation.close < zone.low
        val displacement = displacementScore(confirmation, atr.getOrElse(confirmationIndex) { Double.NaN }, config)
        val sessionQuality = sessionAlignmentQuality(candles[zone.startIndex].timestamp, timeframe, config.sessionOffsetMinutes)

        val score = (
            18 + (compressionQuality * 14.0).toInt() +
                10 + (durationQuality * 8.0).toInt() +
                8 + (wickQuality * 10.0).toInt() +
                (sweepDepthQuality * 10.0).toInt() +
                (if (structureBreak) 16 else 6) +
                (displacement * 12.0).toInt() +
                (sessionQuality * 4.0).toInt()
            ).coerceIn(0, 100)
        val requiredScore = maxOf(config.minScore, when (config.mode) {
            Mode.FAST -> 60
            Mode.PRECISION -> 66
            Mode.POWER -> 78
        })
        if (score < requiredScore) return null

        return Signal(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            mode = config.mode,
            accumulationStartIndex = zone.startIndex,
            accumulationEndIndex = zone.endIndex,
            accumulationHigh = zone.high,
            accumulationLow = zone.low,
            sweepIndex = sweepIndex,
            confirmationIndex = confirmationIndex,
            timestamp = confirmation.timestamp,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            confidence = score,
            reasons = listOf(
                "Accumulation range $accumulationBars bars · compression ${format1(compressionQuality * 100.0)}%",
                "Manipulation sweep ${if (direction == Direction.BULLISH) "below range low" else "above range high"} · ${format1(rejection.wickFraction * 100.0)}% wick",
                if (structureBreak) {
                    "Distribution: closed-bar break through opposite range edge"
                } else {
                    "Fast distribution confirmation"
                },
                "Displacement ${format1(displacement * 100.0)}%",
                "Locked on candle close · R:R ${format1(config.rewardRisk)}",
            ),
        )
    }

    private fun displacementScore(candle: Candle, atr: Double, config: Config): Double {
        if (!atr.isFinite() || atr <= EPSILON || candle.range <= EPSILON) return 0.0
        val bodyFraction = candle.bodySize / candle.range
        val threshold = config.displacementAtrMultiple * when (config.mode) {
            Mode.FAST -> 0.65
            Mode.PRECISION -> 1.0
            Mode.POWER -> 1.20
        }
        val rangeRatio = if (threshold <= EPSILON) 1.0 else candle.range / atr / threshold
        return minOf(bodyFraction / 0.55, rangeRatio).coerceIn(0.0, 1.0)
    }

    /**
     * Cosmetic scoring bonus only — never gates detection. Sub-daily ranges
     * that formed inside the classic Asian accumulation window (00:00-08:00,
     * shifted by [offsetMinutes]) score the full textbook-AMD bonus; anything
     * else, including every D1+ timeframe where the session clock does not
     * apply, still scores a healthy baseline so the engine stays fully usable.
     */
    private fun sessionAlignmentQuality(startTimestamp: Long, timeframe: Timeframe, offsetMinutes: Int): Double {
        if (timeframe.minutes >= Timeframe.D1.minutes) return 0.6
        val adjusted = Math.floorMod(startTimestamp + offsetMinutes * 60_000L, DAY_MS)
        val hour = adjusted / HOUR_MS
        return if (hour < 8L) 1.0 else 0.4
    }

    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
        const val EPSILON = 1e-12
    }
}
