package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.AlertRule
import com.foxtrader.app.domain.model.tradepro.AlertTriggerType
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.tradepro.AlertRuleEngine
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import com.foxtrader.app.domain.usecase.watchlist.ActiveWatchlistSymbols
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** Manages TRADEPRO alert rules and previews them against the persisted watchlist. */
@HiltViewModel
class AlertRulesViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val repository: MarketRepository,
    private val activeWatchlistSymbols: ActiveWatchlistSymbols,
    private val signalEngine: TradeProSignalEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val alertRuleEngine: AlertRuleEngine,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertRulesUiState())
    val uiState: StateFlow<AlertRulesUiState> = _uiState.asStateFlow()

    init {
        appPreferences.tradeProAlertRules
            .onEach { rules -> _uiState.update { it.copy(rules = rules.toImmutableList()) } }
            .launchIn(viewModelScope)
    }

    fun startNewRule() {
        _uiState.update {
            it.copy(
                draft = AlertRule(
                    id = UUID.randomUUID().toString(),
                    name = "New alert",
                    trigger = AlertTriggerType.EXECUTABLE_SETUP,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun editRule(id: String) {
        val rule = _uiState.value.rules.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(draft = rule) }
    }

    fun updateDraft(rule: AlertRule) { _uiState.update { it.copy(draft = rule) } }
    fun cancelDraft() { _uiState.update { it.copy(draft = null) } }

    fun saveDraft() {
        val draft = _uiState.value.draft ?: return
        val existing = _uiState.value.rules
        val updated = if (existing.any { it.id == draft.id }) {
            existing.map { if (it.id == draft.id) draft else it }
        } else existing + draft
        appPreferences.setTradeProAlertRules(updated)
        _uiState.update { it.copy(draft = null) }
    }

    fun deleteRule(id: String) {
        appPreferences.setTradeProAlertRules(_uiState.value.rules.filterNot { it.id == id })
    }

    fun toggleRule(id: String) {
        appPreferences.setTradeProAlertRules(
            _uiState.value.rules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it },
        )
    }

    fun testScan() {
        viewModelScope.launch {
            val rules = _uiState.value.rules.filter { it.enabled }
            if (rules.isEmpty()) {
                _uiState.update { it.copy(previewAlerts = emptyList<com.foxtrader.app.domain.model.tradepro.TriggeredAlert>().toImmutableList()) }
                return@launch
            }
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                val config = appPreferences.tradeProConfig.value
                val watchlist = activeWatchlistSymbols(MAX_SYMBOLS)
                val analyses = HashMap<String, TradeProAnalysis>()
                val prices = HashMap<String, Double>()
                for (entry in watchlist) {
                    val timeframe = ruleTimeframe(rules)
                    val now = System.currentTimeMillis()
                    val candles = ConfirmedBarPolicy.confirmedPrefix(
                        repository.getSourcedCandles(entry.symbol, timeframe).candles,
                        timeframe,
                        now,
                    )
                    if (candles.size < MIN_BARS) continue
                    val htf = ConfirmedBarPolicy.confirmedMap(
                        mtfContextProvider.getHtfContext(entry.symbol, timeframe),
                        now,
                    )
                    val analysis = withContext(defaultDispatcher) {
                        signalEngine.analyze(entry.symbol, candles, config, htf)
                    }
                    analyses[entry.symbol] = analysis
                    prices[entry.symbol] = candles.last().close
                }
                val result = alertRuleEngine.evaluateBatch(
                    rules = rules,
                    analysesBySymbol = analyses,
                    currentPriceBySymbol = prices,
                    nowEpochMs = System.currentTimeMillis(),
                )
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        previewAlerts = result.alerts.toImmutableList(),
                        lastScanEpochMs = System.currentTimeMillis(),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, error = e.message ?: "Preview failed.") }
            }
        }
    }

    private fun ruleTimeframe(rules: List<AlertRule>): Timeframe {
        val label = rules.firstOrNull { it.timeframeLabel.isNotBlank() }?.timeframeLabel
        return if (label != null) Timeframe.fromLabel(label) else Timeframe.H1
    }

    companion object {
        private const val MAX_SYMBOLS = 20
        private const val MIN_BARS = 30
    }
}
