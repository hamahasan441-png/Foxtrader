package com.foxtrader.app.feature.deriv.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.deriv.DerivAccount
import com.foxtrader.app.domain.model.deriv.DerivAccountType
import com.foxtrader.app.domain.model.deriv.DerivExecutionAuthorization
import com.foxtrader.app.domain.model.deriv.DerivProposalRequest
import com.foxtrader.app.domain.model.deriv.DerivPosition
import com.foxtrader.app.domain.repository.DerivRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class DerivViewModel @Inject constructor(
    private val repository: DerivRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        DerivUiState(
            appId = repository.getSavedAppId().orEmpty(),
            token = repository.getSavedToken().orEmpty(),
        )
    )
    val uiState: StateFlow<DerivUiState> = _uiState.asStateFlow()
    private var tickJob: Job? = null
    private val sessionEpoch = AtomicLong(0L)
    private val proposalEpoch = AtomicLong(0L)

    private data class PendingBuyReview(val accountId: String, val proposalId: String, val maxPrice: Double, val reviewedAtMs: Long)
    private data class PendingSellReview(val accountId: String, val contractId: Long, val minimumPrice: Double, val reviewedAtMs: Long)
    private data class PendingUpdateReview(val accountId: String, val contractId: Long, val stopLoss: Double?, val takeProfit: Double?, val reviewedAtMs: Long)
    private data class PendingCancelReview(val accountId: String, val contractId: Long, val reviewedAtMs: Long)

    private var pendingBuyReview: PendingBuyReview? = null
    private var pendingSellReview: PendingSellReview? = null
    private var pendingUpdateReview: PendingUpdateReview? = null
    private var pendingCancelReview: PendingCancelReview? = null

    init {
        viewModelScope.launch {
            repository.connectionState.collectLatest { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
        if (_uiState.value.credentialsReady) loadAccounts()
    }

    fun onAppIdChange(value: String) = editCredentials(appId = value.trim())
    fun onTokenChange(value: String) = editCredentials(token = value.trim())
    fun toggleTokenVisibility() = _uiState.update { it.copy(tokenVisible = !it.tokenVisible) }

    private fun invalidateSessionBoundary() {
        sessionEpoch.incrementAndGet()
        proposalEpoch.incrementAndGet()
        pendingBuyReview = null
        pendingSellReview = null
        pendingUpdateReview = null
        pendingCancelReview = null
        tickJob?.cancel()
        tickJob = null
    }

    private fun isCurrentAccountSession(epoch: Long, accountId: String): Boolean {
        val state = _uiState.value
        return sessionEpoch.get() == epoch && state.authenticated && state.selectedAccount?.accountId == accountId
    }

    private fun clearBuyReview() {
        pendingBuyReview = null
        proposalEpoch.incrementAndGet()
    }

    /**
     * API credential edits cross an account-identity boundary. We immediately
     * invalidate the old authenticated session and every pending execution
     * confirmation so a draft App ID/token can never be mixed with an old
     * account WebSocket.
     */
    private fun editCredentials(appId: String? = null, token: String? = null) {
        val current = _uiState.value
        val nextAppId = appId ?: current.appId
        val nextToken = token ?: current.token
        if (nextAppId == current.appId && nextToken == current.token) return
        invalidateSessionBoundary()
        repository.disconnect()
        _uiState.update {
            it.copy(
                appId = nextAppId,
                token = nextToken,
                credentialsDirty = true,
                accounts = emptyList(),
                loading = false,
                connectionState = com.foxtrader.app.domain.model.deriv.DerivConnectionState.DISCONNECTED,
                selectedAccount = null,
                balance = null,
                positions = emptyList(),
                proposal = null,
                tick = null,
                manageContractId = null,
                pendingBuyConfirmation = false,
                pendingSellContractId = null,
                pendingUpdateConfirmation = false,
                pendingCancelContractId = null,
                profitRecords = emptyList(),
                statementRecords = emptyList(),
                wallets = emptyList(),
                selectedWalletType = null,
                walletTransactions = emptyList(),
                walletNextPageUrl = null,
                error = null,
                notice = "API settings changed. Apply credentials before selecting an account.",
            )
        }
    }

    fun applyCredentials() {
        val state = _uiState.value
        if (!state.credentialsReady || state.loading) return
        invalidateSessionBoundary()
        repository.disconnect()
        val operationEpoch = sessionEpoch.get()
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                selectedAccount = null,
                balance = null,
                positions = emptyList(),
                proposal = null,
                pendingBuyConfirmation = false,
                pendingSellContractId = null,
                pendingUpdateConfirmation = false,
                pendingCancelContractId = null,
            )
        }
        viewModelScope.launch {
            repository.getAccounts(state.appId, state.token)
                .onSuccess { accounts ->
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.appId != state.appId || current.token != state.token) {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                credentialsDirty = true,
                                notice = "API fields changed while verification was running. Apply the latest values again.",
                            )
                        }
                        return@onSuccess
                    }
                    repository.saveCredentials(state.appId, state.token)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            credentialsDirty = false,
                            accounts = accounts,
                            selectedAccount = null,
                            connectionState = com.foxtrader.app.domain.model.deriv.DerivConnectionState.DISCONNECTED,
                            notice = "Deriv API saved and verified. Select an account to connect.",
                        )
                    }
                }
                .onFailure { error ->
                    if (sessionEpoch.get() == operationEpoch) showError(error)
                }
        }
    }

    fun revertCredentials() {
        invalidateSessionBoundary()
        repository.disconnect()
        val savedAppId = repository.getSavedAppId().orEmpty()
        val savedToken = repository.getSavedToken().orEmpty()
        _uiState.update {
            DerivUiState(
                appId = savedAppId,
                token = savedToken,
                credentialsDirty = false,
                notice = if (savedAppId.isNotBlank() && savedToken.isNotBlank()) {
                    "Saved Deriv API restored. Load accounts to continue."
                } else {
                    "No saved Deriv API credentials"
                },
            )
        }
        if (savedAppId.isNotBlank() && savedToken.isNotBlank()) loadAccounts()
    }
    fun onSymbolChange(value: String) { clearBuyReview(); _uiState.update { it.copy(selectedSymbol = value.trim(), proposal = null, pendingBuyConfirmation = false) } }
    fun onAmountChange(value: String) { clearBuyReview(); _uiState.update { it.copy(amount = value, proposal = null, pendingBuyConfirmation = false) } }
    fun onContractTypeChange(value: String) { clearBuyReview(); _uiState.update { it.copy(contractType = value.trim().uppercase(), proposal = null, pendingBuyConfirmation = false) } }
    fun onDurationChange(value: String) { clearBuyReview(); _uiState.update { it.copy(duration = value, proposal = null, pendingBuyConfirmation = false) } }
    fun onSellMinimumPriceChange(value: String) { pendingSellReview = null; _uiState.update { it.copy(sellMinimumPrice = value, pendingSellContractId = null, error = null) } }
    fun onStopLossAmountChange(value: String) { pendingUpdateReview = null; _uiState.update { it.copy(stopLossAmount = value, pendingUpdateConfirmation = false, error = null) } }
    fun onTakeProfitAmountChange(value: String) { pendingUpdateReview = null; _uiState.update { it.copy(takeProfitAmount = value, pendingUpdateConfirmation = false, error = null) } }

    fun manageContract(position: DerivPosition) {
        pendingSellReview = null
        pendingUpdateReview = null
        pendingCancelReview = null
        _uiState.update {
            it.copy(
                manageContractId = position.contractId,
                sellMinimumPrice = "0",
                pendingSellContractId = null,
                stopLossAmount = "",
                takeProfitAmount = "",
                pendingUpdateConfirmation = false,
                pendingCancelContractId = null,
                error = null,
            )
        }
    }

    fun checkHealth() {
        viewModelScope.launch {
            repository.health()
                .onSuccess { healthy ->
                    _uiState.update { it.copy(apiHealthy = healthy, notice = if (healthy) "Deriv API health: OK" else "Deriv API health check failed") }
                }
                .onFailure(::showError)
        }
    }

    fun createDemoAccount() {
        val state = _uiState.value
        if (!state.credentialsReady || state.credentialsDirty || state.loading) return
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch {
            repository.createDemoAccount(state.appId, state.token)
                .onSuccess { account ->
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.appId != state.appId ||
                        current.token != state.token || current.credentialsDirty) return@onSuccess
                    _uiState.update { it.copy(loading = false, notice = "Demo account ${account.accountId} ready") }
                    loadAccounts()
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun resetDemoBalance(account: DerivAccount) {
        val state = _uiState.value
        if (!state.credentialsReady || state.credentialsDirty || state.loading || account.accountType != DerivAccountType.DEMO) return
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch {
            repository.resetDemoBalance(state.appId, state.token, account)
                .onSuccess {
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.appId != state.appId ||
                        current.token != state.token || current.credentialsDirty) return@onSuccess
                    _uiState.update { it.copy(loading = false, notice = "Demo balance reset for ${account.accountId}") }
                    loadAccounts()
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun loadAccounts() {
        val state = _uiState.value
        if (!state.credentialsReady || state.loading) return
        if (state.credentialsDirty) {
            applyCredentials()
            return
        }
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch {
            repository.getAccounts(state.appId, state.token)
                .onSuccess { accounts ->
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.appId != state.appId || current.token != state.token || current.credentialsDirty) {
                        if (sessionEpoch.get() == operationEpoch) _uiState.update { it.copy(loading = false) }
                        return@onSuccess
                    }
                    val saved = repository.getSavedAccountId()
                    _uiState.update {
                        it.copy(
                            loading = false,
                            accounts = accounts,
                            selectedAccount = null,
                            notice = if (saved != null && accounts.any { a -> a.accountId == saved }) {
                                "${accounts.size} account(s) loaded. Previously used account $saved is available."
                            } else {
                                "${accounts.size} Deriv Options account(s) loaded"
                            },
                        )
                    }
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun connectPublic() {
        invalidateSessionBoundary()
        repository.disconnect()
        val operationEpoch = sessionEpoch.get()
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                selectedAccount = null,
                balance = null,
                positions = emptyList(),
                proposal = null,
                pendingBuyConfirmation = false,
            )
        }
        viewModelScope.launch {
            repository.connectPublic()
                .onSuccess {
                    if (sessionEpoch.get() != operationEpoch) return@onSuccess
                    _uiState.update { it.copy(loading = false, connectionState = com.foxtrader.app.domain.model.deriv.DerivConnectionState.CONNECTED, notice = "Connected to Deriv public market data") }
                    loadSymbols()
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun connectAccount(account: DerivAccount) {
        val state = _uiState.value
        if (!state.credentialsReady || state.credentialsDirty || state.loading) return
        invalidateSessionBoundary()
        repository.disconnect()
        val operationEpoch = sessionEpoch.get()
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                connectionState = com.foxtrader.app.domain.model.deriv.DerivConnectionState.CONNECTING,
                selectedAccount = account,
                balance = null,
                positions = emptyList(),
                proposal = null,
                pendingBuyConfirmation = false,
                manageContractId = null,
                pendingSellContractId = null,
                pendingUpdateConfirmation = false,
                pendingCancelContractId = null,
                profitRecords = emptyList(),
                statementRecords = emptyList(),
            )
        }
        viewModelScope.launch {
            repository.connectAccount(state.appId, state.token, account)
                .onSuccess {
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.selectedAccount?.accountId != account.accountId ||
                        current.appId != state.appId || current.token != state.token || current.credentialsDirty) {
                        return@onSuccess
                    }
                    _uiState.update {
                        it.copy(
                            loading = false,
                            connectionState = com.foxtrader.app.domain.model.deriv.DerivConnectionState.CONNECTED,
                            notice = "Connected ${account.accountId} · ${account.accountType}",
                        )
                    }
                    refreshAccount()
                    loadAccountHistory()
                    loadSymbols()
                    loadContractCategories()
                    startTicks()
                }
                .onFailure { error ->
                    if (sessionEpoch.get() == operationEpoch) {
                        _uiState.update { it.copy(selectedAccount = null) }
                        showError(error)
                    }
                }
        }
    }

    fun disconnect() {
        invalidateSessionBoundary()
        repository.disconnect()
        _uiState.update {
            it.copy(
                loading = false,
                connectionState = com.foxtrader.app.domain.model.deriv.DerivConnectionState.DISCONNECTED,
                selectedAccount = null,
                balance = null,
                positions = emptyList(),
                proposal = null,
                pendingBuyConfirmation = false,
                tick = null,
                manageContractId = null,
                pendingSellContractId = null,
                pendingUpdateConfirmation = false,
                pendingCancelContractId = null,
                profitRecords = emptyList(),
                statementRecords = emptyList(),
                notice = "Disconnected",
                error = null,
            )
        }
    }

    fun clearCredentials() {
        invalidateSessionBoundary()
        repository.clearCredentials()
        _uiState.value = DerivUiState(notice = "Deriv credentials cleared")
    }

    fun loadSymbols() {
        val operationEpoch = sessionEpoch.get()
        viewModelScope.launch {
            repository.activeSymbols()
                .onSuccess { symbols ->
                    if (sessionEpoch.get() != operationEpoch) return@onSuccess
                    _uiState.update { it.copy(symbols = symbols, notice = "${symbols.size} active symbols loaded") }
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun loadContractCategories() {
        val operationEpoch = sessionEpoch.get()
        viewModelScope.launch {
            repository.contractsList()
                .onSuccess { categories ->
                    if (sessionEpoch.get() != operationEpoch) return@onSuccess
                    _uiState.update { it.copy(contractCategories = categories, notice = "${categories.size} contract categories loaded") }
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun loadAccountHistory() {
        val state = _uiState.value
        val account = state.selectedAccount ?: return
        if (!state.authenticated || state.loading) return
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val profit = repository.profitTable(limit = 50)
            val statement = repository.statement(limit = 100)
            if (!isCurrentAccountSession(operationEpoch, account.accountId)) return@launch
            _uiState.update { current ->
                current.copy(
                    loading = false,
                    profitRecords = profit.getOrNull() ?: emptyList(),
                    statementRecords = statement.getOrNull() ?: emptyList(),
                    notice = if (profit.isSuccess && statement.isSuccess) "Account history refreshed" else current.notice,
                    error = profit.exceptionOrNull()?.message ?: statement.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun loadWallets() {
        val state = _uiState.value
        if (!state.credentialsReady || state.credentialsDirty || state.loading) return
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.wallets(state.appId, state.token, conversionCurrency = "USD")
                .onSuccess { wallets ->
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.credentialsDirty || current.appId != state.appId || current.token != state.token) return@onSuccess
                    _uiState.update {
                        it.copy(
                            loading = false,
                            wallets = wallets,
                            selectedWalletType = wallets.firstOrNull()?.type,
                            walletTransactions = emptyList(),
                            walletNextPageUrl = null,
                            notice = "${wallets.size} wallet(s) loaded (payment scope)",
                        )
                    }
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun loadWalletTransactions(walletType: String) {
        val state = _uiState.value
        if (!state.credentialsReady || state.credentialsDirty || state.loading || walletType.isBlank()) return
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, selectedWalletType = walletType, error = null) }
        viewModelScope.launch {
            repository.walletTransactions(state.appId, state.token, walletType, perPage = 100)
                .onSuccess { page ->
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.credentialsDirty || current.appId != state.appId || current.token != state.token || current.selectedWalletType != walletType) return@onSuccess
                    _uiState.update {
                        it.copy(
                            loading = false,
                            walletTransactions = page.transactions,
                            walletNextPageUrl = page.nextPageUrl,
                            notice = "${page.transactions.size} wallet transactions loaded",
                        )
                    }
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun loadNextWalletTransactions() {
        val state = _uiState.value
        val nextPage = state.walletNextPageUrl ?: return
        val walletType = state.selectedWalletType ?: return
        if (!state.credentialsReady || state.credentialsDirty || state.loading) return
        val operationEpoch = sessionEpoch.get()
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.walletTransactionsPage(state.appId, state.token, nextPage)
                .onSuccess { page ->
                    val current = _uiState.value
                    if (sessionEpoch.get() != operationEpoch || current.credentialsDirty || current.appId != state.appId || current.token != state.token || current.selectedWalletType != walletType || current.walletNextPageUrl != nextPage) return@onSuccess
                    _uiState.update { currentState ->
                        val existingIds = currentState.walletTransactions.asSequence().map { it.transactionId }.toHashSet()
                        val appended = page.transactions.filterNot { it.transactionId in existingIds }
                        currentState.copy(
                            loading = false,
                            walletTransactions = currentState.walletTransactions + appended,
                            walletNextPageUrl = page.nextPageUrl,
                            notice = "${appended.size} more wallet transactions loaded",
                        )
                    }
                }
                .onFailure { error -> if (sessionEpoch.get() == operationEpoch) showError(error) }
        }
    }

    fun startTicks() {
        val state = _uiState.value
        val symbol = state.selectedSymbol
        if (symbol.isBlank() || state.connectionState != com.foxtrader.app.domain.model.deriv.DerivConnectionState.CONNECTED) return
        val operationEpoch = sessionEpoch.get()
        val accountId = state.selectedAccount?.accountId
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            runCatching {
                repository.streamTicks(symbol).collectLatest { tick ->
                    val current = _uiState.value
                    val sameConnection = sessionEpoch.get() == operationEpoch &&
                        current.selectedSymbol == symbol && current.selectedAccount?.accountId == accountId &&
                        current.connectionState == com.foxtrader.app.domain.model.deriv.DerivConnectionState.CONNECTED
                    if (sameConnection) _uiState.update { it.copy(tick = tick, error = null) }
                }
            }.onFailure { error ->
                if (sessionEpoch.get() == operationEpoch) showError(error)
            }
        }
    }

    fun refreshAccount() {
        val state = _uiState.value
        val account = state.selectedAccount ?: return
        if (!state.authenticated) return
        val operationEpoch = sessionEpoch.get()
        viewModelScope.launch {
            val balance = repository.balance()
            val portfolio = repository.portfolio()
            if (!isCurrentAccountSession(operationEpoch, account.accountId)) return@launch
            _uiState.update { current ->
                current.copy(
                    balance = balance.getOrNull() ?: current.balance,
                    positions = portfolio.getOrNull() ?: current.positions,
                    error = balance.exceptionOrNull()?.message ?: portfolio.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun requestProposal() {
        val s = _uiState.value
        val amount = s.amount.toDoubleOrNull()
        val duration = s.duration.toIntOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0.0 || duration == null || duration <= 0 || s.selectedSymbol.isBlank() || s.contractType.isBlank()) {
            _uiState.update { it.copy(error = "Check symbol, amount, contract type and duration") }
            return
        }
        val request = DerivProposalRequest(
            underlyingSymbol = s.selectedSymbol,
            amount = amount,
            contractType = s.contractType,
            duration = duration,
            durationUnit = s.durationUnit,
        )
        pendingBuyReview = null
        val requestEpoch = proposalEpoch.incrementAndGet()
        val connectionEpoch = sessionEpoch.get()
        viewModelScope.launch {
            repository.proposal(request)
                .onSuccess { proposal ->
                    val current = _uiState.value
                    val inputsStillMatch = current.selectedSymbol == request.underlyingSymbol &&
                        current.amount.toDoubleOrNull() == request.amount &&
                        current.contractType == request.contractType &&
                        current.duration.toIntOrNull() == request.duration &&
                        current.durationUnit == request.durationUnit
                    if (proposalEpoch.get() != requestEpoch || sessionEpoch.get() != connectionEpoch || !inputsStillMatch) return@onSuccess
                    _uiState.update { it.copy(proposal = proposal, pendingBuyConfirmation = false, notice = "Proposal ready", error = null) }
                }
                .onFailure { error ->
                    if (proposalEpoch.get() == requestEpoch && sessionEpoch.get() == connectionEpoch) showError(error)
                }
        }
    }

    fun reviewBuy() {
        val s = _uiState.value
        val proposal = s.proposal ?: return
        val account = s.selectedAccount ?: return
        val maxPrice = proposal.askPrice
        if (maxPrice == null || !maxPrice.isFinite() || maxPrice < 0.0) {
            _uiState.update { it.copy(error = "Proposal price is unavailable; request a fresh proposal") }
            return
        }
        pendingBuyReview = PendingBuyReview(account.accountId, proposal.id, maxPrice, System.currentTimeMillis())
        _uiState.update { it.copy(pendingBuyConfirmation = true, notice = if (account.accountType == DerivAccountType.REAL) "REAL MONEY: confirm this exact proposal to continue" else "Confirm demo purchase") }
    }

    fun confirmBuy() {
        val s = _uiState.value
        val proposal = s.proposal ?: return
        val account = s.selectedAccount ?: return
        val review = pendingBuyReview ?: return
        if (!s.pendingBuyConfirmation || review.accountId != account.accountId || review.proposalId != proposal.id || proposal.askPrice != review.maxPrice) {
            pendingBuyReview = null
            _uiState.update { it.copy(pendingBuyConfirmation = false, error = "Trade review is no longer current; review the proposal again") }
            return
        }
        val operationEpoch = sessionEpoch.get()
        pendingBuyReview = null
        _uiState.update { it.copy(loading = true, pendingBuyConfirmation = false, error = null) }
        viewModelScope.launch {
            repository.buy(
                proposalId = review.proposalId,
                maxPrice = review.maxPrice,
                authorization = DerivExecutionAuthorization(
                    accountId = account.accountId,
                    accountType = account.accountType,
                    userConfirmed = true,
                    confirmationEpochMs = review.reviewedAtMs,
                ),
            ).onSuccess { buy ->
                if (!isCurrentAccountSession(operationEpoch, account.accountId)) return@onSuccess
                _uiState.update { it.copy(loading = false, proposal = null, notice = "Contract ${buy.contractId} purchased") }
                refreshAccount()
            }.onFailure { error -> if (isCurrentAccountSession(operationEpoch, account.accountId)) showError(error) }
        }
    }

    fun reviewSell(position: DerivPosition) {
        if (_uiState.value.manageContractId != position.contractId) manageContract(position)
        val s = _uiState.value
        val account = s.selectedAccount ?: return
        val minPrice = s.sellMinimumPrice.toDoubleOrNull()
        if (minPrice == null || minPrice < 0.0 || !minPrice.isFinite()) {
            _uiState.update { it.copy(error = "Sell minimum price must be 0 or greater") }
            return
        }
        pendingSellReview = PendingSellReview(account.accountId, position.contractId, minPrice, System.currentTimeMillis())
        pendingUpdateReview = null
        pendingCancelReview = null
        _uiState.update { it.copy(pendingSellContractId = position.contractId, pendingUpdateConfirmation = false, pendingCancelContractId = null, notice = "Review early sell for contract ${position.contractId}") }
    }

    fun confirmSell(position: DerivPosition) {
        val s = _uiState.value
        val account = s.selectedAccount ?: return
        val review = pendingSellReview ?: return
        val currentMinPrice = s.sellMinimumPrice.toDoubleOrNull()
        if (s.pendingSellContractId != position.contractId || review.accountId != account.accountId || review.contractId != position.contractId || currentMinPrice != review.minimumPrice) {
            pendingSellReview = null
            _uiState.update { it.copy(pendingSellContractId = null, error = "Sell review is no longer current; review again") }
            return
        }
        val operationEpoch = sessionEpoch.get()
        pendingSellReview = null
        _uiState.update { it.copy(loading = true, pendingSellContractId = null, error = null) }
        viewModelScope.launch {
            repository.sell(
                contractId = review.contractId,
                minimumPrice = review.minimumPrice,
                authorization = DerivExecutionAuthorization(account.accountId, account.accountType, true, review.reviewedAtMs),
            ).onSuccess { sold ->
                if (!isCurrentAccountSession(operationEpoch, account.accountId)) return@onSuccess
                _uiState.update { it.copy(loading = false, manageContractId = null, notice = "Contract ${sold.contractId} sold for ${sold.soldFor ?: "—"}") }
                refreshAccount()
            }.onFailure { error -> if (isCurrentAccountSession(operationEpoch, account.accountId)) showError(error) }
        }
    }

    fun reviewUpdate(position: DerivPosition) {
        if (_uiState.value.manageContractId != position.contractId) manageContract(position)
        val s = _uiState.value
        val account = s.selectedAccount ?: return
        val sl = s.stopLossAmount.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val tp = s.takeProfitAmount.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if ((s.stopLossAmount.isNotBlank() && sl == null) || (s.takeProfitAmount.isNotBlank() && tp == null) ||
            (sl != null && (sl < 0.0 || !sl.isFinite())) || (tp != null && (tp < 0.0 || !tp.isFinite())) || (sl == null && tp == null)) {
            _uiState.update { it.copy(error = "Enter a valid stop-loss and/or take-profit amount") }
            return
        }
        pendingUpdateReview = PendingUpdateReview(account.accountId, position.contractId, sl, tp, System.currentTimeMillis())
        pendingSellReview = null
        pendingCancelReview = null
        _uiState.update { it.copy(pendingUpdateConfirmation = true, pendingSellContractId = null, pendingCancelContractId = null, notice = "Review SL/TP update for contract ${position.contractId}") }
    }

    fun confirmUpdate(position: DerivPosition) {
        val s = _uiState.value
        val account = s.selectedAccount ?: return
        val review = pendingUpdateReview ?: return
        val sl = s.stopLossAmount.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val tp = s.takeProfitAmount.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if (!s.pendingUpdateConfirmation || s.manageContractId != position.contractId || review.accountId != account.accountId ||
            review.contractId != position.contractId || sl != review.stopLoss || tp != review.takeProfit) {
            pendingUpdateReview = null
            _uiState.update { it.copy(pendingUpdateConfirmation = false, error = "SL/TP review is no longer current; review again") }
            return
        }
        val operationEpoch = sessionEpoch.get()
        pendingUpdateReview = null
        _uiState.update { it.copy(loading = true, pendingUpdateConfirmation = false, error = null) }
        viewModelScope.launch {
            repository.updateContract(
                contractId = review.contractId,
                stopLoss = review.stopLoss,
                takeProfit = review.takeProfit,
                authorization = DerivExecutionAuthorization(account.accountId, account.accountType, true, review.reviewedAtMs),
            ).onSuccess {
                if (!isCurrentAccountSession(operationEpoch, account.accountId)) return@onSuccess
                _uiState.update { it.copy(loading = false, notice = "Contract ${position.contractId} SL/TP updated") }
                refreshAccount()
            }.onFailure { error -> if (isCurrentAccountSession(operationEpoch, account.accountId)) showError(error) }
        }
    }

    fun reviewCancel(position: DerivPosition) {
        if (_uiState.value.manageContractId != position.contractId) manageContract(position)
        val account = _uiState.value.selectedAccount ?: return
        pendingCancelReview = PendingCancelReview(account.accountId, position.contractId, System.currentTimeMillis())
        pendingSellReview = null
        pendingUpdateReview = null
        _uiState.update { it.copy(pendingCancelContractId = position.contractId, pendingSellContractId = null, pendingUpdateConfirmation = false, notice = "Review cancellation for contract ${position.contractId}; Deriv will reject contracts that are not cancelable") }
    }

    fun confirmCancel(position: DerivPosition) {
        val s = _uiState.value
        val account = s.selectedAccount ?: return
        val review = pendingCancelReview ?: return
        if (s.pendingCancelContractId != position.contractId || review.accountId != account.accountId || review.contractId != position.contractId) {
            pendingCancelReview = null
            _uiState.update { it.copy(pendingCancelContractId = null, error = "Cancellation review is no longer current; review again") }
            return
        }
        val operationEpoch = sessionEpoch.get()
        pendingCancelReview = null
        _uiState.update { it.copy(loading = true, pendingCancelContractId = null, error = null) }
        viewModelScope.launch {
            repository.cancelContract(
                contractId = review.contractId,
                authorization = DerivExecutionAuthorization(account.accountId, account.accountType, true, review.reviewedAtMs),
            ).onSuccess {
                if (!isCurrentAccountSession(operationEpoch, account.accountId)) return@onSuccess
                _uiState.update { it.copy(loading = false, manageContractId = null, notice = "Contract ${position.contractId} cancellation accepted") }
                refreshAccount()
            }.onFailure { error -> if (isCurrentAccountSession(operationEpoch, account.accountId)) showError(error) }
        }
    }

    private fun showError(t: Throwable) {
        _uiState.update { it.copy(loading = false, error = t.message ?: "Deriv request failed") }
    }

    override fun onCleared() {
        invalidateSessionBoundary()
        repository.disconnect()
        super.onCleared()
    }
}
