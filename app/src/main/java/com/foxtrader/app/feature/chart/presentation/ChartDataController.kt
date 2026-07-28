package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.SourcedCandles
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Manages market data loading, symbol/timeframe switching, candle merging, and
 * the prepended older-history buffer.
 *
 * This is a plain class instantiated by [ChartViewModel]. It does not participate
 * in Hilt dependency injection directly.
 */
internal class ChartDataController(
    private val repository: MarketRepository,
    private val webSocket: MarketWebSocket,
    private val scope: CoroutineScope,
    private val onMergedCandlesChanged: suspend (CandleSource, Boolean) -> Unit,
    private val onUpsertTick: suspend (String, Timeframe, Candle) -> Unit,
) {

    val symbolFlow = MutableStateFlow("EURUSD")
    val timeframeFlow = MutableStateFlow(Timeframe.M15)

    /** Latest hot-cache series observed from Room for the active chart. */
    var currentObservedCandles: SourcedCandles = SourcedCandles.EMPTY
        private set

    /** Older pages kept only in-memory so the Room hot cache can stay bounded. */
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMarket() {
        combine(symbolFlow, timeframeFlow) { symbol, tf -> symbol to tf }
            .flatMapLatest { (symbol, tf) -> repository.observeSourcedCandles(symbol, tf) }
            .distinctUntilChangedBy { sourced ->
                val list = sourced.candles
                val last = list.lastOrNull()
                "${sourced.source}:${list.size}:${last?.timestamp}:${last?.open}:" +
                    "${last?.high}:${last?.low}:${last?.close}:${last?.volume}"
            }
            .onEach { sourced ->
                currentObservedCandles = sourced
                rebuildMergedVisibleCandles()
                scope.launch {
                    onMergedCandlesChanged(sourced.source, true)
                }
            }
            .launchIn(scope)
    }

    fun observeWebSocketTicks() {
        webSocket.ticks
            .onEach { tick ->
                if (tick.symbol == symbolFlow.value && tick.timeframe == timeframeFlow.value) {
                    scope.launch {
                        onUpsertTick(tick.symbol, tick.timeframe, tick.candle)
                    }
                }
            }
            .launchIn(scope)
    }

    suspend fun processMergedCandles(preferIncremental: Boolean = false) {
        val sourceHint = currentObservedCandles.source
        val mergedSource = CandleSource.worstOf(
            buildList {
                add(sourceHint)
                if (prependedHistorySnapshot.isNotEmpty()) add(prependedHistorySource)
            }
        )
        onMergedCandlesChanged(mergedSource, preferIncremental)
    }

    fun rebuildMergedVisibleCandles() {
        val observed = currentObservedCandles.candles
        mergedVisibleCandles = when {
            prependedHistorySnapshot.isEmpty() -> observed
            observed.isEmpty() -> prependedHistorySnapshot
            else -> ConcatenatedCandleList(prependedHistorySnapshot, observed)
        }
    }

    fun clearPrependedHistory() {
        prependedHistory.clear()
        prependedHistorySnapshot = emptyList()
        prependedHistorySource = CandleSource.CACHED
        isLoadingOlder = false
        historyEndReached = false
        loadError = null
        rebuildMergedVisibleCandles()
    }

    fun loadOlderHistory(onStateChanged: (Boolean, Boolean, String?) -> Unit) {
        val beforeTimestamp = prependedHistory.firstOrNull()?.timestamp
            ?: currentObservedCandles.candles.firstOrNull()?.timestamp
            ?: return
        if (isLoadingOlder || historyEndReached) return

        scope.launch {
            isLoadingOlder = true
            loadError = null
            onStateChanged(true, false, null)
            repository.loadOlderCandles(
                symbol = symbolFlow.value,
                timeframe = timeframeFlow.value,
                beforeTimestamp = beforeTimestamp,
                limit = HISTORY_PAGE_SIZE,
            ).onSuccess { page ->
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
                    onMergedCandlesChanged(
                        CandleSource.worstOf(
                            buildList {
                                add(currentObservedCandles.source)
                                if (prependedHistorySnapshot.isNotEmpty()) add(prependedHistorySource)
                            }
                        ),
                        false,
                    )
                }
            }.onFailure { error ->
                isLoadingOlder = false
                loadError = error.message ?: "Failed to load older history"
                onStateChanged(false, false, loadError)
            }
        }
    }

    fun refresh(onError: (String) -> Unit) {
        scope.launch {
            repository.refreshCandles(symbolFlow.value, timeframeFlow.value)
                .onFailure { e ->
                    onError(e.message ?: "Failed to load market data")
                }
        }
    }

    fun onSymbolChange(symbol: String) {
        symbolFlow.value = symbol
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        timeframeFlow.value = timeframe
    }

    fun resetPrimaryChartContext() {
        currentObservedCandles = SourcedCandles.EMPTY
        clearPrependedHistory()
    }

    fun connectLive() {
        scope.launch {
            webSocket.subscribe(symbolFlow.value, timeframeFlow.value)
        }
    }

    fun disconnectLive() {
        scope.launch {
            webSocket.disconnectAll()
        }
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

    companion object {
        const val HISTORY_PAGE_SIZE = 500
    }
}

/**
 * Zero-copy concatenation of the prepended history and the Room-observed candles.
 */
internal class ConcatenatedCandleList(
    private val older: List<Candle>,
    private val newer: List<Candle>,
) : AbstractList<Candle>() {
    override val size: Int = older.size + newer.size

    override fun get(index: Int): Candle =
        if (index < older.size) older[index] else newer[index - older.size]
}
