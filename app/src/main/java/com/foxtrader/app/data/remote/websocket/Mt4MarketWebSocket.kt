package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Bridges the [Mt4QuoteStream] (bid/ask ticks from MetaApi) into the app's
 * [MarketWebSocket] candle-update contract, so the chart can render live MT4
 * prices just like any other provider.
 *
 * MT4 quotes carry no candle structure, so this adapter aggregates each quote's
 * mid price into per-(symbol, timeframe) forming candles and emits
 * [TickUpdate]s — mirroring what Binance/Bybit/polygon already do. The chart's
 * repository layer upserts these into Room, so the offline-first pipeline
 * (and every indicator/analysis layer) works unchanged.
 *
 * Connection lifecycle is delegated to [Mt4QuoteStream]; a connection is only
 * attempted once both the MetaApi token and a provisioned account ID exist
 * (i.e. the user has logged into MT4). Until then the adapter stays
 * disconnected and does not emit.
 */
@Singleton
class Mt4MarketWebSocket @Inject constructor(
    private val quoteStream: Mt4QuoteStream,
    private val appPreferences: AppPreferences,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {

    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 64)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    private val lock = Any()
    private val subscriptions = mutableSetOf<Pair<String, Timeframe>>()
    private val buckets = mutableMapOf<Pair<String, Timeframe>, CandleBucket>()
    private var connectAttempted = false

    init {
        // Forward the underlying stream's connection state.
        scope.launch { quoteStream.connectionState.collect { _connectionState.value = it } }
        // Aggregate quotes into candles.
        scope.launch {
            quoteStream.quotes.collect { quote -> onQuote(quote) }
        }
    }

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val pair = symbol.uppercase() to timeframe
        synchronized(lock) {
            if (subscriptions.add(pair)) {
                quoteStream.subscribe(symbol)
                ensureConnectedLocked()
            }
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val pair = symbol.uppercase() to timeframe
        synchronized(lock) {
            subscriptions.remove(pair)
            buckets.remove(pair)
            if (subscriptions.isEmpty()) {
                quoteStream.disconnect()
                connectAttempted = false
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override suspend fun disconnectAll() {
        synchronized(lock) {
            subscriptions.clear()
            buckets.clear()
            quoteStream.disconnect()
            connectAttempted = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Attempts to start the MT4 stream. Requires both a configured MetaApi
     * token and a provisioned account ID (i.e. a completed login). If either is
     * missing we stay disconnected — the user must connect an MT4 account first.
     */
    private fun ensureConnectedLocked() {
        // Read the token from either configured location (Settings stores it via
        // setApiKey(MT4, ...), login flow may set it via setMetaApiToken).
        val token = appPreferences.getMetaApiToken()
            ?: appPreferences.getApiKey(com.foxtrader.app.domain.model.DataProvider.MT4)
        val accountId = appPreferences.getMetaApiAccountId()
        if (token.isNullOrBlank() || accountId.isNullOrBlank()) {
            connectAttempted = true
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (connectAttempted && _connectionState.value == ConnectionState.CONNECTED) return
        connectAttempted = true
        quoteStream.connect(token, accountId)
    }

    private fun onQuote(quote: Mt4Quote) {
        val price = (quote.bid + quote.ask) / 2.0
        if (!price.isFinite() || price <= 0.0) return

        val symbol = quote.symbol.uppercase()
        val snapshot = synchronized(lock) { subscriptions.toList() }

        for ((subSymbol, timeframe) in snapshot) {
            if (!subSymbol.equals(symbol, ignoreCase = true)) continue
            val key = subSymbol to timeframe
            updateBucket(key, timeframe, quote, price)
        }
    }

    private fun updateBucket(key: Pair<String, Timeframe>, timeframe: Timeframe, quote: Mt4Quote, price: Double) {
        val widthMs = timeframe.minutes * 60_000L
        val start = (quote.timestamp / widthMs) * widthMs

        var rollover: CandleBucket? = null
        var bucket: CandleBucket
        synchronized(lock) {
            bucket = buckets[key] ?: CandleBucket(start, price).also { buckets[key] = it }
            if (bucket.start != start) {
                // Bar rolled over — emit the completed one, then start a new bucket.
                rollover = bucket
                bucket = CandleBucket(start, price)
                buckets[key] = bucket
            } else {
                bucket.high = max(bucket.high, price)
                bucket.low = min(bucket.low, price)
                bucket.close = price
                bucket.volume += 1.0
            }
        }

        rollover?.let { emitCandle(key, it, isBarClose = true) }
        emitCandle(key, bucket, isBarClose = false)
    }

    private fun emitCandle(key: Pair<String, Timeframe>, bucket: CandleBucket, isBarClose: Boolean) {
        val candle = Candle(
            timestamp = bucket.start,
            open = bucket.open,
            high = bucket.high,
            low = bucket.low,
            close = bucket.close,
            volume = bucket.volume,
        )
        _ticks.tryEmit(
            TickUpdate(
                symbol = key.first,
                timeframe = key.second,
                candle = candle,
                isBarClose = isBarClose,
            ),
        )
    }

    /** In-flight forming candle for a (symbol, timeframe) bucket. */
    private class CandleBucket(
        var start: Long,
        var open: Double,
        var high: Double = open,
        var low: Double = open,
        var close: Double = open,
        var volume: Double = 1.0,
    )
}
