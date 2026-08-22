package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType

/**
 * Presentation-only projection of the confirmed LiT Pro state machine onto the
 * chart's existing crash-hardened SMC / structure renderers.
 *
 * Important invariant: structural markers are stamped on confirmationIndex,
 * never on hindsight origin bars. This keeps replay/live rendering aligned with
 * the LiT engine's non-repaint contract while avoiding a second panel or a
 * duplicate execution arrow.
 */
internal data class LitProChartProjection(
    val structureBreaks: List<StructureBreak> = emptyList(),
    val zones: List<OrderBlock> = emptyList(),
)

internal fun LitAnalysis?.toLitProChartProjection(candles: List<Candle>): LitProChartProjection {
    val analysis = this ?: return LitProChartProjection()
    if (candles.isEmpty()) return LitProChartProjection()
    val context = analysis.context
    val structures = buildList {
        context.pullback.toProjectedBreak(
            candles = candles,
            fallbackDirection = context.trend,
            type = StructureBreakType.IDM,
            label = "Pullback",
        )?.let(::add)
        context.inducement.toProjectedBreak(
            candles = candles,
            fallbackDirection = context.trend,
            type = StructureBreakType.IDM,
            label = "IDM",
        )?.let(::add)
        context.bos.toProjectedBreak(
            candles = candles,
            fallbackDirection = context.trend,
            type = StructureBreakType.BOS,
            label = "BOS",
        )?.let(::add)
        context.choch.toProjectedBreak(
            candles = candles,
            fallbackDirection = context.trend,
            type = StructureBreakType.CHOCH,
            label = "CHoCH",
        )?.let(::add)

        context.poi?.let { poi ->
            val midpoint = (poi.high + poi.low) / 2.0
            val index = poi.confirmationIndex
            val candle = candles.getOrNull(index)
            if (
                candle != null &&
                midpoint.isFinite() && midpoint > 0.0 &&
                poi.low.isFinite() && poi.high.isFinite() && poi.high > poi.low
            ) {
                add(
                    StructureBreak(
                        type = StructureBreakType.IDM,
                        direction = poi.direction,
                        breakPrice = midpoint,
                        breakTimestamp = candle.timestamp,
                        breakIndex = index,
                        confirmed = true,
                        labelOverride = "POI ${poi.kind.name}",
                    )
                )
            }
        }

        context.scob?.let { scob ->
            val midpoint = (scob.high + scob.low) / 2.0
            val index = scob.confirmationIndex
            val candle = candles.getOrNull(index)
            if (
                candle != null &&
                midpoint.isFinite() && midpoint > 0.0 &&
                scob.low.isFinite() && scob.high.isFinite() && scob.high > scob.low
            ) {
                add(
                    StructureBreak(
                        type = StructureBreakType.MSS,
                        direction = scob.direction,
                        breakPrice = midpoint,
                        breakTimestamp = candle.timestamp,
                        breakIndex = index,
                        confirmed = true,
                        labelOverride = "SCOB",
                    )
                )
            }
        }
    }
        .filter { it.breakIndex in candles.indices }
        .distinctBy { Triple(it.breakIndex, it.breakPrice, it.labelOverride ?: it.type.name) }
        .sortedBy { it.breakIndex }

    val lastIndex = candles.lastIndex
    val zones = buildList {
        context.poi?.let { poi ->
            if (
                poi.originIndex in candles.indices &&
                poi.low.isFinite() && poi.high.isFinite() &&
                poi.low > 0.0 && poi.high > poi.low
            ) {
                add(
                    OrderBlock(
                        type = poi.direction.toOrderBlockType(),
                        highPrice = poi.high,
                        lowPrice = poi.low,
                        startIndex = poi.originIndex,
                        endIndex = lastIndex,
                        mitigated = poi.mitigated,
                        strength = (poi.quality / 100.0).coerceIn(0.0, 1.0),
                    )
                )
            }
        }
        context.scob?.let { scob ->
            if (
                scob.originIndex in candles.indices &&
                scob.low.isFinite() && scob.high.isFinite() &&
                scob.low > 0.0 && scob.high > scob.low
            ) {
                add(
                    OrderBlock(
                        type = scob.direction.toOrderBlockType(),
                        highPrice = scob.high,
                        lowPrice = scob.low,
                        startIndex = scob.originIndex,
                        endIndex = lastIndex,
                        mitigated = false,
                        strength = (scob.quality / 100.0).coerceIn(0.0, 1.0),
                    )
                )
            }
        }
    }.distinctBy { zone ->
        listOf(zone.type, zone.startIndex, zone.highPrice, zone.lowPrice)
    }

    return LitProChartProjection(structures, zones)
}

private fun LitLevel?.toProjectedBreak(
    candles: List<Candle>,
    fallbackDirection: Direction?,
    type: StructureBreakType,
    label: String,
): StructureBreak? {
    val level = this ?: return null
    val direction = level.direction ?: fallbackDirection ?: return null
    val index = level.confirmationIndex
    val candle = candles.getOrNull(index) ?: return null
    if (!level.price.isFinite() || level.price <= 0.0) return null
    return StructureBreak(
        type = type,
        direction = direction,
        breakPrice = level.price,
        breakTimestamp = candle.timestamp,
        breakIndex = index,
        confirmed = true,
        labelOverride = label,
    )
}

private fun Direction.toOrderBlockType(): OrderBlockType = when (this) {
    Direction.BULLISH -> OrderBlockType.BULLISH
    Direction.BEARISH -> OrderBlockType.BEARISH
}
