package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import com.foxtrader.app.domain.model.tradepro.OrderFlowSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam between the TRADEPRO engines and whatever order-flow data is available.
 *
 * The engines only ever see [OrderFlowBar]s, never raw candles or a raw tape. This means the same
 * imbalance / absorption / hold-zone logic runs unchanged whether the bars come from a real footprint
 * feed (aggressor-tagged trades / L2) or from the candle-derived proxy below. To upgrade fidelity, add
 * a new [OrderFlowProvider] backed by a tape feed and bind it in place of the candle-derived one.
 */
interface OrderFlowProvider {
    /** The provenance the produced bars will carry. */
    val source: OrderFlowSource

    /** Convert a candle series into order-flow bars. Must be pure and non-throwing. */
    fun toOrderFlow(candles: List<Candle>): List<OrderFlowBar>
}

/**
 * Estimates aggressive buy vs sell volume from candle geometry when no real tape is available.
 *
 * Model: a bar that closes near its high traded mostly on the offer (aggressive buying); near its low,
 * mostly on the bid (aggressive selling). We split the bar's total volume by the *close location value*
 * (CLV): `clv = ((close - low) - (high - close)) / range`, which ranges [-1, +1]. `buyFraction` maps
 * that to [0, 1]. This is a well-known proxy (Chaikin-style money-flow) — it is NOT true order flow,
 * which is why produced bars are tagged [OrderFlowSource.CANDLE_DERIVED].
 *
 * Degenerate bars (zero range, e.g. a daily-gap doji or a flat synthetic bar) split 50/50 to avoid
 * divide-by-zero and NaN.
 */
@Singleton
class CandleDerivedOrderFlowProvider @Inject constructor() : OrderFlowProvider {

    override val source: OrderFlowSource = OrderFlowSource.CANDLE_DERIVED

    override fun toOrderFlow(candles: List<Candle>): List<OrderFlowBar> {
        if (candles.isEmpty()) return emptyList()
        return candles.mapIndexed { index, c ->
            val vol = if (c.volume.isFinite() && c.volume > 0.0) c.volume else 0.0
            val range = c.high - c.low
            val buyFraction = if (range > 0.0 && range.isFinite()) {
                val clv = ((c.close - c.low) - (c.high - c.close)) / range
                ((clv + 1.0) / 2.0).coerceIn(0.0, 1.0)
            } else {
                0.5
            }
            val buyVolume = vol * buyFraction
            OrderFlowBar(
                index = index,
                timestamp = c.timestamp,
                open = c.open,
                high = c.high,
                low = c.low,
                close = c.close,
                buyVolume = buyVolume,
                sellVolume = vol - buyVolume,
                source = source,
            )
        }
    }
}
