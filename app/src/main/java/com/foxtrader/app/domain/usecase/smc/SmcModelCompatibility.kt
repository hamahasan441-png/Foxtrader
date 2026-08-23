package com.foxtrader.app.domain.usecase.smc

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.VolumeProfile

/**
 * Compatibility bridge between the canonical SMC domain models and the newer
 * detector implementation. Keep model shape stable for chart/rendering callers
 * while the detector uses a compact origin/match representation internally.
 *
 * These overloads deliberately have different arity from the data-class primary
 * constructors, so existing canonical constructor calls remain unambiguous.
 */
internal fun OrderBlock(
    type: OrderBlockType,
    highPrice: Double,
    lowPrice: Double,
    index: Int,
    strength: Double,
    mitigated: Boolean,
): OrderBlock = OrderBlock(
    type = type,
    highPrice = highPrice,
    lowPrice = lowPrice,
    startIndex = index,
    endIndex = index + ORDER_BLOCK_RENDER_EXTENSION_BARS,
    mitigated = mitigated,
    strength = strength,
)

internal val OrderBlock.index: Int
    get() = startIndex

internal fun LiquidityPool(
    type: LiquidityType,
    price: Double,
    indices: List<Int>,
    swept: Boolean,
    sweepIndex: Int?,
): LiquidityPool {
    val normalized = indices.distinct().sorted()
    return LiquidityPool(
        type = type,
        price = price,
        startIndex = normalized.firstOrNull() ?: 0,
        endIndex = normalized.lastOrNull() ?: 0,
        swept = swept,
        sweepIndex = sweepIndex,
    )
}

/**
 * Reconstruct the model's represented index span for detector de-duplication.
 * Exact touch indices are not persisted by the canonical domain model, so the
 * stable span is the only lossless identity available after construction.
 */
internal val LiquidityPool.indices: IntRange
    get() = startIndex..endIndex

internal val Candle.isBearish: Boolean
    get() = close < open

/** Preserve the established public API while the implementation uses the clearer name. */
fun SmcDetector.computeVolumeProfile(
    candles: List<Candle>,
    buckets: Int = 24,
): VolumeProfile = buildVolumeProfile(candles, buckets)

/**
 * Source-compatible adapter for callers/tests that still use the original
 * `tolerance` / `minTouches` named arguments. The new detector expresses the
 * tolerance as a percentage of price and has a native two-touch minimum.
 */
fun SmcDetector.detectLiquidity(
    candles: List<Candle>,
    tolerance: Double,
    minTouches: Int,
): List<LiquidityPool> {
    val safeTouches = minTouches.coerceAtLeast(2)
    return detectLiquidity(
        candles = candles,
        tolerancePercent = tolerance.coerceAtLeast(0.0),
        lookback = candles.size.coerceAtLeast(1),
    ).filter { pool ->
        safeTouches <= 2 || (pool.endIndex - pool.startIndex + 1) >= safeTouches
    }
}

private const val ORDER_BLOCK_RENDER_EXTENSION_BARS = 20
