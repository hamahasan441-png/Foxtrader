package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.api.MetaApiDataSource
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.Mt4Quote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reliable Android market-price stream for a MetaApi MT4/MT5 account.
 *
 * MetaApi's low-latency streaming API is Socket.IO, not a raw RFC-6455 JSON
 * socket. A previous implementation connected raw OkHttp WebSocket traffic and
 * expected a non-MetaApi `type=quotes` payload, which could never be relied on.
 * Phase 12 wires the documented Socket.IO price channel and retains MetaApi's
 * current-price REST endpoint only as a bounded watchdog/fallback. Trading does
 * not depend on a best-effort UI stream: the repository still re-reads the
 * executable quote immediately before a broker submission.
 *
 * Safety properties:
 *  - credentials are never logged;
 *  - account switches cancel the old generation and clear every cached quote;
 *  - stale/old polling results cannot cross a session boundary;
 *  - malformed/non-finite/out-of-order quotes are dropped;
 *  - 401/403 is terminal AUTH_FAILED; transient errors back off and reconnect;
 *  - no price is retained after disconnect, so execution cannot consume a
 *    quote from a previous broker account.
 */
@Singleton
class Mt4QuoteStream @Inject constructor(
    private val dataSource: MetaApiDataSource,
    private val streamingClient: MetaApiStreamingClient,
) {

    fun latestQuote(symbol: String): Mt4Quote? = latestQuotes[symbol.trim().uppercase()]

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _quotes = MutableSharedFlow<Mt4Quote>(extraBufferCapacity = 64)
    val quotes: Flow<Mt4Quote> = _quotes.asSharedFlow()

    private val lock = Any()
    private val subscribedSymbols = linkedSetOf<String>()
    private val latestQuotes = ConcurrentHashMap<String, Mt4Quote>()
    private val lastQuoteTimeBySymbol = mutableMapOf<String, Long>()

    private var authToken: String = ""
    private var accountId: String = ""
    private var generation: Long = 0L
    private var pollJob: Job? = null
    private var streamConnectJob: Job? = null

    init {
        scope.launch {
            streamingClient.quotes.collect { quote ->
                val snapshot = synchronized(lock) {
                    if (authToken.isBlank() || accountId.isBlank()) null
                    else Triple(generation, authToken, accountId)
                } ?: return@collect
                acceptQuote(snapshot.first, snapshot.second, snapshot.third, quote)
            }
        }
        scope.launch {
            streamingClient.state.collect { streamState ->
                synchronized(lock) {
                    if (authToken.isBlank() || accountId.isBlank()) return@synchronized
                    when (streamState) {
                        ConnectionState.CONNECTED -> _connectionState.value = ConnectionState.CONNECTED
                        ConnectionState.AUTH_FAILED -> {
                            latestQuotes.clear()
                            lastQuoteTimeBySymbol.clear()
                            _connectionState.value = ConnectionState.AUTH_FAILED
                        }
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                            if (_connectionState.value != ConnectionState.CONNECTED) {
                                _connectionState.value = streamState
                            }
                        }
                        ConnectionState.STALE, ConnectionState.ERROR, ConnectionState.FATAL -> {
                            if (_connectionState.value != ConnectionState.AUTH_FAILED) {
                                _connectionState.value = ConnectionState.RECONNECTING
                            }
                        }
                        ConnectionState.DISCONNECTED -> Unit
                    }
                }
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_CONSECUTIVE_FAILURES = 8
        private const val STREAM_RESTART_INTERVAL_MS = 30_000L
    }

    fun connect(token: String, metaApiAccountId: String) {
        val normalizedToken = token.trim()
        val normalizedId = metaApiAccountId.trim()
        if (normalizedToken.isBlank() || normalizedId.isBlank()) {
            synchronized(lock) {
                disconnectLocked(clearSubscriptions = false)
                _connectionState.value = ConnectionState.AUTH_FAILED
            }
            return
        }

        synchronized(lock) {
            val sameSession = authToken == normalizedToken && accountId == normalizedId && pollJob?.isActive == true
            if (sameSession) return

            // Session replacement is a hard boundary. Do not let any prior
            // account quote survive for even one polling interval.
            generation++
            pollJob?.cancel()
            pollJob = null
            streamConnectJob?.cancel()
            streamConnectJob = null
            streamingClient.disconnect(clearSubscriptions = false)
            latestQuotes.clear()
            lastQuoteTimeBySymbol.clear()
            dataSource.invalidateAccountRouting(accountId.takeIf { it.isNotBlank() })

            authToken = normalizedToken
            accountId = normalizedId
            _connectionState.value = ConnectionState.CONNECTING
            val myGeneration = generation
            streamingClient.replaceSubscriptions(subscribedSymbols)
            streamConnectJob = scope.launch {
                try {
                    val region = dataSource.getAccountRegion(normalizedToken, normalizedId)
                    val stillCurrent = synchronized(lock) {
                        generation == myGeneration && authToken == normalizedToken && accountId == normalizedId
                    }
                    if (stillCurrent) {
                        streamingClient.connect(normalizedToken, normalizedId, region)
                        streamingClient.replaceSubscriptions(synchronized(lock) { subscribedSymbols.toList() })
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // REST watchdog below remains the protocol-correct fallback.
                    synchronized(lock) {
                        if (generation == myGeneration && _connectionState.value != ConnectionState.AUTH_FAILED) {
                            _connectionState.value = ConnectionState.RECONNECTING
                        }
                    }
                }
            }
            startPollingLocked(myGeneration, normalizedToken, normalizedId)
        }
    }

    fun replaceSubscriptions(symbols: List<String>) {
        val normalized = symbols.mapNotNull { normalizeSymbolOrNull(it) }.toSet()
        synchronized(lock) {
            subscribedSymbols.clear()
            subscribedSymbols.addAll(normalized)
            latestQuotes.keys.retainAll(normalized)
            lastQuoteTimeBySymbol.keys.retainAll(normalized)
            streamingClient.replaceSubscriptions(normalized)
        }
    }

    fun subscribe(symbol: String) {
        val normalized = normalizeSymbolOrNull(symbol) ?: return
        synchronized(lock) {
            subscribedSymbols.add(normalized)
            streamingClient.replaceSubscriptions(subscribedSymbols)
        }
    }

    fun unsubscribe(symbol: String) {
        val normalized = normalizeSymbolOrNull(symbol) ?: return
        synchronized(lock) {
            subscribedSymbols.remove(normalized)
            latestQuotes.remove(normalized)
            lastQuoteTimeBySymbol.remove(normalized)
            streamingClient.replaceSubscriptions(subscribedSymbols)
        }
    }

    fun disconnect() {
        synchronized(lock) { disconnectLocked(clearSubscriptions = true) }
    }

    private fun disconnectLocked(clearSubscriptions: Boolean) {
        generation++
        pollJob?.cancel()
        pollJob = null
        streamConnectJob?.cancel()
        streamConnectJob = null
        streamingClient.disconnect(clearSubscriptions = clearSubscriptions)
        latestQuotes.clear()
        lastQuoteTimeBySymbol.clear()
        dataSource.invalidateAccountRouting(accountId.takeIf { it.isNotBlank() })
        authToken = ""
        accountId = ""
        if (clearSubscriptions) subscribedSymbols.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun startPollingLocked(myGeneration: Long, token: String, id: String) {
        pollJob = scope.launch {
            var consecutiveFailures = 0
            var backoffMs = INITIAL_BACKOFF_MS
            var lastStreamRestartAttempt = 0L

            while (isActive) {
                val symbols = synchronized(lock) {
                    if (generation != myGeneration || authToken != token || accountId != id) return@launch
                    subscribedSymbols.toList()
                }

                if (symbols.isEmpty()) {
                    // Credentials are valid but there is no active market-data
                    // request yet. Keep session ready without making network calls.
                    synchronized(lock) {
                        if (generation == myGeneration && _connectionState.value != ConnectionState.AUTH_FAILED) {
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                // Socket.IO is primary. REST polling is a watchdog/fallback
                // only; it stops consuming REST quota while streaming is healthy.
                if (streamingClient.state.value == ConnectionState.CONNECTED) {
                    synchronized(lock) {
                        if (generation == myGeneration) _connectionState.value = ConnectionState.CONNECTED
                    }
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                if (streamingClient.state.value == ConnectionState.AUTH_FAILED) {
                    synchronized(lock) {
                        if (generation == myGeneration) _connectionState.value = ConnectionState.AUTH_FAILED
                    }
                    return@launch
                }

                // Socket.IO's Java client performs its own bounded reconnects.
                // If those retries are exhausted (or a local overflow forced the
                // socket generation closed), periodically rebuild the streaming
                // session while REST remains an independent watchdog/fallback.
                val now = System.currentTimeMillis()
                if (now - lastStreamRestartAttempt >= STREAM_RESTART_INTERVAL_MS) {
                    lastStreamRestartAttempt = now
                    try {
                        val region = dataSource.getAccountRegion(token, id)
                        val stillCurrent = synchronized(lock) {
                            generation == myGeneration && authToken == token && accountId == id
                        }
                        if (stillCurrent) {
                            streamingClient.connect(token, id, region)
                            streamingClient.replaceSubscriptions(symbols)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // REST fallback below is deliberately independent of
                        // streaming recovery and remains available here.
                    }
                }

                var requestSuccessCount = 0
                var terminalAuthFailure = false
                for (symbol in symbols) {
                    if (!isActive) break
                    try {
                        val quote = dataSource.getCurrentPrice(token, id, symbol)
                        // A valid HTTP/MetaApi response proves connectivity even
                        // when the market has not produced a newer tick. Keep
                        // quote freshness separate: acceptQuote still refuses to
                        // refresh the cached timestamp on duplicate/old ticks.
                        requestSuccessCount++
                        acceptQuote(myGeneration, token, id, quote)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (http: HttpException) {
                        if (http.code() == 401 || http.code() == 403) {
                            terminalAuthFailure = true
                            break
                        }
                    } catch (_: Exception) {
                        // Transient network/terminal synchronization failure.
                        // State/backoff is handled once per polling cycle below.
                    }
                }

                if (terminalAuthFailure) {
                    synchronized(lock) {
                        if (generation == myGeneration) {
                            latestQuotes.clear()
                            lastQuoteTimeBySymbol.clear()
                            _connectionState.value = ConnectionState.AUTH_FAILED
                        }
                    }
                    return@launch
                }

                if (requestSuccessCount > 0) {
                    consecutiveFailures = 0
                    backoffMs = INITIAL_BACKOFF_MS
                    synchronized(lock) {
                        if (generation == myGeneration) _connectionState.value = ConnectionState.CONNECTED
                    }
                    delay(POLL_INTERVAL_MS)
                } else {
                    consecutiveFailures++
                    synchronized(lock) {
                        if (generation == myGeneration) {
                            _connectionState.value = if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                ConnectionState.STALE
                            } else {
                                ConnectionState.RECONNECTING
                            }
                        }
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private fun acceptQuote(
        myGeneration: Long,
        token: String,
        id: String,
        quote: Mt4Quote,
    ): Boolean {
        if (!quote.bid.isFinite() || !quote.ask.isFinite() || quote.bid <= 0.0 || quote.ask < quote.bid || quote.timestamp <= 0L) {
            return false
        }
        val symbol = normalizeSymbolOrNull(quote.symbol) ?: return false
        synchronized(lock) {
            if (generation != myGeneration || authToken != token || accountId != id) return false
            if (symbol !in subscribedSymbols) return false
            val last = lastQuoteTimeBySymbol[symbol] ?: Long.MIN_VALUE
            if (quote.timestamp <= last) return false
            lastQuoteTimeBySymbol[symbol] = quote.timestamp
            val normalized = if (quote.symbol == symbol) quote else quote.copy(symbol = symbol)
            latestQuotes[symbol] = normalized
            _quotes.tryEmit(normalized)
            return true
        }
    }

    private fun normalizeSymbolOrNull(symbol: String): String? = symbol.trim().uppercase()
        .takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) }
}
