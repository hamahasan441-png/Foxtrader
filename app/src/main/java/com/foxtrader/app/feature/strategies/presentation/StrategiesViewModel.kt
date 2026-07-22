package com.foxtrader.app.feature.strategies.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.analysis.RiskRewardOptimizer
import com.foxtrader.app.domain.usecase.patterns.HarmonicPatternDetector
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/**
 * Strategies screen ViewModel — scans watchlist symbols and surfaces
 * actionable setups detected by the analysis engines (harmonics, SMC,
 * risk/reward). Kept separate from the chart so users have a dedicated
 * "strategies" section.
 */
@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val scannerUseCase: ScannerUseCase,
    private val harmonicDetector: HarmonicPatternDetector,
    private val smcDetector: SmcDetector,
    private val riskReward: RiskRewardOptimizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                val signals = mutableListOf<StrategySignalItem>()
                val watchlist = scannerUseCase.getWatchlist().filter { it.enabled }.take(15)

                for (ws in watchlist) {
                    val candles = repository.getCandles(ws.symbol)
                    if (candles.size < 50) continue
                    signals += detectSignals(ws.symbol, candles)
                }

                signals.sortByDescending { it.confidence }
                _uiState.update {
                    it.copy(
                        signals = signals,
                        isScanning = false,
                        lastScanTime = System.currentTimeMillis(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, error = e.message) }
            }
        }
    }

    private fun detectSignals(symbol: String, candles: List<Candle>): List<StrategySignalItem> {
        val out = mutableListOf<StrategySignalItem>()

        // --- Harmonic patterns ---
        harmonicDetector(candles).take(2).forEach { pattern ->
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "Harmonic ${pattern.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                direction = pattern.direction,
                confidence = pattern.score.toInt(),
                entry = pattern.dPrice,
                stopLoss = pattern.stopLoss,
                takeProfit = pattern.tp1,
                riskReward = rr(pattern.dPrice, pattern.stopLoss, pattern.tp1),
                note = "PRZ ${"%.5f".format(pattern.prz.first)} - ${"%.5f".format(pattern.prz.second)}",
            )
        }

        // --- Order blocks (unmitigated) ---
        smcDetector.detectOrderBlocks(candles).filter { !it.mitigated }.take(1).forEach { ob ->
            val dir = if (ob.type.name == "BULLISH") Direction.BULLISH else Direction.BEARISH
            val entry = (ob.highPrice + ob.lowPrice) / 2.0
            val sl = if (dir == Direction.BULLISH) ob.lowPrice else ob.highPrice
            val tp = if (dir == Direction.BULLISH) entry + (entry - sl) * 2 else entry - (sl - entry) * 2
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "Order Block",
                direction = dir,
                confidence = (ob.strength * 100).toInt().coerceIn(0, 100),
                entry = entry, stopLoss = sl, takeProfit = tp,
                riskReward = rr(entry, sl, tp),
                note = "Institutional supply/demand zone",
            )
        }

        // --- Risk/Reward optimized setup aligned with bias ---
        val rrSetup = riskReward.optimize(candles, Direction.BULLISH)
        if (rrSetup.valid) {
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "R:R Optimized",
                direction = rrSetup.direction,
                confidence = 60,
                entry = rrSetup.entry,
                stopLoss = rrSetup.stopLoss,
                takeProfit = rrSetup.takeProfit1,
                riskReward = rrSetup.riskRewardRatio,
                note = rrSetup.reason,
            )
        }

        return out
    }

    private fun rr(entry: Double, sl: Double, tp: Double): Double {
        val risk = abs(entry - sl)
        val reward = abs(tp - entry)
        return if (risk > 0) reward / risk else 0.0
    }
}
