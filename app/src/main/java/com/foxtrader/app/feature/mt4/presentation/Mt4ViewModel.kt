package com.foxtrader.app.feature.mt4.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4AccountProfile
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4PendingExpirationType
import com.foxtrader.app.domain.model.Mt4PendingOrderRequest
import com.foxtrader.app.domain.model.Mt4PositionProtection
import com.foxtrader.app.domain.model.Mt4PendingOrderSnapshot
import com.foxtrader.app.domain.model.Mt4PositionSnapshot
import com.foxtrader.app.domain.repository.Mt4Repository
import com.foxtrader.app.domain.usecase.execution.BrokerTradeDraftStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicLong
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
    private val brokerTradeDraftStore: BrokerTradeDraftStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<Mt4UiState>(Mt4UiState.Disconnected())
    val uiState: StateFlow<Mt4UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var quoteJob: Job? = null
    private var connectionJob: Job? = null
    private val sessionEpoch = AtomicLong(0L)

    init {
        // Prefill the form with the last known login/server (never the password).
        val last = mt4Repository.getLastConnection()
        if (last != null) {
            _uiState.update {
                (it as? Mt4UiState.Disconnected)?.copy(
                    login = last.login.toString(),
                    server = last.server,
                    platform = last.platform,
                    savedAccounts = mt4Repository.getSavedAccounts(),
                    error = null,
                ) ?: it
            }
        }
        if (last == null) {
            _uiState.update { current ->
                (current as? Mt4UiState.Disconnected)?.copy(
                    savedAccounts = mt4Repository.getSavedAccounts(),
                ) ?: current
            }
        }
        restoreConnection()
    }

    private fun onConnectedState(state: Mt4UiState.Connected, epoch: Long) {
        if (sessionEpoch.get() != epoch) return
        val draft = brokerTradeDraftStore.consume()
        val connected = state.copy(
            liveModeEnabled = mt4Repository.isLiveModeEnabled(),
            killSwitchEngaged = mt4Repository.isKillSwitchEngaged(),
            pendingOrder = null,
            tradeSymbol = draft?.symbol ?: state.tradeSymbol,
            tradeDirection = draft?.direction ?: state.tradeDirection,
            slInput = draft?.stopLoss?.toString() ?: state.slInput,
            tpInput = draft?.takeProfit?.toString() ?: state.tpInput,
            notice = draft?.let {
                "Chart setup imported from ${it.source}${it.confidence?.let { c -> " · confidence $c%" }.orEmpty()}. Review volume and broker price before placing."
            } ?: state.notice,
        )
        _uiState.value = connected
        subscribeQuotes(connected.tradeSymbol, connected.accountInfo.login, connected.accountInfo.server, epoch)
        viewModelScope.launch {
            val reconciliation = try {
                Result.success(mt4Repository.reconcileUnknownOrders())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Result.failure(e)
            }
            val journalSync = mt4Repository.synchronizeBrokerJournal()
            if (sessionEpoch.get() != epoch) return@launch
            _uiState.update { current ->
                if (current is Mt4UiState.Connected &&
                    current.accountInfo.login == state.accountInfo.login &&
                    current.accountInfo.server.equals(state.accountInfo.server, ignoreCase = true)
                ) {
                    reconciliation.fold(
                        onSuccess = { unresolved ->
                            if (unresolved > 0) {
                                current.copy(
                                    notice = "$unresolved order(s) have an unknown outcome. " +
                                        "Check your MT4/MT5 positions before trading. They are not retried automatically."
                                )
                            } else if (journalSync.isFailure) {
                                current.copy(
                                    notice = "Broker journal synchronization could not be verified: " +
                                        (journalSync.exceptionOrNull()?.message ?: "broker history unavailable") +
                                        ". Open broker state remains authoritative; no exit price/P&L is guessed."
                                )
                            } else if (journalSync.getOrNull()?.let { it > 0 } == true) {
                                current.copy(notice = "Broker journal is synchronized, but ${journalSync.getOrNull()} close record(s) are waiting for authoritative history deals. No exit price/P&L is guessed.")
                            } else current
                        },
                        onFailure = { error ->
                            current.copy(
                                notice = "Order reconciliation could not be verified: " +
                                    (error.message ?: "audit data unavailable") +
                                    ". Live safety remains fail-closed; verify broker positions before trading."
                            )
                        },
                    )
                } else current
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

    fun onPlatformChange(platform: String) {
        val normalized = platform.lowercase().takeIf { it == "mt4" || it == "mt5" } ?: return
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update { current.copy(platform = normalized, error = null) }
        }
    }

    fun onSavedAccountSelected(profile: Mt4AccountProfile) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            _uiState.update {
                current.copy(
                    login = profile.login.toString(),
                    server = profile.server,
                    platform = profile.platform,
                    password = "",
                    error = null,
                )
            }
        }
    }

    fun removeSavedAccount(profile: Mt4AccountProfile) {
        mt4Repository.removeSavedAccount(profile)
        _uiState.update { current ->
            (current as? Mt4UiState.Disconnected)?.copy(savedAccounts = mt4Repository.getSavedAccounts()) ?: current
        }
    }

    fun onBrokerSelected(broker: Mt4Broker) {
        val current = _uiState.value
        if (current is Mt4UiState.Disconnected) {
            val server = broker.servers.firstOrNull() ?: return
            val platform = if (broker.name.equals("Deriv", ignoreCase = true) || server.contains("MT5", ignoreCase = true)) "mt5" else current.platform
            _uiState.update { current.copy(server = server, platform = platform, error = null) }
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

        val loginLong = current.login.toLongOrNull()
        if (loginLong == null || loginLong <= 0L) {
            _uiState.value = current.copy(error = "Login must be a positive number")
            return
        }

        val credentials = Mt4Credentials(
            login = loginLong,
            password = current.password,
            server = current.server,
            platform = current.platform,
        )
        val epoch = sessionEpoch.incrementAndGet()
        connectionJob?.cancel()
        quoteJob?.cancel()
        _uiState.value = Mt4UiState.Connecting

        connectionJob = viewModelScope.launch {
            mt4Repository.connect(credentials)
                .onSuccess { accountInfo ->
                    if (sessionEpoch.get() != epoch) return@onSuccess
                    val positions = mt4Repository.getPositions().getOrDefault(emptyList())
                    val pendingOrders = mt4Repository.getPendingOrders().getOrDefault(emptyList())
                    if (sessionEpoch.get() != epoch) return@onSuccess
                    onConnectedState(
                        Mt4UiState.Connected(
                            accountInfo = accountInfo,
                            platform = current.platform,
                            positions = positions,
                            pendingOrders = pendingOrders,
                        ),
                        epoch,
                    )
                }
                .onFailure { e ->
                    if (sessionEpoch.get() != epoch) return@onFailure
                    _uiState.value = Mt4UiState.Disconnected(
                        login = current.login,
                        // Never keep a broker password in UI state after a failed
                        // network/auth attempt. Require explicit re-entry.
                        password = "",
                        server = current.server,
                        platform = current.platform,
                        savedAccounts = mt4Repository.getSavedAccounts(),
                        error = e.message ?: "Connection failed. Please check your credentials.",
                    )
                }
        }
    }

    private fun restoreConnection() {
        if (!mt4Repository.isConnected()) return
        val epoch = sessionEpoch.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            val result = mt4Repository.getAccountInfo()
            if (sessionEpoch.get() != epoch) return@launch

            if (result.isSuccess) {
                val accountInfo = result.getOrThrow()
                val positions = mt4Repository.getPositions().getOrDefault(emptyList())
                val pendingOrders = mt4Repository.getPendingOrders().getOrDefault(emptyList())
                if (sessionEpoch.get() != epoch) return@launch
                onConnectedState(
                    Mt4UiState.Connected(
                        accountInfo = accountInfo,
                        platform = mt4Repository.getLastConnection()?.platform ?: "mt4",
                        positions = positions,
                        pendingOrders = pendingOrders,
                    ),
                    epoch,
                )
            } else {
                val error = result.exceptionOrNull()
                // A persisted MetaApi id is only a connection hint. If the
                // broker/MetaApi session can no longer be validated, clear the
                // active local session so isConnected()/quote restoration do
                // not keep reporting a dead account indefinitely. Saved profile
                // metadata remains available for an explicit reconnect.
                mt4Repository.disconnect()
                if (sessionEpoch.get() != epoch) return@launch
                val last = mt4Repository.getLastConnection()
                _uiState.value = Mt4UiState.Disconnected(
                    login = last?.login?.toString().orEmpty(),
                    password = "",
                    server = last?.server.orEmpty(),
                    platform = last?.platform ?: "mt4",
                    savedAccounts = mt4Repository.getSavedAccounts(),
                    error = error?.message ?: "Saved broker session is no longer valid. Re-enter the password to reconnect.",
                )
            }
        }
    }

    private fun subscribeQuotes(symbol: String, expectedLogin: Long, expectedServer: String, epoch: Long = sessionEpoch.get()) {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            mt4Repository.streamQuotes(listOf(symbol)).collect { quote ->
                if (sessionEpoch.get() != epoch) return@collect
                _uiState.update { current ->
                    if (current is Mt4UiState.Connected &&
                        current.accountInfo.login == expectedLogin &&
                        current.accountInfo.server.equals(expectedServer, ignoreCase = true) &&
                        quote.symbol.equals(symbol, ignoreCase = true)
                    ) {
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
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(tradeSymbol = value.trim().uppercase(), notice = null, pendingOrder = null) ?: it }
        _uiState.value.let {
            if (it is Mt4UiState.Connected) {
                subscribeQuotes(it.tradeSymbol, it.accountInfo.login, it.accountInfo.server)
            }
        }
    }

    fun onDirectionChange(direction: Direction) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(tradeDirection = direction, notice = null, pendingOrder = null) ?: it }
    }

    fun onOrderEntryKindChange(kind: BrokerOrderEntryKind) {
        _uiState.update {
            (it as? Mt4UiState.Connected)?.copy(
                orderEntryKind = kind,
                pendingPriceInput = if (kind == BrokerOrderEntryKind.MARKET) "" else it.pendingPriceInput,
                pendingOrder = null,
                notice = null,
            ) ?: it
        }
    }

    fun onPendingPriceChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(pendingPriceInput = value, pendingOrder = null, notice = null) ?: it }
    }

    fun onPendingExpirationTypeChange(type: Mt4PendingExpirationType) {
        _uiState.update { state ->
            val connected = state as? Mt4UiState.Connected ?: return@update state
            connected.copy(
                pendingExpirationType = type,
                pendingExpirationInput = if (type == Mt4PendingExpirationType.GTC || type == Mt4PendingExpirationType.DAY) "" else connected.pendingExpirationInput,
                pendingOrder = null,
                notice = null,
            )
        }
    }

    fun onPendingExpirationInputChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(pendingExpirationInput = value, pendingOrder = null, notice = null) ?: it }
    }

    fun onLotsChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(lotsInput = value, notice = null, pendingOrder = null) ?: it }
    }

    fun onSlChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(slInput = value, notice = null, pendingOrder = null) ?: it }
    }

    fun onTpChange(value: String) {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(tpInput = value, notice = null, pendingOrder = null) ?: it }
    }

    fun dismissNotice() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = null) ?: it }
    }

    // --- Two-step confirmation ---

    /** Step 1: validate the form and open the confirmation summary. */
    fun requestOrderConfirmation() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        if (current.isPlacing) return
        val lots = current.lotsInput.toDoubleOrNull()
        if (lots == null || !lots.isFinite() || lots <= 0.0) {
            _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Enter a valid volume (lots).") ?: it }
            return
        }
        val quote = current.quote
        val marketEntry = quote?.let { if (current.tradeDirection == Direction.BULLISH) it.ask else it.bid }
        if (marketEntry == null || !marketEntry.isFinite() || marketEntry <= 0.0) {
            _uiState.update {
                (it as? Mt4UiState.Connected)?.copy(notice = "No live broker price available yet.") ?: it
            }
            return
        }
        val orderType = when (current.orderEntryKind) {
            BrokerOrderEntryKind.MARKET -> if (current.tradeDirection == Direction.BULLISH) Mt4OrderType.BUY else Mt4OrderType.SELL
            BrokerOrderEntryKind.LIMIT -> if (current.tradeDirection == Direction.BULLISH) Mt4OrderType.BUY_LIMIT else Mt4OrderType.SELL_LIMIT
            BrokerOrderEntryKind.STOP -> if (current.tradeDirection == Direction.BULLISH) Mt4OrderType.BUY_STOP else Mt4OrderType.SELL_STOP
        }
        val entry = if (current.orderEntryKind == BrokerOrderEntryKind.MARKET) {
            marketEntry
        } else {
            current.pendingPriceInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: run {
                _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Enter a valid pending open price.") ?: it }
                return
            }
        }
        val sl = parseOptionalPrice(current.slInput, "stop loss") ?: if (current.slInput.isBlank()) null else return
        val tp = parseOptionalPrice(current.tpInput, "take profit") ?: if (current.tpInput.isBlank()) null else return
        val expirationTime = if (current.orderEntryKind == BrokerOrderEntryKind.MARKET) {
            null
        } else {
            parsePendingExpiration(current.pendingExpirationType, current.pendingExpirationInput) ?:
                if (current.pendingExpirationType == Mt4PendingExpirationType.SPECIFIED ||
                    current.pendingExpirationType == Mt4PendingExpirationType.SPECIFIED_DAY
                ) return else null
        }

        val epoch = sessionEpoch.get()
        viewModelScope.launch {
            val spec = try {
                mt4Repository.getInstrumentSpec(current.tradeSymbol)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (sessionEpoch.get() != epoch) return@launch
            val isEstimated = spec?.isEstimated ?: true
            val point = spec?.point?.takeIf { it.isFinite() && it > 0.0 }
            val spreadPoints = if (point != null && quote.ask.isFinite() && quote.bid.isFinite() && quote.ask >= quote.bid) {
                ((quote.ask - quote.bid) / point).coerceAtLeast(0.0)
            } else null
            val maxReviewDriftPoints = if (current.orderEntryKind == BrokerOrderEntryKind.MARKET) {
                spreadPoints?.let { maxOf(MIN_REVIEW_DRIFT_POINTS, it * REVIEW_DRIFT_SPREAD_MULTIPLIER) }
            } else null
            val note = when {
                spec == null -> "Broker specification unavailable — live submission will fail closed."
                isEstimated -> "Using estimated limits [min=${spec.minVolume}, max=${spec.maxVolume}, step=${spec.volumeStep}] — live submission will fail closed."
                else -> buildString {
                    append("Broker limits: min=${spec.minVolume}, max=${spec.maxVolume}, step=${spec.volumeStep}")
                    if (current.orderEntryKind != BrokerOrderEntryKind.MARKET) {
                        append(" · stops=${spec.stopsLevelPoints}pt · freeze=${spec.freezeLevelPoints}pt")
                    }
                }
            }

            _uiState.update { state ->
                val connected = state as? Mt4UiState.Connected ?: return@update state
                val formStillMatches = connected.accountInfo.login == current.accountInfo.login &&
                    connected.accountInfo.server.equals(current.accountInfo.server, ignoreCase = true) &&
                    connected.tradeSymbol == current.tradeSymbol &&
                    connected.tradeDirection == current.tradeDirection &&
                    connected.orderEntryKind == current.orderEntryKind &&
                    connected.pendingPriceInput == current.pendingPriceInput &&
                    connected.pendingExpirationType == current.pendingExpirationType &&
                    connected.pendingExpirationInput == current.pendingExpirationInput &&
                    connected.lotsInput == current.lotsInput &&
                    connected.slInput == current.slInput &&
                    connected.tpInput == current.tpInput &&
                    !connected.isPlacing
                if (!formStillMatches) return@update connected
                connected.copy(
                    pendingOrder = Mt4UiState.PendingTrade(
                        symbol = current.tradeSymbol,
                        direction = current.tradeDirection,
                        orderType = orderType,
                        lots = lots,
                        entryPrice = entry,
                        stopLoss = sl,
                        takeProfit = tp,
                        maxSlippagePoints = maxReviewDriftPoints,
                        expirationType = if (current.orderEntryKind == BrokerOrderEntryKind.MARKET) Mt4PendingExpirationType.GTC else current.pendingExpirationType,
                        expirationTime = expirationTime,
                        isVolumeEstimated = isEstimated,
                        volumeBoundsNote = note,
                    ),
                    notice = null,
                )
            }
        }
    }

    private fun parsePendingExpiration(type: Mt4PendingExpirationType, input: String): Long? {
        if (type == Mt4PendingExpirationType.GTC || type == Mt4PendingExpirationType.DAY) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Enter expiration as yyyy-MM-dd HH:mm in your local time.") ?: it }
            return null
        }
        return try {
            val local = LocalDateTime.parse(trimmed, PENDING_EXPIRATION_FORMAT)
            val millis = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (millis <= System.currentTimeMillis() + 5_000L) {
                _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Pending-order expiration must be in the future.") ?: it }
                null
            } else millis
        } catch (_: DateTimeParseException) {
            _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Invalid expiration. Use yyyy-MM-dd HH:mm in your local time.") ?: it }
            null
        }
    }

    private fun parseOptionalPrice(value: String, label: String): Double? {
        if (value.isBlank()) return null
        val parsed = value.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed < 0.0) {
            _uiState.update { (it as? Mt4UiState.Connected)?.copy(notice = "Enter a valid $label price (0 removes it where supported).") ?: it }
            return null
        }
        return parsed
    }

    /** Step 2: confirm and place the order through the safety pipeline. */
    fun confirmOrder() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val pending = current.pendingOrder ?: return
        if (current.isPlacing) return

        val epoch = sessionEpoch.get()
        val expectedLogin = current.accountInfo.login
        val expectedServer = current.accountInfo.server
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(isPlacing = true, notice = null) ?: it }
        viewModelScope.launch {
            val result = if (pending.orderType == Mt4OrderType.BUY || pending.orderType == Mt4OrderType.SELL) {
                mt4Repository.placeTrade(
                    symbol = pending.symbol,
                    type = pending.orderType,
                    lots = pending.lots,
                    sl = pending.stopLoss?.takeIf { it > 0.0 },
                    tp = pending.takeProfit?.takeIf { it > 0.0 },
                    reviewedEntryPrice = pending.entryPrice,
                    maxSlippagePoints = pending.maxSlippagePoints,
                    confirmationTimestamp = pending.confirmationTimestamp,
                )
            } else {
                mt4Repository.placePendingOrder(
                    request = Mt4PendingOrderRequest(
                        symbol = pending.symbol,
                        type = pending.orderType,
                        lots = pending.lots,
                        openPrice = pending.entryPrice,
                        stopLoss = pending.stopLoss?.takeIf { it > 0.0 },
                        takeProfit = pending.takeProfit?.takeIf { it > 0.0 },
                        expirationType = pending.expirationType,
                        expirationTime = pending.expirationTime,
                    ),
                    confirmationTimestamp = pending.confirmationTimestamp,
                )
            }
            if (sessionEpoch.get() != epoch) return@launch
            _uiState.update { state ->
                val connected = state as? Mt4UiState.Connected ?: return@update state
                if (connected.accountInfo.login != expectedLogin ||
                    !connected.accountInfo.server.equals(expectedServer, ignoreCase = true)
                ) return@update connected
                val active = connected.pendingOrder
                if (active == null || active.confirmationTimestamp != pending.confirmationTimestamp ||
                    active.symbol != pending.symbol || active.direction != pending.direction ||
                    active.orderType != pending.orderType || active.entryPrice != pending.entryPrice ||
                    active.expirationType != pending.expirationType || active.expirationTime != pending.expirationTime
                ) return@update connected.copy(isPlacing = false)

                if (result.isSuccess) {
                    connected.copy(
                        isPlacing = false,
                        pendingOrder = null,
                        notice = if (pending.orderType == Mt4OrderType.BUY || pending.orderType == Mt4OrderType.SELL) {
                            "Market order placed. Ticket #${result.getOrThrow()}"
                        } else {
                            "Pending ${pending.orderType.name} placed. Ticket #${result.getOrThrow()}"
                        },
                    )
                } else {
                    connected.copy(
                        isPlacing = false,
                        pendingOrder = null,
                        notice = result.exceptionOrNull()?.message ?: "Order failed",
                    )
                }
            }
            if (sessionEpoch.get() == epoch) refreshPositions()
        }
    }

    fun cancelOrder() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(pendingOrder = null) ?: it }
    }

    // --- Two-step close position ---

    /** Step 1: snapshot the exact position and open a close-review dialog. */
    fun requestClosePosition(ticket: Long) {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        if (current.isPlacing || current.pendingOrder != null || current.pendingClose != null) return
        val position = current.positions.firstOrNull { it.ticket == ticket } ?: run {
            _uiState.value = current.copy(notice = "Position #$ticket is no longer open. Refresh positions.")
            return
        }
        _uiState.value = current.copy(
            pendingClose = Mt4UiState.PendingClose(
                ticket = position.ticket,
                symbol = position.symbol,
                lots = position.lots,
                profit = position.profit + position.swap + position.commission,
                confirmationTimestamp = System.currentTimeMillis(),
            ),
            notice = null,
        )
    }

    /** Step 2: submit only the exact, still-current review snapshot. */
    fun confirmClosePosition() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val pending = current.pendingClose ?: return
        if (current.isPlacing) return
        if (current.positions.none { it.ticket == pending.ticket && it.symbol.equals(pending.symbol, ignoreCase = true) }) {
            _uiState.value = current.copy(pendingClose = null, notice = "Position changed before confirmation. Refresh and review again.")
            return
        }
        _uiState.value = current.copy(isPlacing = true, notice = null)
        val epoch = sessionEpoch.get()
        val expectedLogin = current.accountInfo.login
        val expectedServer = current.accountInfo.server
        viewModelScope.launch {
            val result = mt4Repository.closeTrade(pending.ticket, pending.confirmationTimestamp)
            if (sessionEpoch.get() != epoch) return@launch
            _uiState.update { state ->
                val connected = state as? Mt4UiState.Connected ?: return@update state
                if (connected.accountInfo.login != expectedLogin ||
                    !connected.accountInfo.server.equals(expectedServer, ignoreCase = true)
                ) return@update connected
                val activeClose = connected.pendingClose
                if (activeClose == null || activeClose.ticket != pending.ticket ||
                    activeClose.confirmationTimestamp != pending.confirmationTimestamp
                ) return@update connected.copy(isPlacing = false)
                if (result.isSuccess) {
                    connected.copy(
                        isPlacing = false,
                        pendingClose = null,
                        notice = "Position #${pending.ticket} closed.",
                    )
                } else {
                    connected.copy(
                        isPlacing = false,
                        pendingClose = null,
                        notice = result.exceptionOrNull()?.message ?: "Close failed",
                    )
                }
            }
            refreshPositions()
        }
    }

    fun cancelClosePosition() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(pendingClose = null) ?: it }
    }

    // --- Professional position / pending-order manager ---

    fun requestManagePosition(ticket: Long) {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        if (current.isPlacing || current.pendingClose != null || current.pendingOrder != null) return
        val position = current.positions.firstOrNull { it.ticket == ticket } ?: run {
            _uiState.value = current.copy(notice = "Position #$ticket is no longer open. Refresh first.")
            return
        }
        _uiState.value = current.copy(
            positionManager = Mt4UiState.PositionManagerDraft(
                ticket = position.ticket,
                symbol = position.symbol,
                side = position.type,
                openPrice = position.openPrice,
                lots = position.lots,
                originalStopLoss = position.sl,
                originalTakeProfit = position.tp,
                stopLossInput = if (position.sl > 0.0) position.sl.toString() else "0",
                takeProfitInput = if (position.tp > 0.0) position.tp.toString() else "0",
                partialLotsInput = (position.lots / 2.0).toString(),
            ),
            pendingOrderManager = null,
            notice = null,
        )
    }

    fun updatePositionManagerSl(value: String) = updatePositionManager { it.copy(stopLossInput = value, reviewTimestamp = System.currentTimeMillis()) }
    fun updatePositionManagerTp(value: String) = updatePositionManager { it.copy(takeProfitInput = value, reviewTimestamp = System.currentTimeMillis()) }
    fun updatePositionManagerTrailing(value: String) = updatePositionManager { it.copy(trailingPointsInput = value, reviewTimestamp = System.currentTimeMillis()) }
    fun updatePositionManagerPartial(value: String) = updatePositionManager { it.copy(partialLotsInput = value, reviewTimestamp = System.currentTimeMillis()) }

    private fun updatePositionManager(transform: (Mt4UiState.PositionManagerDraft) -> Mt4UiState.PositionManagerDraft) {
        _uiState.update { state ->
            val connected = state as? Mt4UiState.Connected ?: return@update state
            connected.positionManager?.let { connected.copy(positionManager = transform(it), notice = null) } ?: connected
        }
    }

    fun dismissPositionManager() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(positionManager = null) ?: it }
    }

    fun applyPositionProtection() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val draft = current.positionManager ?: return
        val sl = draft.stopLossInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val tp = draft.takeProfitInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val trailing = if (draft.trailingPointsInput.isBlank()) null else draft.trailingPointsInput.toDoubleOrNull()
        if (sl == null || tp == null || (trailing != null && (!trailing.isFinite() || trailing <= 0.0))) {
            _uiState.value = current.copy(notice = "Position protection values are invalid. Use 0 to remove SL/TP; leave trailing blank to keep it unchanged.")
            return
        }
        performManagedAction(
            reviewTimestamp = draft.reviewTimestamp,
            successMessage = "Protection updated for #${draft.ticket}.",
            clearPositionManager = true,
        ) {
            mt4Repository.modifyPositionProtection(
                ticket = draft.ticket,
                protection = Mt4PositionProtection(stopLoss = sl, takeProfit = tp, trailingDistancePoints = trailing),
                confirmationTimestamp = draft.reviewTimestamp,
                expectedState = Mt4PositionSnapshot(
                    lots = draft.lots,
                    stopLoss = draft.originalStopLoss,
                    takeProfit = draft.originalTakeProfit,
                ),
            )
        }
    }

    fun moveManagedPositionToBreakEven() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val draft = current.positionManager ?: return
        performManagedAction(
            reviewTimestamp = draft.reviewTimestamp,
            successMessage = "Stop moved to break-even for #${draft.ticket}.",
            clearPositionManager = true,
        ) { mt4Repository.movePositionToBreakEven(draft.ticket, draft.reviewTimestamp) }
    }

    fun partialCloseManagedPosition() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val draft = current.positionManager ?: return
        val lots = draft.partialLotsInput.toDoubleOrNull()
        if (lots == null || !lots.isFinite() || lots <= 0.0 || lots >= draft.lots) {
            _uiState.value = current.copy(notice = "Partial-close volume must be positive and smaller than ${draft.lots} lots.")
            return
        }
        performManagedAction(
            reviewTimestamp = draft.reviewTimestamp,
            successMessage = "Partial close submitted for #${draft.ticket}: $lots lots.",
            clearPositionManager = true,
        ) {
            mt4Repository.partialCloseTrade(
                ticket = draft.ticket,
                lots = lots,
                confirmationTimestamp = draft.reviewTimestamp,
                expectedState = Mt4PositionSnapshot(
                    lots = draft.lots,
                    stopLoss = draft.originalStopLoss,
                    takeProfit = draft.originalTakeProfit,
                ),
            )
        }
    }

    fun requestManagePendingOrder(ticket: Long) {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        if (current.isPlacing || current.pendingClose != null || current.pendingOrder != null) return
        val order = current.pendingOrders.firstOrNull { it.ticket == ticket } ?: run {
            _uiState.value = current.copy(notice = "Pending order #$ticket is no longer active. Refresh first.")
            return
        }
        _uiState.value = current.copy(
            pendingOrderManager = Mt4UiState.PendingOrderManagerDraft(
                ticket = order.ticket,
                symbol = order.symbol,
                type = order.type,
                lots = order.remainingLots.takeIf { it > 0.0 } ?: order.lots,
                originalOpenPrice = order.openPrice,
                originalStopLoss = order.sl,
                originalTakeProfit = order.tp,
                openPriceInput = order.openPrice.toString(),
                stopLossInput = if (order.sl > 0.0) order.sl.toString() else "0",
                takeProfitInput = if (order.tp > 0.0) order.tp.toString() else "0",
            ),
            positionManager = null,
            notice = null,
        )
    }

    fun updatePendingManagerPrice(value: String) = updatePendingManager { it.copy(openPriceInput = value, reviewTimestamp = System.currentTimeMillis()) }
    fun updatePendingManagerSl(value: String) = updatePendingManager { it.copy(stopLossInput = value, reviewTimestamp = System.currentTimeMillis()) }
    fun updatePendingManagerTp(value: String) = updatePendingManager { it.copy(takeProfitInput = value, reviewTimestamp = System.currentTimeMillis()) }

    private fun updatePendingManager(transform: (Mt4UiState.PendingOrderManagerDraft) -> Mt4UiState.PendingOrderManagerDraft) {
        _uiState.update { state ->
            val connected = state as? Mt4UiState.Connected ?: return@update state
            connected.pendingOrderManager?.let { connected.copy(pendingOrderManager = transform(it), notice = null) } ?: connected
        }
    }

    fun dismissPendingOrderManager() {
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(pendingOrderManager = null) ?: it }
    }

    fun applyPendingOrderModification() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val draft = current.pendingOrderManager ?: return
        val price = draft.openPriceInput.toDoubleOrNull()
        val sl = draft.stopLossInput.toDoubleOrNull()
        val tp = draft.takeProfitInput.toDoubleOrNull()
        if (price == null || !price.isFinite() || price <= 0.0 ||
            sl == null || !sl.isFinite() || sl < 0.0 || tp == null || !tp.isFinite() || tp < 0.0
        ) {
            _uiState.value = current.copy(notice = "Pending-order price/SL/TP values are invalid. Use 0 to remove SL/TP.")
            return
        }
        performManagedAction(
            reviewTimestamp = draft.reviewTimestamp,
            successMessage = "Pending order #${draft.ticket} updated.",
            clearPendingManager = true,
        ) {
            mt4Repository.modifyPendingOrder(
                ticket = draft.ticket,
                openPrice = price,
                stopLoss = sl,
                takeProfit = tp,
                confirmationTimestamp = draft.reviewTimestamp,
                expectedState = Mt4PendingOrderSnapshot(
                    openPrice = draft.originalOpenPrice,
                    stopLoss = draft.originalStopLoss,
                    takeProfit = draft.originalTakeProfit,
                    lots = draft.lots,
                ),
            )
        }
    }

    fun cancelManagedPendingOrder() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val draft = current.pendingOrderManager ?: return
        performManagedAction(
            reviewTimestamp = draft.reviewTimestamp,
            successMessage = "Pending order #${draft.ticket} cancelled or already inactive.",
            clearPendingManager = true,
        ) { mt4Repository.cancelPendingOrder(draft.ticket, draft.reviewTimestamp) }
    }

    private fun performManagedAction(
        reviewTimestamp: Long,
        successMessage: String,
        clearPositionManager: Boolean = false,
        clearPendingManager: Boolean = false,
        action: suspend () -> Result<Unit>,
    ) {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        if (current.isPlacing) return
        val now = System.currentTimeMillis()
        if (reviewTimestamp <= 0L || now - reviewTimestamp !in 0..60_000L) {
            _uiState.value = current.copy(notice = "This management review is stale. Close it and review again.")
            return
        }
        val epoch = sessionEpoch.get()
        val expectedLogin = current.accountInfo.login
        val expectedServer = current.accountInfo.server
        _uiState.value = current.copy(isPlacing = true, notice = null)
        viewModelScope.launch {
            val result = action()
            if (sessionEpoch.get() != epoch) return@launch
            _uiState.update { state ->
                val connected = state as? Mt4UiState.Connected ?: return@update state
                if (connected.accountInfo.login != expectedLogin ||
                    !connected.accountInfo.server.equals(expectedServer, ignoreCase = true)
                ) return@update connected
                connected.copy(
                    isPlacing = false,
                    positionManager = if (clearPositionManager) null else connected.positionManager,
                    pendingOrderManager = if (clearPendingManager) null else connected.pendingOrderManager,
                    notice = if (result.isSuccess) successMessage else result.exceptionOrNull()?.message ?: "Broker action failed.",
                )
            }
            if (sessionEpoch.get() == epoch) refreshPositions()
        }
    }

    // --- Connected state actions ---

    fun refreshPositions() {
        val current = _uiState.value as? Mt4UiState.Connected ?: return
        val epoch = sessionEpoch.get()
        val expectedLogin = current.accountInfo.login
        val expectedServer = current.accountInfo.server
        _uiState.update { (it as? Mt4UiState.Connected)?.copy(isRefreshing = true) ?: it }
        viewModelScope.launch {
            val account = mt4Repository.getAccountInfo().getOrNull()
            val positionsResult = mt4Repository.getPositions()
            val pendingResult = mt4Repository.getPendingOrders()
            val journalSync = mt4Repository.synchronizeBrokerJournal()
            if (sessionEpoch.get() != epoch) return@launch
            _uiState.update { state ->
                val connected = state as? Mt4UiState.Connected ?: return@update state
                if (connected.accountInfo.login != expectedLogin ||
                    !connected.accountInfo.server.equals(expectedServer, ignoreCase = true)
                ) return@update connected
                positionsResult.fold(
                    onSuccess = { positions -> connected.copy(
                        accountInfo = account ?: connected.accountInfo,
                        positions = positions,
                        pendingOrders = pendingResult.getOrElse { connected.pendingOrders },
                        isRefreshing = false,
                        notice = journalSync.getOrNull()?.takeIf { it > 0 }?.let { count ->
                            "$count broker journal close record(s) await authoritative history synchronization."
                        } ?: connected.notice,
                    ) },
                    onFailure = { connected.copy(
                        accountInfo = account ?: connected.accountInfo,
                        pendingOrders = pendingResult.getOrElse { connected.pendingOrders },
                        isRefreshing = false,
                    ) },
                )
            }
        }
    }

    fun disconnect() {
        sessionEpoch.incrementAndGet()
        connectionJob?.cancel()
        quoteJob?.cancel()
        viewModelScope.launch {
            mt4Repository.disconnect()
            _uiState.value = Mt4UiState.Disconnected(
                login = mt4Repository.getLastConnection()?.login?.toString().orEmpty(),
                password = "",
                server = mt4Repository.getLastConnection()?.server.orEmpty(),
                platform = mt4Repository.getLastConnection()?.platform ?: "mt4",
                savedAccounts = mt4Repository.getSavedAccounts(),
            )
        }
    }

    private companion object {
        val PENDING_EXPIRATION_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        // A review should survive ordinary spread noise, but not an old price.
        // The cap scales with the live spread while retaining a conservative
        // minimum for tight-spread symbols. The repository re-checks this
        // against the latest executable ask/bid immediately before submit.
        const val MIN_REVIEW_DRIFT_POINTS = 20.0
        const val REVIEW_DRIFT_SPREAD_MULTIPLIER = 5.0
    }

}
