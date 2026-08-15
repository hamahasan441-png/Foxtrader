package com.foxtrader.app.feature.chart.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Candle

/**
 * Read-only, equality-stable candle list wrapper for Compose-facing chart state.
 *
 * This avoids copying thousands of candles into a persistent collection on every
 * tick while still giving the UI a stable, immutable-by-contract surface.
 */
@Immutable
class CandleSeries internal constructor(
    private val backing: List<Candle>,
) : AbstractList<Candle>() {
    override val size: Int get() = backing.size

    /**
     * Cached once per immutable series so log-scale controls never scan on draw.
     *
     * `PERF` Lazy (NONE mode — only ever read from the UI thread): the eager
     * version ran an O(n) full-series scan on EVERY wrap, and a wrap happens on
     * every live tick via withComputation. Now the scan runs only when the
     * log-scale control actually reads the flag for a given series.
     */
    val supportsLogScale: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        backing.all { it.low > 0.0 && it.low.isFinite() }
    }

    override fun get(index: Int): Candle = backing[index]

    override fun equals(other: Any?): Boolean =
        other is CandleSeries && backing == other.backing

    override fun hashCode(): Int = backing.hashCode()

    companion object {
        val EMPTY = CandleSeries(emptyList())
    }
}

/**
 * Immutable-by-contract numeric overlay series for Compose state.
 *
 * The wrapped array is never copied so the chart hot path avoids extra
 * allocations; callers must therefore treat the source array as frozen after it
 * has been wrapped.
 */
@Immutable
class ImmutableDoubleSeries internal constructor(
    private val backing: DoubleArray,
) {
    val size: Int get() = backing.size

    operator fun get(index: Int): Double = backing[index]

    override fun equals(other: Any?): Boolean =
        other is ImmutableDoubleSeries && backing.contentEquals(other.backing)

    override fun hashCode(): Int = backing.contentHashCode()
}

/** Immutable-by-contract IntArray wrapper for Compose-facing chart state. */
@Immutable
class ImmutableIntSeries internal constructor(
    private val backing: IntArray,
) {
    val size: Int get() = backing.size

    operator fun get(index: Int): Int = backing[index]

    override fun equals(other: Any?): Boolean =
        other is ImmutableIntSeries && backing.contentEquals(other.backing)

    override fun hashCode(): Int = backing.contentHashCode()
}

internal fun List<Candle>.asCandleSeries(): CandleSeries =
    if (this is CandleSeries) this else if (isEmpty()) CandleSeries.EMPTY else CandleSeries(this)

internal fun DoubleArray?.asImmutableDoubleSeries(): ImmutableDoubleSeries? =
    this?.let(::ImmutableDoubleSeries)

internal fun IntArray?.asImmutableIntSeries(): ImmutableIntSeries? =
    this?.let(::ImmutableIntSeries)
