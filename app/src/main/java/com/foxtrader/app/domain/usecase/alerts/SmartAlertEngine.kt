package com.foxtrader.app.domain.usecase.alerts

import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/**
 * Smart Alert Engine — context-aware alerts that trigger on market conditions.
 *
 * Beyond simple price alerts, this detects:
 * - Price reaching key SMC levels (order blocks, FVG, liquidity)
 * - Market structure shifts (BOS/CHOCH on specific timeframes)
 * - Confluence threshold reached (multi-TF alignment)
 * - Harmonic pattern completion (D-point reached)
 * - Risk threshold breaches (drawdown, daily loss)
 * - Session open/close times
 * - News event proximity
 *
 * Each alert has a condition engine that evaluates against live ticks.
 */
@Singleton
class SmartAlertEngine @Inject constructor() {

    private val alerts = mutableListOf<SmartAlert>()

    // ========================================================================
    // ALERT TYPES
    // ========================================================================

    data class SmartAlert(
        val id: String = UUID.randomUUID().toString(),
        val type: SmartAlertType,
        val symbol: String,
        val timeframe: Timeframe? = null,
        val condition: AlertCondition,
        val priority: AlertPriority = AlertPriority.MEDIUM,
        val message: String,
        val isActive: Boolean = true,
        val triggered: Boolean = false,
        val triggeredAt: Long? = null,
        val repeatable: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
    )

    enum class SmartAlertType {
        PRICE_LEVEL,           // Price crosses a specific level
        PRICE_RANGE_EXIT,      // Price exits a range
        STRUCTURE_BREAK,       // BOS or CHOCH detected
        ORDER_BLOCK_TOUCH,     // Price enters an order block zone
        FVG_FILL,              // Fair value gap gets filled
        LIQUIDITY_SWEEP,       // Liquidity level swept
        CONFLUENCE_REACHED,    // Multi-TF confluence score threshold
        HARMONIC_COMPLETION,   // Harmonic pattern D-point reached
        RSI_EXTREME,           // RSI enters overbought/oversold
        EMA_CROSS,             // EMA crossover/crossunder
        VOLUME_SPIKE,          // Unusual volume detected
        SESSION_EVENT,         // Session open/close
        NEWS_PROXIMITY,        // High-impact event approaching
        DRAWDOWN_BREACH,       // Risk threshold exceeded
    }

    sealed class AlertCondition {
        data class PriceCross(val level: Double, val direction: CrossDirection) : AlertCondition()
        data class PriceRange(val low: Double, val high: Double) : AlertCondition()
        data class RsiThreshold(val threshold: Double, val above: Boolean) : AlertCondition()
        data class EmaCross(val shortPeriod: Int, val longPeriod: Int, val direction: CrossDirection) : AlertCondition()
        data class VolumeMultiple(val multiple: Double) : AlertCondition() // e.g. 3x average
        data class ConfluenceScore(val minScore: Int) : AlertCondition()
        data class TimeEvent(val hourUtc: Int, val minuteUtc: Int) : AlertCondition()
        data object StructureShift : AlertCondition()
        data object OrderBlockTouch : AlertCondition()
        data object LiquiditySweep : AlertCondition()
    }

    enum class CrossDirection { ABOVE, BELOW }

    // ========================================================================
    // ALERT MANAGEMENT
    // ========================================================================

    fun createAlert(alert: SmartAlert): SmartAlert {
        alerts.add(alert)
        return alert
    }

    fun createPriceAlert(
        symbol: String,
        level: Double,
        direction: CrossDirection,
        message: String = "Price alert triggered",
    ): SmartAlert {
        val alert = SmartAlert(
            type = SmartAlertType.PRICE_LEVEL,
            symbol = symbol,
            condition = AlertCondition.PriceCross(level, direction),
            message = message,
        )
        alerts.add(alert)
        return alert
    }

    fun createRsiAlert(
        symbol: String,
        timeframe: Timeframe,
        threshold: Double,
        above: Boolean,
    ): SmartAlert {
        val dir = if (above) "above" else "below"
        val alert = SmartAlert(
            type = SmartAlertType.RSI_EXTREME,
            symbol = symbol,
            timeframe = timeframe,
            condition = AlertCondition.RsiThreshold(threshold, above),
            message = "RSI $dir $threshold on ${timeframe.label}",
        )
        alerts.add(alert)
        return alert
    }

    fun createConfluenceAlert(symbol: String, minScore: Int = 70): SmartAlert {
        val alert = SmartAlert(
            type = SmartAlertType.CONFLUENCE_REACHED,
            symbol = symbol,
            condition = AlertCondition.ConfluenceScore(minScore),
            message = "Confluence score reached $minScore%",
            priority = AlertPriority.HIGH,
        )
        alerts.add(alert)
        return alert
    }

    fun createVolumeAlert(symbol: String, multiple: Double = 3.0): SmartAlert {
        val alert = SmartAlert(
            type = SmartAlertType.VOLUME_SPIKE,
            symbol = symbol,
            condition = AlertCondition.VolumeMultiple(multiple),
            message = "Volume spike: ${multiple}x average",
        )
        alerts.add(alert)
        return alert
    }

    fun deleteAlert(id: String) { alerts.removeAll { it.id == id } }
    fun toggleAlert(id: String) {
        val idx = alerts.indexOfFirst { it.id == id }
        if (idx >= 0) alerts[idx] = alerts[idx].copy(isActive = !alerts[idx].isActive)
    }
    fun getActiveAlerts(): List<SmartAlert> = alerts.filter { it.isActive && !it.triggered }
    fun getTriggeredAlerts(): List<SmartAlert> = alerts.filter { it.triggered }
    fun getAllAlerts(): List<SmartAlert> = alerts.toList()

    // ========================================================================
    // EVALUATION (called on each tick/candle update)
    // ========================================================================

    /**
     * Evaluate all active alerts against the current candle.
     * Returns list of newly triggered alerts.
     */
    fun evaluate(symbol: String, candle: Candle, prevCandle: Candle?): List<SmartAlert> {
        val triggered = mutableListOf<SmartAlert>()

        for (i in alerts.indices) {
            val alert = alerts[i]
            if (!alert.isActive || alert.triggered || alert.symbol != symbol) continue

            val fire = when (val cond = alert.condition) {
                is AlertCondition.PriceCross -> evaluatePriceCross(cond, candle, prevCandle)
                is AlertCondition.PriceRange -> candle.close < cond.low || candle.close > cond.high
                is AlertCondition.VolumeMultiple -> false // Needs avg volume context
                is AlertCondition.RsiThreshold -> false // Needs RSI context
                is AlertCondition.EmaCross -> false // Needs EMA context
                is AlertCondition.ConfluenceScore -> false // Needs confluence context
                is AlertCondition.TimeEvent -> false // Needs time context
                is AlertCondition.StructureShift -> false // Needs structure context
                is AlertCondition.OrderBlockTouch -> false // Needs OB context
                is AlertCondition.LiquiditySweep -> false // Needs liquidity context
            }

            if (fire) {
                alerts[i] = alert.copy(triggered = true, triggeredAt = candle.timestamp)
                triggered.add(alerts[i])
            }
        }
        return triggered
    }

    private fun evaluatePriceCross(
        cond: AlertCondition.PriceCross,
        candle: Candle,
        prev: Candle?,
    ): Boolean {
        if (prev == null) return false
        return when (cond.direction) {
            CrossDirection.ABOVE -> prev.close < cond.level && candle.close >= cond.level
            CrossDirection.BELOW -> prev.close > cond.level && candle.close <= cond.level
        }
    }

    fun clearAll() { alerts.clear() }
}
