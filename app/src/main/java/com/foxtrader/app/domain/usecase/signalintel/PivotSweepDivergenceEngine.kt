package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Causal Daily-Pivot + Liquidity-Sweep + Divergence signal engine.
 *
 * Every level comes from the previous completed trading day. A sweep belongs to
 * a confirmed RSI/order-flow pivot and the arrow is stamped only on the later
 * closed confirmation candle. Consequently appending future candles cannot
 * move, delete, or otherwise rewrite a previously emitted signal.
 */
@Singleton
class PivotSweepDivergenceEngine @Inject constructor() {

    enum class Mode { FAST, PRECISION, POWER }

    data class Config(
        val mode: Mode = Mode.PRECISION,
        val divergence: RsiOrderFlow.Config = RsiOrderFlow.Config(includeHidden = false),
        val atrPeriod: Int = 14,
        val minSweepAtr: Double = 0.15,
        val minRejectionWickFraction: Double = 0.40,
        val minCloseLocation: Double = 0.60,
        val structureLookback: Int = 5,
        val maxConfirmBars: Int = 4,
        val displacementAtrMultiple: Double = 0.80,
        val stopBufferAtr: Double = 0.25,
        val rewardRisk: Double = 2.0,
        val minScore: Int = 75,
        val cooldownBars: Int = 8,
        val sessionOffsetMinutes: Int = 0,
        val maxSignals: Int = 160,
    ) {
        init {
            require(atrPeriod >= 2)
            require(minSweepAtr.isFinite() && minSweepAtr >= 0.0)
            require(minRejectionWickFraction in 0.0..1.0)
            require(minCloseLocation in 0.0..1.0)
            require(structureLookback >= 1)
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

    enum class LevelName { PDL, S1, S2, PDH, R1, R2 }

    data class DailyLevels(
        val dayKey: Long,
        val pivot: Double,
        val previousHigh: Double,
        val previousLow: Double,
        val r1: Double,
        val r2: Double,
        val s1: Double,
        val s2: Double,
    )

    data class Signal(
        val symbol: String,
        val timeframe: Timeframe,
        val direction: Direction,
        val mode: Mode,
        val levelName: LevelName,
        val levelPrice: Double,
        val sweepIndex: Int,
        val confirmationIndex: Int,
        val timestamp: Long,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val confidence: Int,
        val reasons: List<String>,
    )

    data class Analysis(val signals: List<Signal>, val completedTradingDays: Int)

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: Config = Config(),
    ): Analysis {
        if (candles.isEmpty() || timeframe.minutes >= Timeframe.D1.minutes) return Analysis(emptyList(), 0)
        val study = RsiOrderFlow.calculate(candles, config.divergence)
        val atr = TechnicalIndicators.calculateATR(candles, config.atrPeriod)
        val levelsByDay = levelsByTradingDay(candles, config.sessionOffsetMinutes)
        if (levelsByDay.isEmpty()) return Analysis(emptyList(), completedDayCount(candles, config.sessionOffsetMinutes))

        val normalizedSymbol = symbol.trim().uppercase().ifBlank { "UNKNOWN" }
        val candidates = study.divergences.asSequence()
            .filter { !it.type.name.startsWith("HIDDEN") }
            .mapNotNull { divergence ->
                buildSignal(
                    symbol = normalizedSymbol,
                    timeframe = timeframe,
                    candles = candles,
                    atr = atr,
                    volumeCoverage = study.positiveVolumeCoverage,
                    divergence = divergence,
                    levels = levelsByDay[dayKey(candles[divergence.endIndex].timestamp, config.sessionOffsetMinutes)]
                        ?: return@mapNotNull null,
                    config = config,
                )
            }
            .sortedWith(compareBy<Signal> { it.confirmationIndex }.thenByDescending { it.confidence })
            .toList()

        val accepted = ArrayList<Signal>()
        val usedLevelDays = HashSet<String>()
        for (signal in candidates) {
            val day = dayKey(signal.timestamp, config.sessionOffsetMinutes)
            val levelKey = "$day|${signal.direction}|${signal.levelName}"
            if (!usedLevelDays.add(levelKey)) continue
            val tooClose = accepted.any {
                it.direction == signal.direction &&
                    signal.confirmationIndex - it.confirmationIndex in 0..config.cooldownBars
            }
            if (!tooClose) accepted += signal
        }

        return Analysis(
            signals = accepted.takeLast(config.maxSignals),
            completedTradingDays = completedDayCount(candles, config.sessionOffsetMinutes),
        )
    }

    private fun buildSignal(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        atr: DoubleArray,
        volumeCoverage: Double,
        divergence: RsiOrderFlow.Divergence,
        levels: DailyLevels,
        config: Config,
    ): Signal? {
        val sweepIndex = divergence.endIndex
        val sweep = candles.getOrNull(sweepIndex) ?: return null
        val sweepAtr = atr.getOrNull(sweepIndex)?.takeIf { it.isFinite() && it > EPSILON } ?: return null
        val direction = if (divergence.bullish) Direction.BULLISH else Direction.BEARISH
        val rejection = findRejection(sweep, sweepAtr, levels, direction, config) ?: return null
        val confirmationIndex = findConfirmation(candles, atr, divergence, direction, config) ?: return null
        val confirmation = candles[confirmationIndex]
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
        val target = if (direction == Direction.BULLISH) entry + risk * config.rewardRisk
            else entry - risk * config.rewardRisk
        if (!target.isFinite() || target <= 0.0) return null

        val structureBreak = breaksStructure(candles, divergence.endIndex, confirmationIndex, direction, config.structureLookback)
        val displacement = displacementScore(candles[confirmationIndex], atr[confirmationIndex], config)
        val wickQuality = rejection.wickFraction.coerceIn(0.0, 1.0)
        val divergenceQuality = (divergence.strength / 100.0).coerceIn(0.0, 1.0)
        val levelScore = when (rejection.name) {
            LevelName.PDL, LevelName.PDH -> 20
            LevelName.S2, LevelName.R2 -> 19
            LevelName.S1, LevelName.R1 -> 17
        }
        val score = (
            levelScore +
                18 + (wickQuality * 7.0).toInt() +
                10 + (divergenceQuality * 10.0).toInt() +
                (if (structureBreak) 15 else 5) +
                (displacement * 10.0).toInt() +
                (if (volumeCoverage >= 0.50) 5 else 2)
            ).coerceIn(0, 100)
        val requiredScore = maxOf(config.minScore, when (config.mode) {
            Mode.FAST -> 70
            Mode.PRECISION -> 75
            Mode.POWER -> 85
        })
        if (score < requiredScore) return null

        return Signal(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            mode = config.mode,
            levelName = rejection.name,
            levelPrice = rejection.price,
            sweepIndex = sweepIndex,
            confirmationIndex = confirmationIndex,
            timestamp = confirmation.timestamp,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            confidence = score,
            reasons = listOf(
                "Previous-day ${rejection.name} sweep + close reclaim",
                "${divergence.type.name.replace('_', ' ')} ${divergence.strength}/100",
                if (structureBreak) "Closed-bar micro structure break" else "Fast rejection confirmation",
                "Displacement ${format1(displacement * 100.0)}%",
                "Locked on candle close · R:R ${format1(config.rewardRisk)}",
            ),
        )
    }

    private data class Rejection(val name: LevelName, val price: Double, val wickFraction: Double)

    private fun findRejection(
        candle: Candle,
        atr: Double,
        levels: DailyLevels,
        direction: Direction,
        config: Config,
    ): Rejection? {
        val range = candle.high - candle.low
        if (!range.isFinite() || range <= EPSILON) return null
        val closeLocation = (candle.close - candle.low) / range
        val candidates = if (direction == Direction.BULLISH) {
            listOf(LevelName.PDL to levels.previousLow, LevelName.S1 to levels.s1, LevelName.S2 to levels.s2)
        } else {
            listOf(LevelName.PDH to levels.previousHigh, LevelName.R1 to levels.r1, LevelName.R2 to levels.r2)
        }
        return candidates.mapNotNull { (name, price) ->
            val wickFraction = if (direction == Direction.BULLISH) {
                (minOf(candle.open, candle.close) - candle.low) / range
            } else {
                (candle.high - maxOf(candle.open, candle.close)) / range
            }
            val swept = if (direction == Direction.BULLISH) {
                candle.low <= price - atr * config.minSweepAtr && candle.close > price &&
                    closeLocation >= config.minCloseLocation
            } else {
                candle.high >= price + atr * config.minSweepAtr && candle.close < price &&
                    closeLocation <= 1.0 - config.minCloseLocation
            }
            if (swept && wickFraction >= config.minRejectionWickFraction) Rejection(name, price, wickFraction)
            else null
        }.maxByOrNull { it.wickFraction }
    }

    private fun findConfirmation(
        candles: List<Candle>,
        atr: DoubleArray,
        divergence: RsiOrderFlow.Divergence,
        direction: Direction,
        config: Config,
    ): Int? {
        val start = divergence.confirmedIndex.coerceAtLeast(divergence.endIndex)
        if (start !in candles.indices) return null
        val end = (start + config.maxConfirmBars).coerceAtMost(candles.lastIndex)
        for (index in start..end) {
            val structure = breaksStructure(candles, divergence.endIndex, index, direction, config.structureLookback)
            val displacement = displacementScore(candles[index], atr.getOrElse(index) { Double.NaN }, config)
            val qualifies = when (config.mode) {
                Mode.FAST -> index == start && displacement >= 0.35
                Mode.PRECISION -> structure && displacement >= 0.65
                Mode.POWER -> structure && displacement >= 0.85
            }
            if (qualifies) return index
        }
        return null
    }

    private fun breaksStructure(
        candles: List<Candle>,
        pivotIndex: Int,
        confirmationIndex: Int,
        direction: Direction,
        lookback: Int,
    ): Boolean {
        val from = (pivotIndex - lookback).coerceAtLeast(0)
        if (from >= pivotIndex || confirmationIndex !in candles.indices) return false
        val context = candles.subList(from, pivotIndex)
        return when (direction) {
            Direction.BULLISH -> candles[confirmationIndex].close > context.maxOf { it.high }
            Direction.BEARISH -> candles[confirmationIndex].close < context.minOf { it.low }
        }
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

    internal fun levelsByTradingDay(candles: List<Candle>, offsetMinutes: Int): Map<Long, DailyLevels> {
        val days = aggregateDays(candles, offsetMinutes)
        if (days.size < 2) return emptyMap()
        val result = LinkedHashMap<Long, DailyLevels>()
        for (i in 1 until days.size) {
            result[days[i].key] = classicalLevels(days[i].key, days[i - 1].high, days[i - 1].low, days[i - 1].close)
        }
        return result
    }

    internal fun classicalLevels(dayKey: Long, high: Double, low: Double, close: Double): DailyLevels {
        val pivot = (high + low + close) / 3.0
        return DailyLevels(
            dayKey = dayKey,
            pivot = pivot,
            previousHigh = high,
            previousLow = low,
            r1 = 2.0 * pivot - low,
            r2 = pivot + (high - low),
            s1 = 2.0 * pivot - high,
            s2 = pivot - (high - low),
        )
    }

    private data class DayAggregate(val key: Long, var high: Double, var low: Double, var close: Double)

    private fun aggregateDays(candles: List<Candle>, offsetMinutes: Int): List<DayAggregate> {
        val byDay = linkedMapOf<Long, DayAggregate>()
        candles.sortedBy { it.timestamp }.forEach { candle ->
            val key = dayKey(candle.timestamp, offsetMinutes)
            val day = byDay[key]
            if (day == null) byDay[key] = DayAggregate(key, candle.high, candle.low, candle.close)
            else {
                day.high = maxOf(day.high, candle.high)
                day.low = minOf(day.low, candle.low)
                day.close = candle.close
            }
        }
        return byDay.values.toList()
    }

    private fun completedDayCount(candles: List<Candle>, offsetMinutes: Int): Int =
        (aggregateDays(candles, offsetMinutes).size - 1).coerceAtLeast(0)

    private fun dayKey(timestamp: Long, offsetMinutes: Int): Long =
        Math.floorDiv(timestamp + offsetMinutes * 60_000L, DAY_MS)

    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private companion object {
        const val DAY_MS = 86_400_000L
        const val EPSILON = 1e-12
    }
}
