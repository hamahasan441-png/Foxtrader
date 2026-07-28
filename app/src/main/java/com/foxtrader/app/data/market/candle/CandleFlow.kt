package com.foxtrader.app.data.market.candle

import com.foxtrader.app.data.market.model.CandleUpdate
import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Transforms a cold stream of ticks into a stream of confirmed, no-repaint
 * candles across all [timeframes].
 *
 * A single [MultiTimeframeCandleEngine] is created per collection and lives for
 * the lifetime of the upstream flow, so bucket state is preserved across
 * emissions. When the upstream completes, any forming buckets are [flushed]
 * (flushed) so the final partial bars are not lost.
 *
 * Every emitted [CandleUpdate] has `isBarClose = true`: this operator only
 * forwards sealed bars, never a forming one, so consumers can treat each
 * emission as final history.
 *
 * Usage:
 * ```
 * tickFlow
 *     .buildCandles(MarketTimeframe.ALL)
 *     .collect { update -> chart.applyConfirmed(update) }
 * ```
 */
fun Flow<Tick>.buildCandles(timeframes: Collection<MarketTimeframe>): Flow<CandleUpdate> = flow {
    val engine = MultiTimeframeCandleEngine(timeframes)
    collect { tick ->
        val closed = engine.onTickCollect(tick)
        for ((tf, candle) in closed) {
            emit(CandleUpdate(tf, candle, isBarClose = true))
        }
    }
    val flushed = engine.flush()
    for ((tf, candle) in flushed) {
        emit(CandleUpdate(tf, candle, isBarClose = true))
    }
}
