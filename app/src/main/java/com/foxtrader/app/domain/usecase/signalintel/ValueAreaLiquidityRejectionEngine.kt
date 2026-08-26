package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Causal previous-session Value Area Liquidity Rejection (VALR) engine.
 *
 * Profiles are built only from completed sessions. A signal is stamped on its
 * closed confirmation candle, so later candles cannot move or erase it. Since
 * Dukascopy exposes tick-volume rather than exchange-wide volume, the profile
 * explicitly reports whether it uses provider volume or a deterministic
 * time-at-price fallback.
 */
@Singleton
class ValueAreaLiquidityRejectionEngine @Inject constructor() {

    enum class Mode { FAST, PRECISION, POWER }
    enum class Edge { VAL, VAH }
    enum class ProfileQuality { PROVIDER_VOLUME_PROXY, TIME_AT_PRICE }

    data class Config(
        val mode: Mode = Mode.PRECISION,
        val profileBins: Int = 48,
        val valueAreaPercent: Double = 0.70,
        val minPreviousSessionBars: Int = 24,
        val atrPeriod: Int = 14,
        val swingLeft: Int = 2,
        val swingRight: Int = 2,
        val liquidityLookback: Int = 30,
        val poolToleranceAtr: Double = 0.30,
        val minSweepAtr: Double = 0.12,
        val minWickFraction: Double = 0.42,
        val minCloseLocation: Double = 0.62,
        val volumeLookback: Int = 20,
        val volumeSpikeMultiple: Double = 1.15,
        val structureLookback: Int = 5,
        val maxConfirmBars: Int = 2,
        val displacementAtrMultiple: Double = 0.70,
        val stopBufferAtr: Double = 0.20,
        val minPocRewardRisk: Double = 1.50,
        val minScore: Int = 78,
        val cooldownBars: Int = 8,
        val sessionOffsetMinutes: Int = 0,
        val maxSignals: Int = 160,
    ) {
        init {
            require(profileBins in 12..200)
            require(valueAreaPercent in 0.50..0.90)
            require(minPreviousSessionBars >= 4)
            require(atrPeriod >= 2)
            require(swingLeft >= 1 && swingRight >= 1)
            require(liquidityLookback >= 3)
            require(poolToleranceAtr.isFinite() && poolToleranceAtr >= 0.0)
            require(minSweepAtr.isFinite() && minSweepAtr >= 0.0)
            require(minWickFraction in 0.0..1.0)
            require(minCloseLocation in 0.50..1.0)
            require(volumeLookback >= 2)
            require(volumeSpikeMultiple.isFinite() && volumeSpikeMultiple >= 0.0)
            require(structureLookback >= 1 && maxConfirmBars >= 0)
            require(displacementAtrMultiple.isFinite() && displacementAtrMultiple >= 0.0)
            require(stopBufferAtr.isFinite() && stopBufferAtr >= 0.0)
            require(minPocRewardRisk.isFinite() && minPocRewardRisk > 0.0)
            require(minScore in 0..100 && cooldownBars >= 0 && maxSignals >= 1)
            require(sessionOffsetMinutes in -720..840)
        }
    }

    data class ProfileSnapshot(
        val sessionKey: Long,
        val sourceSessionKey: Long,
        val sourceStartTimestamp: Long,
        val sourceEndTimestamp: Long,
        val appliesFromIndex: Int,
        val sessionHigh: Double,
        val sessionLow: Double,
        val poc: Double,
        val vah: Double,
        val valueAreaLow: Double,
        val totalWeight: Double,
        val volumeCoverage: Double,
        val quality: ProfileQuality,
        val bins: List<ProfileBin>,
    )

    data class ProfileBin(val low: Double, val high: Double, val weight: Double)

    data class Signal(
        val symbol: String,
        val timeframe: Timeframe,
        val direction: Direction,
        val mode: Mode,
        val edge: Edge,
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

    data class Analysis(
        val signals: List<Signal>,
        val activeProfile: ProfileSnapshot?,
        val completedSessions: Int,
    )

    private val profileCache = object : LinkedHashMap<ProfileCacheKey, CachedProfile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProfileCacheKey, CachedProfile>?): Boolean =
            size > MAX_CACHED_PROFILES
    }

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: Config = Config(),
    ): Analysis {
        if (candles.isEmpty() || timeframe.minutes >= Timeframe.D1.minutes) {
            return Analysis(emptyList(), null, 0)
        }
        val ordered = candles.withIndex().sortedBy { it.value.timestamp }
        val sorted = ordered.map { it.value }
        val sessions = groupSessions(sorted, config.sessionOffsetMinutes)
        if (sessions.size < 2) return Analysis(emptyList(), null, 0)

        val atr = TechnicalIndicators.calculateATR(sorted, config.atrPeriod)
        val pressure = pressureSeries(sorted)
        val profiles = LinkedHashMap<Long, ProfileSnapshot>()
        for (i in 1 until sessions.size) {
            val previous = sessions[i - 1]
            if (previous.candles.size < config.minPreviousSessionBars) continue
            val current = sessions[i]
            val cached = profile(previous, config)
            profiles[current.key] = cached.toSnapshot(current.key, current.startIndex)
        }

        val candidates = ArrayList<Signal>()
        for (session in sessions.drop(1)) {
            val profile = profiles[session.key] ?: continue
            var index = session.startIndex
            while (index <= session.endIndex) {
                val rejection = rejectionAt(sorted, atr, index, profile, config)
                if (rejection != null && hasLiquidityPool(sorted, atr, index, rejection, config)) {
                    val confirmation = findConfirmation(sorted, atr, index, rejection.direction, config)
                    if (confirmation != null) {
                        buildSignal(
                            symbol = symbol.trim().uppercase().ifBlank { "UNKNOWN" },
                            timeframe = timeframe,
                            candles = sorted,
                            atr = atr,
                            pressure = pressure,
                            profile = profile,
                            rejection = rejection,
                            confirmationIndex = confirmation,
                            config = config,
                        )?.let(candidates::add)
                    }
                }
                index++
            }
        }

        val accepted = ArrayList<Signal>()
        val usedEdges = HashSet<String>()
        candidates.sortedWith(compareBy<Signal> { it.confirmationIndex }.thenByDescending { it.confidence })
            .forEach { signal ->
                val sessionKey = dayKey(signal.timestamp, config.sessionOffsetMinutes)
                if (!usedEdges.add("$sessionKey|${signal.edge.name}")) return@forEach
                val coolingDown = accepted.any {
                    it.direction == signal.direction &&
                        signal.confirmationIndex - it.confirmationIndex in 0..config.cooldownBars
                }
                if (!coolingDown) accepted += signal
            }

        return Analysis(
            signals = accepted.takeLast(config.maxSignals),
            activeProfile = profiles[sessions.last().key],
            completedSessions = sessions.size - 1,
        )
    }

    private fun buildSignal(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        atr: DoubleArray,
        pressure: DoubleArray,
        profile: ProfileSnapshot,
        rejection: Rejection,
        confirmationIndex: Int,
        config: Config,
    ): Signal? {
        val sweep = candles[rejection.index]
        val confirmation = candles[confirmationIndex]
        val sweepAtr = atr.getOrNull(rejection.index)?.takeIf { it.isFinite() && it > EPSILON } ?: return null
        val entry = confirmation.close
        val stop = if (rejection.direction == Direction.BULLISH) {
            sweep.low - sweepAtr * config.stopBufferAtr
        } else {
            sweep.high + sweepAtr * config.stopBufferAtr
        }
        val risk = abs(entry - stop)
        if (risk <= EPSILON || !entry.isFinite() || !stop.isFinite()) return null
        if (rejection.direction == Direction.BULLISH && (stop >= entry || profile.poc <= entry)) return null
        if (rejection.direction == Direction.BEARISH && (stop <= entry || profile.poc >= entry)) return null
        val rewardRisk = abs(profile.poc - entry) / risk
        val requiredRr = max(config.minPocRewardRisk, when (config.mode) {
            Mode.FAST -> 1.20
            Mode.PRECISION -> 1.50
            Mode.POWER -> 2.00
        })
        if (rewardRisk < requiredRr) return null

        val absorption = absorptionScore(candles, pressure, rejection.index, profile.quality, config)
        val structure = breaksStructure(candles, rejection.index, confirmationIndex, rejection.direction, config.structureLookback)
        val displacement = displacementScore(confirmation, atr.getOrElse(confirmationIndex) { Double.NaN }, config)
        val pool = poolScore(candles, atr, rejection.index, rejection, config)
        val profileScore = min(1.0, profile.totalWeight / max(1.0, profile.bins.size.toDouble()))
        val score = (
            24.0 * rejection.quality +
                16.0 * pool +
                16.0 * absorption +
                18.0 * if (structure) 1.0 else 0.35 +
                12.0 * displacement +
                6.0 * profileScore +
                8.0 * min(1.0, rewardRisk / 3.0)
            ).toInt().coerceIn(0, 100)
        val threshold = max(config.minScore, when (config.mode) {
            Mode.FAST -> 70
            Mode.PRECISION -> 78
            Mode.POWER -> 86
        })
        if (score < threshold) return null

        return Signal(
            symbol = symbol,
            timeframe = timeframe,
            direction = rejection.direction,
            mode = config.mode,
            edge = rejection.edge,
            levelPrice = rejection.level,
            sweepIndex = rejection.index,
            confirmationIndex = confirmationIndex,
            timestamp = confirmation.timestamp,
            entry = entry,
            stopLoss = stop,
            takeProfit = profile.poc,
            confidence = score,
            reasons = listOf(
                "Previous-session ${rejection.edge.name} liquidity sweep + reclaim",
                "Confirmed liquidity pool and absorption ${formatPercent(absorption)}",
                if (structure) "Closed-bar micro structure break" else "Fast closed-bar rejection",
                "POC target ${formatPrice(profile.poc)} · R:R ${format1(rewardRisk)}",
                "${profile.quality.name.replace('_', ' ').lowercase()} · non-repaint",
            ),
        )
    }

    private data class Rejection(
        val index: Int,
        val direction: Direction,
        val edge: Edge,
        val level: Double,
        val quality: Double,
    )

    private fun rejectionAt(
        candles: List<Candle>,
        atr: DoubleArray,
        index: Int,
        profile: ProfileSnapshot,
        config: Config,
    ): Rejection? {
        val candle = candles[index]
        val range = candle.range
        val value = atr.getOrElse(index) { Double.NaN }
        if (!range.isFinite() || range <= EPSILON || !value.isFinite() || value <= EPSILON) return null
        val closeLocation = (candle.close - candle.low) / range
        val lowerWick = (min(candle.open, candle.close) - candle.low) / range
        val upperWick = (candle.high - max(candle.open, candle.close)) / range
        val bullish = candle.low <= profile.valueAreaLow - value * config.minSweepAtr &&
            candle.close > profile.valueAreaLow && lowerWick >= config.minWickFraction &&
            closeLocation >= config.minCloseLocation
        val bearish = candle.high >= profile.vah + value * config.minSweepAtr &&
            candle.close < profile.vah && upperWick >= config.minWickFraction &&
            closeLocation <= 1.0 - config.minCloseLocation
        return when {
            bullish -> Rejection(index, Direction.BULLISH, Edge.VAL, profile.valueAreaLow,
                min(1.0, (lowerWick + closeLocation) / 1.55))
            bearish -> Rejection(index, Direction.BEARISH, Edge.VAH, profile.vah,
                min(1.0, (upperWick + (1.0 - closeLocation)) / 1.55))
            else -> null
        }
    }

    private fun hasLiquidityPool(
        candles: List<Candle>,
        atr: DoubleArray,
        index: Int,
        rejection: Rejection,
        config: Config,
    ): Boolean {
        if (config.mode == Mode.FAST) return true
        return poolScore(candles, atr, index, rejection, config) >= if (config.mode == Mode.POWER) 0.65 else 0.40
    }

    private fun poolScore(
        candles: List<Candle>,
        atr: DoubleArray,
        index: Int,
        rejection: Rejection,
        config: Config,
    ): Double {
        val from = (index - config.liquidityLookback).coerceAtLeast(config.swingLeft)
        val to = (index - config.swingRight).coerceAtLeast(from - 1)
        val tolerance = atr.getOrElse(index) { 0.0 } * config.poolToleranceAtr
        if (to < from || tolerance <= 0.0) return 0.0
        var best = Double.POSITIVE_INFINITY
        for (i in from..to) {
            val isPivot = if (rejection.direction == Direction.BULLISH) {
                (i - config.swingLeft..i + config.swingRight).all { candles[it].low >= candles[i].low }
            } else {
                (i - config.swingLeft..i + config.swingRight).all { candles[it].high <= candles[i].high }
            }
            if (!isPivot) continue
            val price = if (rejection.direction == Direction.BULLISH) candles[i].low else candles[i].high
            best = min(best, abs(price - rejection.level))
        }
        return if (best.isFinite()) (1.0 - best / tolerance).coerceIn(0.0, 1.0) else 0.0
    }

    private fun findConfirmation(
        candles: List<Candle>,
        atr: DoubleArray,
        sweepIndex: Int,
        direction: Direction,
        config: Config,
    ): Int? {
        if (config.mode == Mode.FAST) return sweepIndex
        val end = (sweepIndex + config.maxConfirmBars).coerceAtMost(candles.lastIndex)
        for (index in sweepIndex..end) {
            val structure = breaksStructure(candles, sweepIndex, index, direction, config.structureLookback)
            val displacement = displacementScore(candles[index], atr.getOrElse(index) { Double.NaN }, config)
            val floor = if (config.mode == Mode.POWER) 0.82 else 0.60
            if (structure && displacement >= floor) return index
        }
        return null
    }

    private fun breaksStructure(
        candles: List<Candle>,
        sweepIndex: Int,
        confirmationIndex: Int,
        direction: Direction,
        lookback: Int,
    ): Boolean {
        val from = (sweepIndex - lookback).coerceAtLeast(0)
        if (from >= sweepIndex) return false
        val context = candles.subList(from, sweepIndex)
        return if (direction == Direction.BULLISH) candles[confirmationIndex].close > context.maxOf { it.high }
        else candles[confirmationIndex].close < context.minOf { it.low }
    }

    private fun displacementScore(candle: Candle, atr: Double, config: Config): Double {
        if (!atr.isFinite() || atr <= EPSILON || candle.range <= EPSILON) return 0.0
        val body = candle.bodySize / candle.range
        val modeScale = when (config.mode) { Mode.FAST -> 0.70; Mode.PRECISION -> 1.0; Mode.POWER -> 1.20 }
        val range = candle.range / (atr * max(EPSILON, config.displacementAtrMultiple * modeScale))
        return min(body / 0.55, range).coerceIn(0.0, 1.0)
    }

    private fun absorptionScore(
        candles: List<Candle>,
        pressure: DoubleArray,
        index: Int,
        quality: ProfileQuality,
        config: Config,
    ): Double {
        val from = (index - config.volumeLookback).coerceAtLeast(0)
        val window = candles.subList(from, index + 1)
        val positive = window.map { it.volume }.filter { it.isFinite() && it > 0.0 }
        val average = positive.dropLast(1).average().takeIf { it.isFinite() } ?: 0.0
        val spike = if (quality == ProfileQuality.PROVIDER_VOLUME_PROXY && average > EPSILON) {
            (candles[index].volume / (average * max(EPSILON, config.volumeSpikeMultiple))).coerceIn(0.0, 1.0)
        } else 0.65
        val flip = if (index > 0 && pressure[index] * pressure[index - 1] <= 0.0) 1.0 else 0.45
        return (spike * 0.60 + flip * 0.40).coerceIn(0.0, 1.0)
    }

    private fun pressureSeries(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { index ->
        val candle = candles[index]
        if (candle.range <= EPSILON) 0.0 else ((candle.close - candle.low) / candle.range * 2.0 - 1.0) *
            if (candle.volume.isFinite() && candle.volume > 0.0) candle.volume else 1.0
    }

    private data class Session(
        val key: Long,
        val startIndex: Int,
        val endIndex: Int,
        val candles: List<Candle>,
    )

    private fun groupSessions(candles: List<Candle>, offsetMinutes: Int): List<Session> {
        val groups = linkedMapOf<Long, MutableList<Pair<Int, Candle>>>()
        candles.forEachIndexed { index, candle -> groups.getOrPut(dayKey(candle.timestamp, offsetMinutes)) { arrayListOf() } += index to candle }
        return groups.map { (key, entries) -> Session(key, entries.first().first, entries.last().first, entries.map { it.second }) }
    }

    private data class ProfileCacheKey(
        val sessionKey: Long,
        val bars: Int,
        val highBits: Long,
        val lowBits: Long,
        val volumeBits: Long,
        val bins: Int,
        val valueAreaBits: Long,
    )

    private data class CachedProfile(
        val sourceSessionKey: Long,
        val sourceStartTimestamp: Long,
        val sourceEndTimestamp: Long,
        val high: Double,
        val low: Double,
        val poc: Double,
        val vah: Double,
        val valueAreaLow: Double,
        val total: Double,
        val coverage: Double,
        val quality: ProfileQuality,
        val bins: List<ProfileBin>,
    ) {
        fun toSnapshot(sessionKey: Long, appliesFromIndex: Int) = ProfileSnapshot(
            sessionKey, sourceSessionKey, sourceStartTimestamp, sourceEndTimestamp, appliesFromIndex,
            high, low, poc, vah, valueAreaLow, total, coverage, quality, bins,
        )
    }

    private fun profile(session: Session, config: Config): CachedProfile {
        val high = session.candles.maxOf { it.high }
        val low = session.candles.minOf { it.low }
        val volume = session.candles.sumOf { if (it.volume.isFinite() && it.volume > 0.0) it.volume else 0.0 }
        val key = ProfileCacheKey(session.key, session.candles.size, high.toBits(), low.toBits(), volume.toBits(),
            config.profileBins, config.valueAreaPercent.toBits())
        synchronized(profileCache) { profileCache[key]?.let { return it } }
        val built = buildProfile(session, high, low, config)
        synchronized(profileCache) { profileCache[key] = built }
        return built
    }

    private fun buildProfile(session: Session, high: Double, low: Double, config: Config): CachedProfile {
        val range = max(EPSILON, high - low)
        val step = range / config.profileBins
        val coverage = session.candles.count { it.volume.isFinite() && it.volume > 0.0 }.toDouble() / session.candles.size
        val quality = if (coverage >= MIN_VOLUME_COVERAGE) ProfileQuality.PROVIDER_VOLUME_PROXY else ProfileQuality.TIME_AT_PRICE
        val weights = DoubleArray(config.profileBins)
        session.candles.forEach { candle ->
            val first = floor((candle.low - low) / step).toInt().coerceIn(0, config.profileBins - 1)
            val last = floor((candle.high - low) / step).toInt().coerceIn(first, config.profileBins - 1)
            val count = last - first + 1
            val sourceWeight = if (quality == ProfileQuality.PROVIDER_VOLUME_PROXY) candle.volume.coerceAtLeast(0.0) else 1.0
            val share = sourceWeight / count
            for (bin in first..last) weights[bin] += share
        }
        val total = weights.sum().coerceAtLeast(EPSILON)
        val pocIndex = weights.indices.maxByOrNull { weights[it] } ?: 0
        var lower = pocIndex
        var upper = pocIndex
        var included = weights[pocIndex]
        val target = total * config.valueAreaPercent
        while (included < target && (lower > 0 || upper < weights.lastIndex)) {
            val below = if (lower > 0) weights[lower - 1] else -1.0
            val above = if (upper < weights.lastIndex) weights[upper + 1] else -1.0
            if (above > below) { upper++; included += weights[upper] } else { lower--; included += weights[lower] }
        }
        val bins = weights.indices.map { index -> ProfileBin(low + step * index, low + step * (index + 1), weights[index]) }
        return CachedProfile(
            session.key, session.candles.first().timestamp, session.candles.last().timestamp,
            high, low, low + step * (pocIndex + 0.5), low + step * (upper + 1), low + step * lower,
            total, coverage, quality, bins,
        )
    }

    private fun dayKey(timestamp: Long, offsetMinutes: Int): Long =
        Math.floorDiv(timestamp + offsetMinutes * 60_000L, DAY_MS)

    private fun formatPrice(value: Double): String = String.format(Locale.US, "%.5f", value)
    private fun formatPercent(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100.0)
    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

    private companion object {
        const val DAY_MS = 86_400_000L
        const val EPSILON = 1e-12
        const val MIN_VOLUME_COVERAGE = 0.65
        const val MAX_CACHED_PROFILES = 48
    }
}
