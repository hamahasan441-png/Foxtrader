package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free near-real-time FX/metals/index transport backed by Dukascopy's public
 * tick archive. Dukascopy does not expose the same public streaming WebSocket as
 * crypto exchanges, so this adapter polls a tiny recent window and emits only a
 * changed latest candle. The chart separately computes freshness and labels a
 * stale feed DELAYED/CACHED instead of pretending it is live.
 *
 * Healthy subscriptions target a one-second poll cadence on every timeframe.
 * The fetch duration is subtracted from the sleep so a two-second HTTP request
 * does not accidentally turn a one-second cadence into seven seconds. Failed
 * cycles retain bounded exponential backoff to avoid hammering the provider.
 */
@Singleton
class DukascopyPollingWebSocket @Inject constructor(
    private val dataSource: DukascopyDataSource,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private val jobs = mutableMapOf<Pair<String, Timeframe>, Job>()
    private val lastCandles = mutableMapOf<Pair<String, Timeframe>, Candle>()
    private val healthySubscriptions = mutableSetOf<Pair<String, Timeframe>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 64)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val pair = canonical(symbol) to timeframe
        mutex.withLock {
            if (jobs[pair]?.isActive == true) return
            _connectionState.value = if (jobs.isEmpty()) ConnectionState.CONNECTING else _connectionState.value
            jobs[pair] = scope.launch { poll(pair) }
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val pair = canonical(symbol) to timeframe
        mutex.withLock {
            jobs.remove(pair)?.cancel()
            lastCandles.remove(pair)
            healthySubscriptions.remove(pair)
            updateAggregateStateLocked()
        }
    }

    override suspend fun disconnectAll() {
        mutex.withLock {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            lastCandles.clear()
            healthySubscriptions.clear()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun poll(pair: Pair<String, Timeframe>) {
        val (symbol, timeframe) = pair
        var failures = 0

        while (kotlin.coroutines.coroutineContext.isActive) {
            val cycleStartedNanos = System.nanoTime()
            var failedThisCycle = false
            try {
                val latest = dataSource.fetchCandles(symbol, timeframe, POLL_FETCH_BARS).lastOrNull()
                if (latest == null) {
                    failures++
                    failedThisCycle = true
                    markFailure(pair, failures)
                } else {
                    failures = 0
                    val now = System.currentTimeMillis()
                    val periodMs = timeframe.minutes.toLong().coerceAtLeast(1L) * 60_000L
                    val isFresh = now - latest.timestamp <= periodMs + DATA_FRESHNESS_GRACE_MS
                    val previous = mutex.withLock {
                        if (isFresh) healthySubscriptions += pair else healthySubscriptions -= pair
                        updateAggregateStateLocked(staleData = !isFresh)
                        lastCandles.put(pair, latest)
                    }
                    if (previous != latest) {
                        _ticks.emit(
                            TickUpdate(
                                symbol = symbol,
                                timeframe = timeframe,
                                candle = latest,
                                isBarClose = now >= latest.timestamp + periodMs,
                            )
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failures++
                failedThisCycle = true
                markFailure(pair, failures)
            }

            val elapsedMs = (System.nanoTime() - cycleStartedNanos).coerceAtLeast(0L) / 1_000_000L
            delay(nextDelayMs(timeframe, failedThisCycle, failures, elapsedMs))
        }
    }

    private suspend fun markFailure(pair: Pair<String, Timeframe>, failures: Int) {
        mutex.withLock {
            if (failures >= FAILURES_BEFORE_STALE) healthySubscriptions.remove(pair)
            _connectionState.value = when {
                jobs.isEmpty() -> ConnectionState.DISCONNECTED
                healthySubscriptions.isNotEmpty() -> ConnectionState.CONNECTED
                failures >= FAILURES_BEFORE_ERROR -> ConnectionState.ERROR
                failures >= FAILURES_BEFORE_STALE -> ConnectionState.STALE
                else -> ConnectionState.CONNECTING
            }
        }
    }

    private fun updateAggregateStateLocked(staleData: Boolean = false) {
        _connectionState.value = when {
            jobs.isEmpty() -> ConnectionState.DISCONNECTED
            healthySubscriptions.isNotEmpty() -> ConnectionState.CONNECTED
            staleData -> ConnectionState.STALE
            else -> ConnectionState.CONNECTING
        }
    }

    internal fun pollIntervalMs(timeframe: Timeframe): Long = when (timeframe) {
        Timeframe.M1,
        Timeframe.M5,
        Timeframe.M15,
        Timeframe.M30,
        Timeframe.H1,
        Timeframe.H4,
        Timeframe.D1,
        Timeframe.W1,
        Timeframe.MN,
        -> HEALTHY_POLL_INTERVAL_MS
    }

    internal fun nextDelayMs(
        timeframe: Timeframe,
        failedThisCycle: Boolean,
        failures: Int,
        elapsedMs: Long,
    ): Long {
        val targetCycleMs = if (failedThisCycle) {
            maxOf(pollIntervalMs(timeframe), failureBackoffMs(failures))
        } else {
            pollIntervalMs(timeframe)
        }
        return (targetCycleMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    internal fun failureBackoffMs(failures: Int): Long = minOf(
        FAILURE_BACKOFF_BASE_MS * (1L shl (failures - 1).coerceIn(0, 5)),
        FAILURE_BACKOFF_MAX_MS,
    )

    private fun canonical(symbol: String): String = symbol.trim().uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")

    private companion object {
        const val POLL_FETCH_BARS = 3
        const val FAILURES_BEFORE_STALE = 2
        const val FAILURES_BEFORE_ERROR = 4
        const val DATA_FRESHNESS_GRACE_MS = 120_000L
        const val HEALTHY_POLL_INTERVAL_MS = 1_000L
        const val FAILURE_BACKOFF_BASE_MS = 5_000L
        const val FAILURE_BACKOFF_MAX_MS = 120_000L
    }
}
