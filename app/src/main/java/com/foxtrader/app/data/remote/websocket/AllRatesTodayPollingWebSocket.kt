package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.api.AllRatesTodayDataSource
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
 * Near-live AllRatesToday transport.
 *
 * AllRatesToday refreshes interbank rates roughly once per minute and does not
 * document a streaming WebSocket, so FoxTrader polls its own backend proxy at
 * that cadence. The ART_API_KEY remains on the backend.
 */
@Singleton
class AllRatesTodayPollingWebSocket @Inject constructor(
    private val dataSource: AllRatesTodayDataSource,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private val jobs = mutableMapOf<Pair<String, Timeframe>, Job>()
    private val lastCandles = mutableMapOf<Pair<String, Timeframe>, Candle>()
    private val healthy = mutableSetOf<Pair<String, Timeframe>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 64)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val pair = canonical(symbol) to timeframe
        mutex.withLock {
            if (jobs[pair]?.isActive == true) return
            if (jobs.isEmpty()) _connectionState.value = ConnectionState.CONNECTING
            jobs[pair] = scope.launch { poll(pair) }
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val pair = canonical(symbol) to timeframe
        mutex.withLock {
            jobs.remove(pair)?.cancel()
            lastCandles.remove(pair)
            healthy.remove(pair)
            updateStateLocked()
        }
    }

    override suspend fun disconnectAll() {
        mutex.withLock {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            lastCandles.clear()
            healthy.clear()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun poll(pair: Pair<String, Timeframe>) {
        val (symbol, timeframe) = pair
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                val latest = dataSource.fetchCandles(symbol, timeframe, 3).lastOrNull()
                if (latest == null) {
                    failures += 1
                    markFailure(pair, failures)
                } else {
                    failures = 0
                    val now = System.currentTimeMillis()
                    val periodMs = timeframe.minutes.toLong().coerceAtLeast(1L) * 60_000L
                    val fresh = now - latest.timestamp <= periodMs + FRESHNESS_GRACE_MS
                    val previous = mutex.withLock {
                        if (fresh) healthy += pair else healthy -= pair
                        updateStateLocked(stale = !fresh)
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
                failures += 1
                markFailure(pair, failures)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun markFailure(pair: Pair<String, Timeframe>, failures: Int) {
        mutex.withLock {
            if (failures >= FAILURES_BEFORE_STALE) healthy.remove(pair)
            _connectionState.value = when {
                jobs.isEmpty() -> ConnectionState.DISCONNECTED
                healthy.isNotEmpty() -> ConnectionState.CONNECTED
                failures >= FAILURES_BEFORE_ERROR -> ConnectionState.ERROR
                failures >= FAILURES_BEFORE_STALE -> ConnectionState.STALE
                else -> ConnectionState.CONNECTING
            }
        }
    }

    private fun updateStateLocked(stale: Boolean = false) {
        _connectionState.value = when {
            jobs.isEmpty() -> ConnectionState.DISCONNECTED
            healthy.isNotEmpty() -> ConnectionState.CONNECTED
            stale -> ConnectionState.STALE
            else -> ConnectionState.CONNECTING
        }
    }

    private fun canonical(symbol: String): String = symbol.trim().uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")

    private companion object {
        const val POLL_INTERVAL_MS = 60_000L
        const val FRESHNESS_GRACE_MS = 180_000L
        const val FAILURES_BEFORE_STALE = 2
        const val FAILURES_BEFORE_ERROR = 4
    }
}
