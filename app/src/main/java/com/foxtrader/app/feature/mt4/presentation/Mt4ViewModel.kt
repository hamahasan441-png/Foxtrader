package com.foxtrader.app.feature.mt4.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.repository.Mt4Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the MT4 connection + live-trading screens.
 *
 * Manages the MT4 lifecycle (login, restore, disconnect), broker search, live
 * quote streaming for the trade panel, and the two-step confirm flow for
 * placing/closing live orders. On connect it reconciles any UNKNOWN order
 * receipts left from a previous session so they are never double-submitted.
 */
@HiltViewModel
class Mt4ViewModel @Inject constructor(
    private val mt4Repository: Mt4Repository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<Mt4UiState>(Mt4UiState.Disconnected())
    val uiState: StateFlow<Mt4UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var quoteJob: Job? = null

    init {
        // Prefill the form with the last known login/server (never the password).
        val last = mt4Repository.getLastConnection()
        if (last != null) {
            _uiState.update {
                (it as? Mt4UiState.Disconnected)?.copy(
                    login = last.login.toString(),
                    server = last.server,
                    error = null,
                ) ?: it
            }
        }
        restoreConnection()
    }

    private fun onConnectedState(state: Mt4UiState.Connected) {
        _uiState.value = state.copy(
            liveModeEnabled = mt4Repository.isLiveModeEnabled(),
            killSwitchEngaged = mt4Repository.isKillSwitchEngaged(),
        )
        subscribeQuotes(state.tradeSymbol)
        viewModelScope.launch {
            val unresolved = mt4Repository.reconcileUnknownOrders()
            if (unresolved > 0) {
                _uiState.update { current ->
                    if (current is Mt4UiState.Connected) {
                        current.copy(
                            notice = "$unresolved order(s) have an unknown outcome. " +
                                "Check your MT4 positions before trading. They are not retried automatically."
                        )
                    } else current
                }
            }
        }
    }

    // --- Form input (Disconnected state only) ---

    fun onLoginChange(value: String) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(login = value.trim(), error = null) }
        }
    }

    fun onPasswordChange(value: String) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(password = value, error = null) }
        }
    }

    fun onServerChange(value: String) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(server = value.trim(), error = null) }
        }
    }

    fun onBrokerSelected(broker: Mt4Broker) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            val server = broker.servers.firstOrNull() ?: return
            _uiState.update { current.copy(server = server, error = null) }
        }
    }

    fun searchBrokers(query: String) {
        val current = _uiState.value
        if (current !is Mt4UiState.Disconnected) return
        _uiState.update { (it as? Mt4UiState.Disconnected)?.copy(brokerQuery = query) ?: it }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val results = mt4Repository.searchBrokers(query)
            val latest = _uiState.value
            if (latest is Mt4UiState.Disconnected) {
                _uiState.update { latest.copy(brokerResults = results) }
            }
        }
    }

    // --- Connection ---

    fun connect() {
        val current = _uiState.value
        if (current !is Mt4UiState.Disconnected || !current.canSubmit) return

        val loginInt = current.login.toIntOrNull()
        if (loginInt == null) {
            _uiState.value = current.copy(error = "Login must be a number")
            return
        }

        val credentials = Mt4Credentials(
            login = loginInt,
            password = current.password,
            server = current.server,
        )

        _uiState.value = Mt4UiState.Connecting

        viewModelScope.launch {
            mt4Repository.connect(credentials)
                .onSuccess { accountInfo ->
                    val positions = mt4Repository.getPositions().getOrDefault(emptyList())
                    onConnectedState(
                        Mt4UiState.Connected(accountInfo = accountInfo, positions = positions)
                    )
                }
                .onFailure { e ->
                    _uiState.value = Mt4UiState.Disconnected(
                        login = current.login,
                        password = current.password,
                        server = current.server,
                        error = e.message ?: "Connection failed. Please check your credentials.",
                    )
                }
        }
    }

    private fun restoreConnection() {
        if (!mt4Repository.isConnected()) return
        viewModelScope.launch {
            mt4Repository.getAccountInfo()
                .onSuccess { accountInfo ->
                    val positions = mt4Repository.getPositions().getOrDefault(emptyList())
                    onConnectedState(
                        Mt4UiState.Connected(accountInfo = accountInfo, positions = positions)
                    )
                }
                .onFailure {
                    _uiState.value = Mt4UiState.Disconnected(
                        login = _uiState.value.login,
                        server = _uiState.value.server,
                    )
                }
        }
    }

    private fun subscribeQuotes(symbol: String) {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            mt4Repository.streamQuotes(listOf(symbol)).collect { quote ->
                _uiState.update { current ->
                    if (current is Mt4UiState.Connected && quote.symbol.equals(symbol, ignoreCase = true)) {
                        current.copy(quote = quote)
                    } else current
                }
            }
        }
    }

    // --- Live trading controls ---

    fun toggleLiveMode() {
        val current = _uiState.value
        if (current !is Mt4UiState.Connected) return
        val enabled = !current.liveModeEnabled
        mt4Repository.setLiveModeEnabled(enabled)
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(liveModeEnabled = enabled) ?: it }
    }

    fun engageKillSwitch() {
        mt4Repository.setKillSwitch(true)
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(killSwitchEngaged = true) ?: it }
    }

    fun disengageKillSwitch() {
        mt4Repository.setKillSwitch(false)
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(killSwitchEngaged = false) ?: it }
    }

    // --- Trade form ---

    fun onTradeSymbolChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(tradeSymbol = value.trim().uppercase(), notice = null) ?: it }
        _uiState.value.let { if (it is Mt4UiState.Connected) subscribeQuotes(it.tradeSymbol) }
    }

    fun onDirectionChange(direction: Direction) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(tradeDirection = direction, notice = null) ?: it }
    }

    fun onLotsChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(lotsInput = value, notice = null) ?: it }
    }

    fun onSlChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(slInput = value, notice = null) ?: it }
    }

    fun onTpChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(tpInput = value, notice = null) ?: it }
    }

    fun dismissNotice() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = null) ?: it }
    }

    // --- Two-step confirmation ---

    /** Step 1: validate the form and open the confirmation summary. */
    fun requestOrderConfirmation() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val lots = current.lotsInput.toDoubleOrNull()
        if (lots == null || lots <= 0.0) {
            _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Enter a valid volume (lots).") ?: it }
            return
        }
        val entry = current.lastPrice
        if (entry == null || entry <= 0.0) {
            _uiState.update {
                (it as? Mt4UiState.Connected)?.copy(notice = "No live price available yet. Connect your MT4 feed.") ?: it
            }
            return
        }
        val sl = current.slInput.toDoubleOrNull()?.takeIf { it > 0.0 }
        val tp = current.tpInput.toDoubleOrNull()?.takeIf { it > 0.0 }
        _uiState.update { state ->
            (state as? Mt4UiState.Connected)?.copy(
                pendingOrder = Mt4UiState.PendingTrade(
                    symbol = current.tradeSymbol,
                    direction = current.tradeDirection,
                    lots = lots,
                    entryPrice = entry,
                    stopLoss = sl,
                    takeProfit = tp,
                ),
                notice = null,
            ) ?: state
        }
    }

    /** Step 2: confirm and place the order through the safety pipeline. */
    fun confirmOrder() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val pending = current.pendingOrder ?: return
        if (current.isPlacing) return

        _uiState.update { (it as? Mt4UiState.Connected)?.copy(isPlacing = true, notice = null) ?: it }
        viewModelScope.launch {
            val result = mt4Repository.placeTrade(
                symbol = pending.symbol,
                type = if (pending.direction == Direction.BULLISH) Mt4OrderType.BUY else Mt4OrderType.SELL,
                lots = pending.lots,
                sl = pending.stopLoss,
                tp = pending.takeProfit,
                confirmationTimestamp = System.currentTimeMillis(),
            )
            _uiState.update { state ->
                val s = (state as? Mt4UiState.Connected)?.copy(isPlacing = false, pendingOrder = null) ?: state
                if (result.isSuccess) {
                    (s as? Mt4UiState.Connected)?.copy(notice = "Order placed. Ticket #${result.getOrThrow()}") ?: s
                } else {
                    (s as? Mt4UiState.Connected)?.copy(notice = result.exceptionOrNull()?.message ?: "Order failed") ?: s
                }
            }
            refreshPositions()
        }
    }

    fun cancelOrder() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(pendingOrder = null) ?: it }
    }

    // --- Close position ---

    fun closePosition(ticket: Long) {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        if (current.isPlacing) return
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(isPlacing = true, notice = null) ?: it }
        viewModelScope.launch {
            val result = mt4Repository.closeTrade(ticket, System.currentTimeMillis())
            _uiState.update { state ->
                val s = (state as? Mt4UiState.Connected)?.copy(isPlacing = false) ?: state
                if (result.isSuccess) {
                    (s as? Mt4UiState.Connected)?.copy(notice = "Position #$ticket closed.") ?: s
                } else {
                    (s as? Mt4UiState.Connected)?.copy(notice = result.exceptionOrNull()?.message ?: "Close failed") ?: s
                }
            }
            refreshPositions()
        }
    }

    // --- Connected state actions ---

    fun refreshPositions() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(isRefreshing = true) ?: it }
        viewModelScope.launch {
            mt4Repository.getPositions()
                .onSuccess { positions ->
                    _uiState.update { (it as? Mt4UiState.Connected)?.copy(positions = positions, isRefreshing = false) ?: it }
                }
                .onFailure {
                    _uiState.update { (it as? Mt4UiState.Connected)?.copy(isRefreshing = false) ?: it }
                }
        }
    }

    fun disconnect() {
        quoteJob?.cancel()
        viewModelScope.launch {
            mt4Repository.disconnect()
            _uiState.value = Mt4UiState.Disconnected(
                login = mt4Repository.getLastConnection()?.login?.toString().orEmpty(),
                server = mt4Repository.getLastConnection()?.server.orEmpty(),
            )
        }
    }
}
