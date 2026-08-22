package com.foxtrader.app.data.remote.deriv

import com.foxtrader.app.di.DerivApiClient
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.deriv.DerivConnectionState
import com.foxtrader.app.domain.model.deriv.DerivTick
import com.foxtrader.app.domain.usecase.deriv.DerivRequestBuilder
import com.foxtrader.app.domain.usecase.marketdata.MarketAssetClass
import com.foxtrader.app.domain.usecase.marketdata.MarketSymbolClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated public-market-data session for Deriv charts.
 *
 * This deliberately does NOT use [com.foxtrader.app.domain.repository.DerivRepository]:
 * the repository owns the authenticated Deriv trading session and switching it to
 * the public WebSocket would invalidate account-bound state. Chart/history traffic
 * therefore gets its own public-only [DerivWebSocketClient] instance.
 */
@Singleton
class DerivMarketDataSource @Inject constructor(
    @DerivApiClient client: OkHttpClient,
    json: Json,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val ws = DerivWebSocketClient(client, json, io)
    private val connectMutex = Mutex()

    val connectionState: StateFlow<DerivConnectionState> = ws.state

    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        beforeTimestampMs: Long? = null,
    ): List<Candle> {
        val requested = symbol.trim()
        require(requested.isNotBlank()) { "Deriv symbol is required" }
        val transportSymbol = toDerivTransportSymbol(requested)
        val safeLimit = limit.coerceIn(1, MAX_HISTORY_BARS)
        val granularity = timeframeToGranularitySeconds(timeframe)
        val endEpochSeconds = beforeTimestampMs?.let { ms ->
            // `end` is inclusive. Subtract one second so paging cannot return the
            // boundary candle again and create a load-older loop.
            ((ms / 1_000L) - 1L).coerceAtLeast(0L)
        }

        ensureConnected()
        val reqId = ws.nextReqId()
        val root = ws.request(
            DerivRequestBuilder.ticksHistory(
                symbol = transportSymbol,
                granularitySeconds = granularity,
                count = safeLimit,
                reqId = reqId,
                endEpochSeconds = endEpochSeconds,
            ),
            reqId,
        )

        val candles = (root["candles"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val epoch = obj.longValue("epoch") ?: return@mapNotNull null
            val open = obj.number("open") ?: return@mapNotNull null
            val high = obj.number("high") ?: return@mapNotNull null
            val low = obj.number("low") ?: return@mapNotNull null
            val close = obj.number("close") ?: return@mapNotNull null
            if (epoch <= 0L || !validOhlc(open, high, low, close)) return@mapNotNull null
            Candle(
                timestamp = epoch * 1_000L,
                open = open,
                high = high,
                low = low,
                close = close,
                // Deriv candle-history payload does not expose exchange volume.
                // Keep it explicitly unavailable rather than fabricating volume.
                volume = 0.0,
            )
        }

        return candles
            .asSequence()
            .filter { beforeTimestampMs == null || it.timestamp < beforeTimestampMs }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .toList()
            .takeLast(safeLimit)
    }

    /**
     * One public Deriv tick subscription. Cancellation forgets the remote
     * subscription, but the shared public socket stays available for history or
     * other chart symbols.
     */
    fun streamTicks(symbol: String): Flow<DerivTick> = flow {
        val requested = symbol.trim()
        require(requested.isNotBlank()) { "Deriv symbol is required" }
        val transportSymbol = toDerivTransportSymbol(requested)
        ensureConnected()

        val reqId = ws.nextReqId()
        val initial = ws.request(DerivRequestBuilder.ticks(transportSymbol, reqId, subscribe = true), reqId)
        val subscriptionId = initial["subscription"]?.jsonObject?.string("id")
        val generation = ws.sessionGeneration()
        initial.parseTick()?.let { emit(it) }

        try {
            ws.messagesForGeneration(generation).collect { root ->
                if (!ws.isCurrentGeneration(generation)) {
                    throw DerivApiException("Deriv public market-data session changed")
                }
                val messageSubscription = root["subscription"]?.jsonObject?.string("id")
                if (!subscriptionId.isNullOrBlank() && messageSubscription != subscriptionId) {
                    return@collect
                }
                val tick = root.parseTick() ?: return@collect
                if (tick.symbol.equals(transportSymbol, ignoreCase = true)) emit(tick)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            if (!subscriptionId.isNullOrBlank() && ws.state.value == DerivConnectionState.CONNECTED) {
                withContext(NonCancellable) {
                    runCatching {
                        val forgetId = ws.nextReqId()
                        ws.request(DerivRequestBuilder.forget(subscriptionId, forgetId), forgetId)
                    }
                }
            }
        }
    }

    suspend fun ensureConnected() {
        if (ws.state.value == DerivConnectionState.CONNECTED) return
        // History refresh and multiple live symbols can start concurrently. The
        // low-level client intentionally replaces an in-progress connection, so
        // serialize public-session establishment here to prevent two callers
        // from invalidating each other's WebSocket generation.
        connectMutex.withLock {
            if (ws.state.value != DerivConnectionState.CONNECTED) {
                ws.connectPublic().getOrThrow()
            }
        }
    }


    /**
     * Deriv's public API uses provider-native identifiers. For ordinary FX
     * symbols FoxTrader accepts the chart-friendly aliases `EURUSD` /
     * `EUR/USD` and translates only that well-defined case to `frxEURUSD`.
     * Synthetic indices and every already-native/unknown identifier are sent
     * untouched (apart from surrounding whitespace), so names such as `R_100`
     * can never be corrupted by generic symbol normalisation.
     */
    private fun toDerivTransportSymbol(symbol: String): String {
        val trimmed = symbol.trim()
        if (trimmed.startsWith("frx", ignoreCase = true)) return trimmed
        return if (MarketSymbolClassifier.classify(trimmed) == MarketAssetClass.FOREX) {
            "frx${MarketSymbolClassifier.canonicalSymbol(trimmed)}"
        } else {
            trimmed
        }
    }

    private fun timeframeToGranularitySeconds(timeframe: Timeframe): Int {
        val seconds = timeframe.minutes.toLong() * 60L
        require(seconds in 1..Int.MAX_VALUE.toLong()) {
            "Unsupported Deriv timeframe: ${timeframe.label}"
        }
        return seconds.toInt()
    }

    private fun JsonObject.parseTick(): DerivTick? {
        val obj = this["tick"] as? JsonObject ?: return null
        val symbol = obj.string("symbol") ?: obj.string("underlying_symbol") ?: return null
        val quote = obj.number("quote") ?: return null
        val epoch = obj.longValue("epoch") ?: return null
        if (!quote.isFinite() || quote <= 0.0 || epoch <= 0L) return null
        return DerivTick(symbol = symbol, quote = quote, epochSeconds = epoch, pipSize = obj.intValue("pip_size"))
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.let { primitive ->
        primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }
    private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.let { primitive ->
        primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }
    private fun JsonObject.intValue(key: String): Int? = this[key]?.jsonPrimitive?.let { primitive ->
        primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private fun validOhlc(open: Double, high: Double, low: Double, close: Double): Boolean {
        if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) return false
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return false
        return high >= max(open, close) && low <= min(open, close) && high >= low
    }

    private companion object {
        const val MAX_HISTORY_BARS = 5_000
    }
}
