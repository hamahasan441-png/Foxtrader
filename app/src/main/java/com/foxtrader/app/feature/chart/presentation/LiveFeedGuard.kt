package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.TickUpdate

/**
 * Stateful trust boundary for primary-chart live ticks.
 *
 * Provider callbacks may be duplicated or arrive out of order around a socket
 * reconnect. Persisting an older candle after a newer one can roll the chart
 * backwards and make a strategy evaluate a false transition. Equal timestamps
 * remain valid because a forming candle is expected to update many times.
 */
internal class LiveTickGate {
    private data class SeriesKey(
        val provider: String,
        val symbol: String,
        val timeframe: String,
    )

    private data class AcceptedTick(
        val timestamp: Long,
        val candle: Candle,
    )

    private val latest = mutableMapOf<SeriesKey, AcceptedTick>()

    @Synchronized
    fun accept(tick: TickUpdate): Boolean {
        val candle = tick.candle
        val key = SeriesKey(
            provider = tick.provider?.name.orEmpty(),
            symbol = tick.symbol.trim().uppercase(),
            timeframe = tick.timeframe.name,
        )
        val previous = latest[key]
        if (previous != null && candle.timestamp < previous.timestamp) return false

        if (previous?.timestamp == candle.timestamp && previous.candle == candle) return false

        latest[key] = AcceptedTick(candle.timestamp, candle)
        return true
    }

    @Synchronized
    fun reset() {
        latest.clear()
    }
}

/** Emits one recovery request for each healthy -> interrupted -> healthy cycle. */
internal class LiveRecoveryGate {
    private var hasConnected = false
    private var interrupted = false

    @Synchronized
    fun onState(state: ConnectionState): Boolean {
        if (state == ConnectionState.CONNECTED) {
            val shouldRecover = hasConnected && interrupted
            hasConnected = true
            interrupted = false
            return shouldRecover
        }
        if (hasConnected && state != ConnectionState.CONNECTING) interrupted = true
        return false
    }

    @Synchronized
    fun reset() {
        hasConnected = false
        interrupted = false
    }
}
