package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.deriv.DerivMarketDataSource
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Public Deriv live market-data adapter for the chart pipeline.
 *
 * Deriv streams raw prices rather than timeframe candles. Every received tick is
 * aggregated into the requested chart timeframes. This adapter uses the dedicated
 * public [DerivMarketDataSource], so chart subscriptions can never replace the
 * authenticated native-Deriv trading session.
 */
@Singleton
class DerivMarketWebSocket @Inject constructor(
    private val dataSource: DerivMarketDataSource,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()

    private val subscriptions = mutableSetOf<Pair<String, Timeframe>>()
    private val symbolJobs = mutableMapOf<String, Job>()
    private val buckets = mutableMapOf<Pair<String, Timeframe>, CandleBucket>()
    private val healthySymbols = mutableSetOf<String>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 256)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val normalized = transportSymbol(symbol)
        require(normalized.isNotBlank()) { "Deriv symbol is required" }
        mutex.withLock {
            val pair = normalized to timeframe
            if (!subscriptions.add(pair)) return
            if (symbolJobs[normalized]?.isActive != true) {
                _connectionState.value = ConnectionState.CONNECTING
                symbolJobs[normalized] = scope.launch { streamSymbol(normalized) }
            }
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val normalized = transportSymbol(symbol)
        mutex.withLock {
            subscriptions.remove(normalized to timeframe)
            buckets.remove(normalized to timeframe)
            if (subscriptions.none { it.first == normalized }) {
                symbolJobs.remove(normalized)?.cancel()
                healthySymbols.remove(normalized)
                buckets.keys.removeAll { it.first == normalized }
            }
            recomputeStateLocked()
        }
    }

    override suspend fun disconnectAll() {
        mutex.withLock {
            subscriptions.clear()
            symbolJobs.values.forEach { it.cancel() }
            symbolJobs.clear()
            buckets.clear()
            healthySymbols.clear()
            // Do not close DerivMarketDataSource's shared public socket here:
            // a history request can be in flight on the same public-only session.
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun streamSymbol(symbol: String) {
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive && isSymbolSubscribed(symbol)) {
            try {
                dataSource.streamTicks(symbol).collect { tick ->
                    failures = 0
                    markHealthy(symbol)
                    onPrice(symbol, tick.epochSeconds * 1_000L, tick.quote)
                }
                // A subscription flow should be long-lived. Normal completion
                // while the symbol remains subscribed is treated as a reconnect.
                if (isSymbolSubscribed(symbol)) {
                    failures++
                    markFailure(symbol, failures)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failures++
                markFailure(symbol, failures)
            }

            if (isSymbolSubscribed(symbol)) {
                delay(reconnectDelayMs(failures))
            }
        }
    }

    private suspend fun onPrice(symbol: String, timestampMs: Long, price: Double) {
        if (timestampMs <= 0L || !price.isFinite() || price <= 0.0) return
        val active = mutex.withLock {
            subscriptions.filter { it.first == symbol }.map { it.second }
        }
        for (timeframe in active) updateBucket(symbol, timeframe, timestampMs, price)
    }

    private suspend fun updateBucket(symbol: String, timeframe: Timeframe, timestampMs: Long, price: Double) {
        val widthMs = timeframe.minutes.toLong().coerceAtLeast(1L) * 60_000L
        val start = (timestampMs / widthMs) * widthMs
        val key = symbol to timeframe

        var completed: CandleBucket? = null
        val current = mutex.withLock {
            val previous = buckets[key]
            when {
                previous == null -> CandleBucket(start, price).also { buckets[key] = it }
                // Delayed/out-of-order ticks must not rewind a chart candle.
                start < previous.start -> null
                start > previous.start -> {
                    completed = previous
                    CandleBucket(start, price).also { buckets[key] = it }
                }
                else -> previous.apply {
                    high = max(high, price)
                    low = min(low, price)
                    close = price
                    volume += 1.0
                }
            }
        } ?: return

        completed?.let { _ticks.emit(it.toTickUpdate(symbol, timeframe, isBarClose = true)) }
        _ticks.emit(current.toTickUpdate(symbol, timeframe, isBarClose = false))
    }

    private suspend fun isSymbolSubscribed(symbol: String): Boolean =
        mutex.withLock { subscriptions.any { it.first == symbol } }

    private suspend fun markHealthy(symbol: String) {
        mutex.withLock {
            healthySymbols += symbol
            recomputeStateLocked()
        }
    }

    private suspend fun markFailure(symbol: String, failures: Int) {
        mutex.withLock {
            healthySymbols -= symbol
            _connectionState.value = when {
                subscriptions.isEmpty() -> ConnectionState.DISCONNECTED
                healthySymbols.isNotEmpty() -> ConnectionState.CONNECTED
                failures >= FAILURES_BEFORE_ERROR -> ConnectionState.ERROR
                else -> ConnectionState.RECONNECTING
            }
        }
    }

    private fun recomputeStateLocked() {
        _connectionState.value = when {
            subscriptions.isEmpty() -> ConnectionState.DISCONNECTED
            healthySymbols.isNotEmpty() -> ConnectionState.CONNECTED
            symbolJobs.values.any { it.isActive } -> ConnectionState.CONNECTING
            else -> ConnectionState.ERROR
        }
    }

    private fun reconnectDelayMs(failures: Int): Long {
        val exponent = (failures - 1).coerceIn(0, 5)
        return minOf(INITIAL_RECONNECT_MS * (1L shl exponent), MAX_RECONNECT_MS)
    }

    private fun transportSymbol(symbol: String): String = symbol.trim()

    private class CandleBucket(
        val start: Long,
        val open: Double,
        var high: Double = open,
        var low: Double = open,
        var close: Double = open,
        var volume: Double = 1.0,
    ) {
        fun toTickUpdate(symbol: String, timeframe: Timeframe, isBarClose: Boolean): TickUpdate = TickUpdate(
            symbol = symbol,
            timeframe = timeframe,
            candle = Candle(
                timestamp = start,
                open = open,
                high = high,
                low = low,
                close = close,
                // Tick count, not exchange volume. Kept deterministic and never
                // confused with Deriv's unavailable historical exchange volume.
                volume = volume,
            ),
            isBarClose = isBarClose,
        )
    }

    private companion object {
        const val FAILURES_BEFORE_ERROR = 4
        const val INITIAL_RECONNECT_MS = 1_000L
        const val MAX_RECONNECT_MS = 30_000L
    }
}
