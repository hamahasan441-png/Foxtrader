package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.SourcedCandles
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages primary-chart data and enforces a single chart-computation lane.
 *
 * Room can emit on every live candle update and indicator toggles can request a
 * full recompute at the same time. Previously every emission launched another
 * independent CPU-heavy chart job, so a fast feed could build several LiTX/SMC/
 * strategy computations concurrently. Stale-frame guards stopped bad results
 * publishing but did not stop the CPU/GC storm. This controller now coalesces
 * market emissions (latest wins) and serializes all chart computation through a
 * mutex. The semantic result is unchanged; the app simply never computes two
 * primary chart frames concurrently.
 */
internal class ChartDataController(
    private val repository: MarketRepository,
    private val webSocket: MarketWebSocket,
    private val scope: CoroutineScope,
    private val onMergedCandlesChanged: suspend (CandleSource, Boolean) -> Unit,
    private val onUpsertTick: suspend (String, Timeframe, Candle, DataProvider?) -> Unit,
) {

    val symbolFlow = MutableStateFlow("EURUSD")
    val timeframeFlow = MutableStateFlow(Timeframe.M15)

    var currentObservedCandles: SourcedCandles = SourcedCandles.EMPTY
        private set

    private val prependedHistory = mutableListOf<Candle>()
    private var prependedHistorySnapshot: List<Candle> = emptyList()
    private var prependedHistorySource: CandleSource = CandleSource.CACHED
    var mergedVisibleCandles: List<Candle> = emptyList()
        private set

    var isLoadingOlder: Boolean = false
        private set
    var historyEndReached: Boolean = false
        private set
    var loadError: String? = null
        private set
    private var historyContextGeneration: Long = 0L

    /** Exactly one primary-chart computation may run at a time. */
    private val computationMutex = Mutex()

    /** Latest flow-driven recompute. A newer Room emission cancels the waiter. */
    private var marketProcessingJob: Job? = null

    /** Coalesces explicit recomputes such as rapid indicator-chip changes. */
    private val explicitGenerationLock = Any()
    private var explicitProcessingGeneration: Long = 0L

    /** Reject duplicate/late provider events before they can reach Room. */
    private val liveTickGate = LiveTickGate()

    /** Detects a real reconnect without treating the first connection as recovery. */
    private val liveRecoveryGate = LiveRecoveryGate()

    /** At most one REST gap repair may be active for the primary series. */
    private var liveRecoveryJob: Job? = null

    private data class EmissionKey(
        val source: CandleSource,
        val size: Int,
        val lastTimestamp: Long?,
        val lastOpen: Double?,
        val lastHigh: Double?,
        val lastLow: Double?,
        val lastClose: Double?,
        val lastVolume: Double?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMarket() {
        combine(symbolFlow, timeframeFlow) { symbol, tf -> symbol to tf }
            .flatMapLatest { (symbol, tf) -> repository.observeSourcedCandles(symbol, tf) }
            .distinctUntilChangedBy { sourced ->
                val last = sourced.candles.lastOrNull()
                EmissionKey(
                    source = sourced.source,
                    size = sourced.candles.size,
                    lastTimestamp = last?.timestamp,
                    lastOpen = last?.open,
                    lastHigh = last?.high,
                    lastLow = last?.low,
                    lastClose = last?.close,
                    lastVolume = last?.volume,
                )
            }
            .onEach { sourced ->
                currentObservedCandles = sourced
                rebuildMergedVisibleCandles()
                scheduleMarketProcessing(sourced.source, preferIncremental = true)
            }
            .launchIn(scope)
    }

    fun observeWebSocketTicks() {
        webSocket.ticks
            .onEach { tick ->
                if (tick.symbol == symbolFlow.value &&
                    tick.timeframe == timeframeFlow.value &&
                    liveTickGate.accept(tick)
                ) {
                    try {
                        // Keep writes in flow order. Launching one child per tick
                        // allowed an older Room write to finish after a newer one.
                        onUpsertTick(tick.symbol, tick.timeframe, tick.candle, tick.provider)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A later tick or reconnect snapshot retries safely.
                    }
                }
            }
            .launchIn(scope)
    }

    /**
     * Backfill candles missed while a transport was reconnecting. The initial
     * CONNECTED state does not trigger a second startup refresh; only a stream
     * that was healthy and then interrupted is repaired.
     */
    fun observeLiveRecovery() {
        webSocket.connectionState
            .onEach { state ->
                if (liveRecoveryGate.onState(state)) scheduleLiveGapRecovery()
            }
            .launchIn(scope)
    }

    private fun scheduleLiveGapRecovery() {
        liveRecoveryJob?.cancel()
        val symbol = symbolFlow.value
        val timeframe = timeframeFlow.value
        liveRecoveryJob = scope.launch {
            try {
                repository.refreshCandles(symbol, timeframe)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Streaming remains usable; the next reconnect or manual
                // refresh gets another opportunity to repair the gap.
            }
        }
    }

    /**
     * Explicit user/config recompute. Multiple callers that pile up while one
     * heavy frame is running are coalesced: only the newest request proceeds
     * after the mutex is available.
     */
    suspend fun processMergedCandles(preferIncremental: Boolean = false) {
        val generation = synchronized(explicitGenerationLock) {
            explicitProcessingGeneration += 1L
            explicitProcessingGeneration
        }
        // Prefer an explicit user configuration change over a stale live-frame
        // waiter. If a market compute is already in CPU code, the mutex lets it
        // finish safely before the latest explicit frame runs.
        marketProcessingJob?.cancel()
        val source = currentMergedSource()
        computationMutex.withLock {
            val stillLatest = synchronized(explicitGenerationLock) {
                generation == explicitProcessingGeneration
            }
            if (!stillLatest) return
            onMergedCandlesChanged(source, preferIncremental)
        }
    }

    private fun scheduleMarketProcessing(source: CandleSource, preferIncremental: Boolean) {
        marketProcessingJob?.cancel()
        marketProcessingJob = scope.launch {
            try {
                computationMutex.withLock {
                    onMergedCandlesChanged(source, preferIncremental)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last valid frame. The next market emission retries.
            }
        }
    }

    /** History loads run through the same single-computation lane. */
    private suspend fun processHistoryMutation(preferIncremental: Boolean = false) {
        marketProcessingJob?.cancel()
        computationMutex.withLock {
            onMergedCandlesChanged(currentMergedSource(), preferIncremental)
        }
    }

    private fun currentMergedSource(): CandleSource = CandleSource.worstOf(
        buildList {
            add(currentObservedCandles.source)
            if (prependedHistorySnapshot.isNotEmpty()) add(prependedHistorySource)
        }
    )

    fun rebuildMergedVisibleCandles() {
        val observed = currentObservedCandles.candles
        mergedVisibleCandles = when {
            prependedHistorySnapshot.isEmpty() -> observed
            observed.isEmpty() -> prependedHistorySnapshot
            else -> ConcatenatedCandleList(prependedHistorySnapshot, observed)
        }
    }

    fun clearPrependedHistory() {
        historyContextGeneration += 1L
        prependedHistory.clear()
        prependedHistorySnapshot = emptyList()
        prependedHistorySource = CandleSource.CACHED
        isLoadingOlder = false
        historyEndReached = false
        loadError = null
        rebuildMergedVisibleCandles()
    }

    fun loadOlderHistory(onStateChanged: (Boolean, Boolean, String?) -> Unit) {
        if (currentObservedCandles.source == CandleSource.SYNTHETIC) {
            historyEndReached = true
            onStateChanged(false, true, null)
            return
        }

        val beforeTimestamp = prependedHistory.firstOrNull()?.timestamp
            ?: currentObservedCandles.candles.firstOrNull()?.timestamp
            ?: return
        if (isLoadingOlder || historyEndReached) return
        val requestSymbol = symbolFlow.value
        val requestTimeframe = timeframeFlow.value
        val requestGeneration = historyContextGeneration

        scope.launch {
            isLoadingOlder = true
            loadError = null
            onStateChanged(true, false, null)
            repository.loadOlderCandles(
                symbol = requestSymbol,
                timeframe = requestTimeframe,
                beforeTimestamp = beforeTimestamp,
                limit = HISTORY_PAGE_SIZE,
            ).onSuccess { page ->
                if (!historyContextMatches(requestSymbol, requestTimeframe, requestGeneration)) return@onSuccess
                val existingTimestamps = HashSet<Long>(prependedHistory.size + currentObservedCandles.candles.size).apply {
                    prependedHistory.forEach { add(it.timestamp) }
                    currentObservedCandles.candles.forEach { add(it.timestamp) }
                }
                val newCandles = page.candles.filter { candle ->
                    candle.timestamp < beforeTimestamp && existingTimestamps.add(candle.timestamp)
                }
                if (newCandles.isEmpty()) {
                    isLoadingOlder = false
                    historyEndReached = true
                    onStateChanged(false, true, null)
                } else {
                    prependedHistory.addAll(0, newCandles)
                    prependedHistorySnapshot = prependedHistory.toList()
                    prependedHistorySource = CandleSource.worstOf(
                        listOf(prependedHistorySource, page.source)
                    )
                    rebuildMergedVisibleCandles()
                    isLoadingOlder = false
                    historyEndReached = false
                    onStateChanged(false, false, null)
                    processHistoryMutation(preferIncremental = false)
                }
            }.onFailure { error ->
                if (!historyContextMatches(requestSymbol, requestTimeframe, requestGeneration)) return@onFailure
                isLoadingOlder = false
                loadError = error.message ?: "Failed to load older history"
                onStateChanged(false, false, loadError)
            }
        }
    }

    suspend fun preloadHistoryBackTo(
        targetStartTimestamp: Long,
        maxTotalBars: Int = MAX_BACKTEST_VISIBLE_BARS,
        onStateChanged: (Boolean, Boolean, String?) -> Unit = { _, _, _ -> },
    ): Result<HistoryPrefetchResult> {
        if (currentObservedCandles.source == CandleSource.SYNTHETIC) {
            return Result.failure(IllegalStateException("Real market data is required for backtest history."))
        }
        if (isLoadingOlder) {
            return Result.failure(IllegalStateException("History is already loading."))
        }

        val requestSymbol = symbolFlow.value
        val requestTimeframe = timeframeFlow.value
        val requestGeneration = historyContextGeneration
        isLoadingOlder = true
        loadError = null
        onStateChanged(true, historyEndReached, null)

        return try {
            var reachedTarget = false
            var providerExhausted = false
            val seen = HashSet<Long>(prependedHistory.size + currentObservedCandles.candles.size).apply {
                prependedHistory.forEach { add(it.timestamp) }
                currentObservedCandles.candles.forEach { add(it.timestamp) }
            }

            while (seen.size < maxTotalBars) {
                ensureHistoryContext(requestSymbol, requestTimeframe, requestGeneration)
                val oldestTimestamp = prependedHistory.firstOrNull()?.timestamp
                    ?: currentObservedCandles.candles.firstOrNull()?.timestamp
                    ?: break
                if (oldestTimestamp <= targetStartTimestamp) {
                    reachedTarget = true
                    break
                }

                val remaining = (maxTotalBars - seen.size).coerceAtLeast(1)
                val pageLimit = minOf(HISTORY_PAGE_SIZE, remaining)
                val page = repository.loadOlderCandles(
                    symbol = requestSymbol,
                    timeframe = requestTimeframe,
                    beforeTimestamp = oldestTimestamp,
                    limit = pageLimit,
                ).getOrThrow()
                ensureHistoryContext(requestSymbol, requestTimeframe, requestGeneration)

                if (page.source == CandleSource.SYNTHETIC) {
                    throw IllegalStateException("Provider returned simulated history; backtest prefetch stopped.")
                }

                val newCandles = page.candles
                    .asSequence()
                    .filter { it.timestamp < oldestTimestamp }
                    .filter { seen.add(it.timestamp) }
                    .sortedBy { it.timestamp }
                    .toList()

                if (newCandles.isEmpty()) {
                    providerExhausted = true
                    break
                }

                prependedHistory.addAll(0, newCandles)
                prependedHistorySource = CandleSource.worstOf(listOf(prependedHistorySource, page.source))
            }

            prependedHistorySnapshot = prependedHistory.toList()
            rebuildMergedVisibleCandles()

            val oldest = mergedVisibleCandles.firstOrNull()?.timestamp
            reachedTarget = reachedTarget || (oldest != null && oldest <= targetStartTimestamp)
            historyEndReached = providerExhausted
            isLoadingOlder = false
            onStateChanged(false, historyEndReached, null)

            if (mergedVisibleCandles.isNotEmpty()) {
                processHistoryMutation(preferIncremental = false)
            }

            Result.success(
                HistoryPrefetchResult(
                    totalVisibleBars = mergedVisibleCandles.size,
                    oldestTimestamp = oldest ?: 0L,
                    reachedTarget = reachedTarget,
                    providerExhausted = providerExhausted,
                )
            )
        } catch (cancel: CancellationException) {
            if (historyContextMatches(requestSymbol, requestTimeframe, requestGeneration)) {
                isLoadingOlder = false
                onStateChanged(false, historyEndReached, null)
            }
            throw cancel
        } catch (error: Exception) {
            if (historyContextMatches(requestSymbol, requestTimeframe, requestGeneration)) {
                isLoadingOlder = false
                loadError = error.message ?: "Failed to preload backtest history"
                onStateChanged(false, historyEndReached, loadError)
            }
            Result.failure(error)
        }
    }

    private fun historyContextMatches(symbol: String, timeframe: Timeframe, generation: Long): Boolean =
        historyContextGeneration == generation && symbolFlow.value == symbol && timeframeFlow.value == timeframe

    private fun ensureHistoryContext(symbol: String, timeframe: Timeframe, generation: Long) {
        if (!historyContextMatches(symbol, timeframe, generation)) {
            throw IllegalStateException("Chart/provider context changed while backtest history was loading.")
        }
    }

    fun refresh(onError: (String) -> Unit) {
        scope.launch {
            repository.refreshCandles(symbolFlow.value, timeframeFlow.value)
                .onFailure { e -> onError(e.message ?: "Failed to load market data") }
        }
    }

    fun onSymbolChange(symbol: String) {
        symbolFlow.value = symbol
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        timeframeFlow.value = timeframe
    }

    fun resetPrimaryChartContext() {
        marketProcessingJob?.cancel()
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        liveTickGate.reset()
        liveRecoveryGate.reset()
        synchronized(explicitGenerationLock) { explicitProcessingGeneration += 1L }
        currentObservedCandles = SourcedCandles.EMPTY
        clearPrependedHistory()
    }

    fun connectLive() {
        scope.launch { webSocket.subscribe(symbolFlow.value, timeframeFlow.value) }
    }

    fun disconnectLive() {
        scope.launch { webSocket.disconnectAll() }
    }

    fun toggleLive(currentlyEnabled: Boolean) {
        if (!currentlyEnabled) connectLive() else disconnectLive()
    }

    fun resubscribeLive() {
        scope.launch {
            webSocket.disconnectAll()
            webSocket.subscribe(symbolFlow.value, timeframeFlow.value)
        }
    }

    data class HistoryPrefetchResult(
        val totalVisibleBars: Int,
        val oldestTimestamp: Long,
        val reachedTarget: Boolean,
        val providerExhausted: Boolean,
    )

    companion object {
        const val HISTORY_PAGE_SIZE = 500
        const val MAX_BACKTEST_VISIBLE_BARS = 20_000
    }
}

internal class ConcatenatedCandleList(
    private val older: List<Candle>,
    private val newer: List<Candle>,
) : AbstractList<Candle>() {
    override val size: Int = older.size + newer.size

    override fun get(index: Int): Candle =
        if (index < older.size) older[index] else newer[index - older.size]
}
