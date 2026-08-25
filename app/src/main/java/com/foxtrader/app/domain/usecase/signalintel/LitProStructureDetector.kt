package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitBreakMode
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitEventType
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.LitPoiKind
import com.foxtrader.app.domain.model.LitPoiZone
import com.foxtrader.app.domain.model.LitProContext
import com.foxtrader.app.domain.model.LitScob
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Confirmed-bar market-structure state machine used by LiT Pro.
 *
 * This detector deliberately owns its pivot/break chronology instead of reusing
 * a renderer-oriented BOS list. Every pivot has an explicit confirmation index,
 * and every level break is searched only after that index. Appending future bars
 * can add a new event, but it cannot move an already-confirmed event backwards.
 *
 * The detector is execution-agnostic: it maps Pullback -> IDM -> BOS/CHOCH -> POI
 * -> SCOB context. [LitEngine] applies displacement, premium/discount and risk
 * rules before a trade signal is allowed to leave the domain layer.
 */
@Singleton
class LitProStructureDetector @Inject constructor(
    private val equalLevels: EqualLevelDetector = EqualLevelDetector(),
) {

    private enum class SwingType { HIGH, LOW }
    private enum class BreakType { BOS, CHOCH }

    private data class Swing(
        val type: SwingType,
        val price: Double,
        val index: Int,
        val confirmationIndex: Int,
    )

    private data class BreakEvent(
        val type: BreakType,
        val direction: Direction,
        val level: Double,
        val originIndex: Int,
        val confirmationIndex: Int,
    )

    fun detect(candles: List<Candle>, config: LitConfig): LitProContext {
        val cfg = config.sanitized()
        val minimum = cfg.swingLeftBars + cfg.swingRightBars + 8
        if (candles.size < minimum) return LitProContext(notes = listOf("Waiting for confirmed LiT pivots"))

        val swings = detectSwings(candles, cfg)
        if (swings.size < 2) return LitProContext(notes = listOf("Not enough confirmed structural pivots"))

        val breaks = classifyBreaks(candles, swings, cfg)
        val latest = breaks.lastOrNull()
        val trend = latest?.direction ?: inferTrend(swings)

        if (latest == null) {
            val pullback = latestPullback(swings, trend, candles.lastIndex)
            return LitProContext(
                trend = trend,
                pullback = pullback,
                protectedHigh = swings.lastOrNull { it.type == SwingType.HIGH }?.price,
                protectedLow = swings.lastOrNull { it.type == SwingType.LOW }?.price,
                notes = listOf("Structure mapped; waiting for a confirmed level break"),
            )
        }

        val pullback = latestPullback(swings, latest.direction, latest.confirmationIndex)
        val idm = findInducement(candles, swings, latest, cfg)
        val lastBos = breaks.lastOrNull { it.type == BreakType.BOS && it.confirmationIndex <= latest.confirmationIndex }
        val lastChoch = breaks.lastOrNull { it.type == BreakType.CHOCH && it.confirmationIndex <= latest.confirmationIndex }
        val poi = selectPoi(candles, latest, cfg)
        val scob = poi?.let { detectScob(candles, latest.direction, it, latest.confirmationIndex, cfg) }

        return LitProContext(
            trend = trend,
            pullback = pullback,
            inducement = idm,
            bos = lastBos?.toLevel(LitEventType.BOS),
            choch = lastChoch?.toLevel(LitEventType.CHOCH),
            poi = poi,
            scob = scob,
            protectedHigh = swings.lastOrNull {
                it.type == SwingType.HIGH && it.confirmationIndex <= latest.confirmationIndex
            }?.price,
            protectedLow = swings.lastOrNull {
                it.type == SwingType.LOW && it.confirmationIndex <= latest.confirmationIndex
            }?.price,
            notes = buildList {
                add("${latest.type.name} ${latest.direction.name.lowercase()} confirmed at ${latest.confirmationIndex}")
                if (idm != null) add("IDM swept/reclaimed before structural confirmation")
                if (poi != null) add("${poi.kind.name.lowercase()} POI mapped")
                if (scob != null) add("SCOB rejection confirmed")
            },
        )
    }

    private fun detectSwings(candles: List<Candle>, cfg: LitConfig): List<Swing> {
        val result = mutableListOf<Swing>()
        val left = cfg.swingLeftBars
        val right = cfg.swingRightBars
        for (index in left until candles.size - right) {
            val candle = candles[index]
            var high = true
            var low = true
            for (offset in 1..left) {
                if (candle.high <= candles[index - offset].high) high = false
                if (candle.low >= candles[index - offset].low) low = false
            }
            for (offset in 1..right) {
                // Equal-high/low plateaus belong to their first extreme. This
                // keeps the event stable when another equal print arrives later.
                if (candle.high < candles[index + offset].high) high = false
                if (candle.low > candles[index + offset].low) low = false
            }
            val confirmation = index + right
            if (high) result += Swing(SwingType.HIGH, candle.high, index, confirmation)
            if (low) result += Swing(SwingType.LOW, candle.low, index, confirmation)
        }
        return result.sortedWith(compareBy<Swing> { it.index }.thenBy { it.type.ordinal })
    }

    private fun classifyBreaks(
        candles: List<Candle>,
        swings: List<Swing>,
        cfg: LitConfig,
    ): List<BreakEvent> {
        val last = candles.lastIndex
        val lookbackStart = (last - cfg.setupLookback + 1).coerceAtLeast(0)
        val raw = swings.mapNotNull { swing ->
            val direction = if (swing.type == SwingType.HIGH) Direction.BULLISH else Direction.BEARISH
            val start = maxOf(swing.confirmationIndex, swing.index + 1, lookbackStart)
            if (start > last) return@mapNotNull null
            val confirmation = (start..last).firstOrNull { index ->
                breaksLevel(candles[index], swing.price, direction, cfg.breakMode)
            } ?: return@mapNotNull null
            BreakEvent(BreakType.BOS, direction, swing.price, swing.index, confirmation)
        }
            // Several nested pivots can break on the same candle. The closest
            // (most recently formed) pivot is the actionable structural level.
            .groupBy { it.direction to it.confirmationIndex }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.originIndex } }
            .sortedWith(compareBy<BreakEvent> { it.confirmationIndex }.thenBy { it.originIndex })

        var trend: Direction? = null
        val classified = mutableListOf<BreakEvent>()
        for (event in raw) {
            val type = if (trend == null || trend == event.direction) BreakType.BOS else BreakType.CHOCH
            classified += event.copy(type = type)
            trend = event.direction
        }
        return classified
    }

    private fun breaksLevel(
        candle: Candle,
        level: Double,
        direction: Direction,
        mode: LitBreakMode,
    ): Boolean {
        if (!level.isFinite() || level <= 0.0 || candle.range < 0.0) return false
        return when (mode) {
            LitBreakMode.SHADOW -> when (direction) {
                Direction.BULLISH -> candle.high > level
                Direction.BEARISH -> candle.low < level
            }
            LitBreakMode.BODY -> when (direction) {
                Direction.BULLISH -> candle.close > level && candle.bodyHigh > level
                Direction.BEARISH -> candle.close < level && candle.bodyLow < level
            }
            LitBreakMode.BODY_PLUS_SWEEP -> when (direction) {
                Direction.BULLISH -> candle.high > level && candle.close > level && candle.bodyHigh > level
                Direction.BEARISH -> candle.low < level && candle.close < level && candle.bodyLow < level
            }
        }
    }

    private fun latestPullback(swings: List<Swing>, direction: Direction?, beforeIndex: Int): LitLevel? {
        val type = when (direction) {
            Direction.BULLISH -> SwingType.LOW
            Direction.BEARISH -> SwingType.HIGH
            null -> return null
        }
        val swing = swings.lastOrNull { it.type == type && it.confirmationIndex <= beforeIndex } ?: return null
        return LitLevel(
            type = LitEventType.PULLBACK,
            direction = direction,
            price = swing.price,
            originIndex = swing.index,
            confirmationIndex = swing.confirmationIndex,
        )
    }

    /**
     * Locate the inducement pool that was swept ahead of [event].
     *
     * Two sources, tried in order of how much resting liquidity they represent:
     *
     * 1. An **EQH/EQL shelf** — two or more pivots printed at effectively the
     *    same price. A flat ceiling draws breakout entries and stacks their
     *    stops in one place, so it is the larger pool and the one price is
     *    actually reaching for. This source is new; the detector previously
     *    could not see a shelf at all.
     * 2. A **single swing point**, the original behaviour, kept as the fallback
     *    for the (common) case where no shelf formed.
     *
     * The shelf is preferred only when it was swept *and* sits at least as far
     * into the direction of travel as the single-swing candidate. A shelf below
     * the nearest swing low is not the inducement for a bullish move — price
     * would have taken the nearer pool first — so preferring it would misreport
     * which liquidity was actually collected.
     */
    private fun findInducement(
        candles: List<Candle>,
        swings: List<Swing>,
        event: BreakEvent,
        cfg: LitConfig,
    ): LitLevel? {
        val single = findSingleSwingInducement(candles, swings, event, cfg)
        val shelf = findEqualLevelInducement(candles, swings, event, cfg)
        if (shelf == null) return single
        if (single == null) return shelf
        // Prefer the shelf only when it is the outer (further) pool.
        val shelfIsOuter = when (event.direction) {
            Direction.BULLISH -> shelf.price <= single.price
            Direction.BEARISH -> shelf.price >= single.price
        }
        return if (shelfIsOuter) shelf else single
    }

    private fun findEqualLevelInducement(
        candles: List<Candle>,
        swings: List<Swing>,
        event: BreakEvent,
        cfg: LitConfig,
    ): LitLevel? {
        val atr = averageRange(candles, event.confirmationIndex) ?: return null
        val tolerance = atr * EqualLevelDetector.DEFAULT_TOLERANCE_ATR_FRACTION
        if (tolerance <= 0.0) return null

        val wanted = if (event.direction == Direction.BULLISH) SwingType.LOW else SwingType.HIGH
        val earliest = (event.confirmationIndex - cfg.maxIdmToBosBars - cfg.maxBosToChochBars)
            .coerceAtLeast(0)
        // Only pivots already confirmed before the event may contribute, so the
        // shelf cannot be assembled out of bars the event could not have seen.
        val pivots = swings
            .filter { it.type == wanted && it.confirmationIndex < event.confirmationIndex }
            .map { it.index }

        val clusters = equalLevels.detect(
            candles = candles,
            pivots = pivots,
            direction = event.direction,
            tolerance = tolerance,
        )
        val cluster = equalLevels.mostRecentBefore(clusters, event.confirmationIndex)
            ?.takeIf { it.confirmationIndex >= earliest }
            ?: return null

        val sweepIndex = (cluster.confirmationIndex until event.confirmationIndex).firstOrNull { index ->
            isSweepAndReclaim(candles[index], cluster.level, event.direction)
        } ?: return null

        return LitLevel(
            type = LitEventType.IDM,
            direction = event.direction,
            price = cluster.level,
            originIndex = cluster.firstIndex,
            confirmationIndex = sweepIndex,
            swept = true,
        )
    }

    private fun findSingleSwingInducement(
        candles: List<Candle>,
        swings: List<Swing>,
        event: BreakEvent,
        cfg: LitConfig,
    ): LitLevel? {
        val wanted = if (event.direction == Direction.BULLISH) SwingType.LOW else SwingType.HIGH
        val start = (event.confirmationIndex - cfg.maxIdmToBosBars - cfg.maxBosToChochBars)
            .coerceAtLeast(0)
        return swings.asReversed().firstNotNullOfOrNull { swing ->
            if (swing.type != wanted || swing.confirmationIndex !in start until event.confirmationIndex) {
                return@firstNotNullOfOrNull null
            }
            val sweepIndex = (swing.confirmationIndex until event.confirmationIndex).firstOrNull { index ->
                isSweepAndReclaim(candles[index], swing.price, event.direction)
            } ?: return@firstNotNullOfOrNull null
            LitLevel(
                type = LitEventType.IDM,
                direction = event.direction,
                price = swing.price,
                originIndex = swing.index,
                confirmationIndex = sweepIndex,
                swept = true,
            )
        }
    }

    private fun isSweepAndReclaim(candle: Candle, level: Double, direction: Direction): Boolean =
        when (direction) {
            Direction.BULLISH -> candle.low < level && candle.close > level
            Direction.BEARISH -> candle.high > level && candle.close < level
        }

    private fun selectPoi(candles: List<Candle>, event: BreakEvent, cfg: LitConfig): LitPoiZone? {
        val start = maxOf(0, event.confirmationIndex - POI_SEARCH_BARS)
        val candidates = (start until event.confirmationIndex)
            .filter { index ->
                val candle = candles[index]
                when (event.direction) {
                    Direction.BULLISH -> !candle.isBullish
                    Direction.BEARISH -> candle.isBullish
                }
            }
        if (candidates.isEmpty()) return null

        val origin = if (cfg.followDeeperPoiCandle) {
            when (event.direction) {
                Direction.BULLISH -> candidates.minByOrNull { candles[it].low }
                Direction.BEARISH -> candidates.maxByOrNull { candles[it].high }
            }
        } else {
            candidates.lastOrNull()
        } ?: return null

        val candle = candles[origin]
        if (!candle.low.isFinite() || !candle.high.isFinite() || candle.high <= candle.low) return null
        val kind = classifyPoi(candles, origin, event)
        val quality = when (kind) {
            LitPoiKind.EXTREME -> 92
            LitPoiKind.FLIP -> 88
            LitPoiKind.BREAKER -> 84
            LitPoiKind.DECISIONAL -> 80
        }
        val mitigated = ((event.confirmationIndex + 1)..candles.lastIndex)
            .any { index -> overlaps(candles[index], candle.low, candle.high) }
        return LitPoiZone(
            kind = kind,
            direction = event.direction,
            low = candle.low,
            high = candle.high,
            originIndex = origin,
            confirmationIndex = event.confirmationIndex,
            mitigated = mitigated,
            quality = quality,
        )
    }

    private fun classifyPoi(candles: List<Candle>, origin: Int, event: BreakEvent): LitPoiKind {
        val start = maxOf(0, origin - EXTREME_WINDOW)
        val end = event.confirmationIndex.coerceAtMost(candles.lastIndex)
        val source = candles[origin]
        val extreme = when (event.direction) {
            Direction.BULLISH -> source.low <= (start..end).minOf { candles[it].low }
            Direction.BEARISH -> source.high >= (start..end).maxOf { candles[it].high }
        }
        if (extreme) return LitPoiKind.EXTREME

        val midpoint = (source.high + source.low) / 2.0
        val flipped = ((origin + 1)..end).any { index ->
            val candle = candles[index]
            when (event.direction) {
                Direction.BULLISH -> candle.low < source.low && candle.close > midpoint
                Direction.BEARISH -> candle.high > source.high && candle.close < midpoint
            }
        }
        if (flipped) return LitPoiKind.FLIP

        val sameDirectionSource = when (event.direction) {
            Direction.BULLISH -> source.isBullish
            Direction.BEARISH -> !source.isBullish
        }
        return if (sameDirectionSource) LitPoiKind.BREAKER else LitPoiKind.DECISIONAL
    }

    private fun detectScob(
        candles: List<Candle>,
        direction: Direction,
        poi: LitPoiZone,
        structureConfirmation: Int,
        cfg: LitConfig,
    ): LitScob? {
        val start = structureConfirmation + 1
        val end = minOf(candles.lastIndex, structureConfirmation + cfg.maxPoiAgeBars)
        if (start > end) return null
        val atr = averageRange(candles, structureConfirmation)
        for (index in start..end) {
            val candle = candles[index]
            if (!overlaps(candle, poi.low, poi.high) || candle.range <= 0.0) continue
            val directionalClose = when (direction) {
                Direction.BULLISH -> candle.isBullish && candle.close >= (candle.high + candle.low) / 2.0
                Direction.BEARISH -> !candle.isBullish && candle.close <= (candle.high + candle.low) / 2.0
            }
            if (!directionalClose || candle.bodySize / candle.range < MIN_SCOB_BODY_FRACTION) continue

            val adverseShadow = when (direction) {
                Direction.BULLISH -> candle.high - candle.bodyHigh
                Direction.BEARISH -> candle.bodyLow - candle.low
            }.coerceAtLeast(0.0)
            if (atr != null && adverseShadow > atr * cfg.hiddenShadowMaxAtrFraction) continue

            val bodyLow = candle.bodyLow
            val bodyHigh = candle.bodyHigh
            if (!bodyLow.isFinite() || !bodyHigh.isFinite() || bodyHigh <= bodyLow) continue
            val quality = ((candle.bodySize / candle.range) * 100.0).toInt().coerceIn(55, 96)
            return LitScob(direction, bodyLow, bodyHigh, index, index, quality)
        }
        return null
    }

    private fun averageRange(candles: List<Candle>, endIndex: Int): Double? {
        val start = (endIndex - ATR_WINDOW + 1).coerceAtLeast(0)
        val values = (start..endIndex.coerceAtMost(candles.lastIndex))
            .map { candles[it].range }
            .filter { it.isFinite() && it > 0.0 }
        return values.average().takeIf { values.isNotEmpty() && it.isFinite() && it > 0.0 }
    }

    private fun inferTrend(swings: List<Swing>): Direction? {
        val highs = swings.filter { it.type == SwingType.HIGH }.takeLast(2)
        val lows = swings.filter { it.type == SwingType.LOW }.takeLast(2)
        if (highs.size < 2 || lows.size < 2) return null
        return when {
            highs.last().price > highs.first().price && lows.last().price > lows.first().price -> Direction.BULLISH
            highs.last().price < highs.first().price && lows.last().price < lows.first().price -> Direction.BEARISH
            else -> null
        }
    }

    private fun BreakEvent.toLevel(type: LitEventType): LitLevel = LitLevel(
        type = type,
        direction = direction,
        price = level,
        originIndex = originIndex,
        confirmationIndex = confirmationIndex,
    )

    private fun overlaps(candle: Candle, low: Double, high: Double): Boolean =
        low.isFinite() && high.isFinite() && high > low && candle.low <= high && candle.high >= low

    private companion object {
        const val POI_SEARCH_BARS = 12
        const val EXTREME_WINDOW = 16
        const val ATR_WINDOW = 14
        const val MIN_SCOB_BODY_FRACTION = 0.45
    }
}
