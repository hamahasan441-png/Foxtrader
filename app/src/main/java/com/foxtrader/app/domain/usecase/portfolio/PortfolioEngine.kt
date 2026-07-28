package com.foxtrader.app.domain.usecase.portfolio

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.sdk.broker.Position
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.correlation.CorrelationMatrix
import javax.inject.Inject
import kotlin.math.abs

/**
 * Portfolio Engine — open-position exposure, P&L and correlation risk analysis.
 *
 * This is pure domain logic and deliberately broker-agnostic. Broker adapters
 * provide [Position] snapshots; this engine translates them into exposure
 * percentages that the Risk Engine can gate before a new order is created.
 *
 * Contract size is resolved **per instrument** from the symbol, not applied as a
 * single blanket forex lot across the whole book. A mixed portfolio (say 2 BTC
 * and 1 FX lot) has wildly different notional per unit; using one contract size
 * for both would over- or under-state exposure by orders of magnitude and feed
 * the risk gate a fictional number.
 */
class PortfolioEngine @Inject constructor(
    private val instrumentTypeResolver: InstrumentTypeResolver,
) {

    fun analyze(
        positions: List<Position>,
        accountEquity: Double,
        correlationMatrix: CorrelationMatrix.MatrixResult? = null,
        proposedPosition: ProposedPosition? = null,
        correlationThreshold: Double = DEFAULT_CORRELATION_THRESHOLD,
    ): PortfolioRiskSnapshot {
        require(accountEquity > 0.0) { "Account equity must be positive." }

        val exposures = positions.map { position ->
            val notional = notional(position.symbol, position.volume, position.currentPrice)
            PositionExposure(
                symbol = position.symbol,
                direction = position.direction,
                volume = position.volume,
                notional = notional,
                exposurePercent = percent(notional, accountEquity),
                unrealizedPnl = position.unrealizedPnl,
            )
        }
        val proposedExposure = proposedPosition?.let { proposed ->
            val proposedNotional = notional(proposed.symbol, proposed.volume, proposed.entryPrice)
            PositionExposure(
                symbol = proposed.symbol,
                direction = proposed.direction,
                volume = proposed.volume,
                notional = proposedNotional,
                exposurePercent = percent(proposedNotional, accountEquity),
                unrealizedPnl = 0.0,
                proposed = true,
            )
        }
        val allExposures = if (proposedExposure != null) exposures + proposedExposure else exposures
        val totalExposure = allExposures.sumOf { it.exposurePercent }
        val longExposure = allExposures
            .filter { it.direction == Direction.BULLISH }
            .sumOf { it.exposurePercent }
        val shortExposure = allExposures
            .filter { it.direction == Direction.BEARISH }
            .sumOf { it.exposurePercent }
        val bySymbol = allExposures
            .groupBy { it.symbol.uppercase() }
            .mapValues { (_, items) -> items.sumOf { it.exposurePercent } }
        val largestSymbol = bySymbol.maxByOrNull { it.value }
        val weighted = allExposures.map { exposure ->
            exposure.copy(
                weightPercent = if (totalExposure > 0.0) {
                    (exposure.exposurePercent / totalExposure) * 100.0
                } else 0.0
            )
        }
        val correlatedExposure = calculateCorrelatedExposure(
            bySymbol = bySymbol,
            matrix = correlationMatrix,
            proposedSymbol = proposedPosition?.symbol,
            threshold = correlationThreshold,
        )
        val warnings = buildWarnings(
            totalExposure = totalExposure,
            largestSymbol = largestSymbol,
            correlatedExposure = correlatedExposure,
        )

        return PortfolioRiskSnapshot(
            positions = weighted,
            totalExposurePercent = totalExposure,
            longExposurePercent = longExposure,
            shortExposurePercent = shortExposure,
            netDirectionalExposurePercent = longExposure - shortExposure,
            unrealizedPnl = positions.sumOf { it.unrealizedPnl },
            largestSymbol = largestSymbol?.key,
            largestSymbolExposurePercent = largestSymbol?.value ?: 0.0,
            correlatedExposurePercent = correlatedExposure,
            warnings = warnings,
        )
    }

    private fun calculateCorrelatedExposure(
        bySymbol: Map<String, Double>,
        matrix: CorrelationMatrix.MatrixResult?,
        proposedSymbol: String?,
        threshold: Double,
    ): Double {
        if (matrix == null || bySymbol.isEmpty()) return 0.0
        val normalizedProposed = proposedSymbol?.uppercase()
        if (normalizedProposed != null) {
            val correlatedSymbols = matrix.pairs
                .filter { pair ->
                    abs(pair.correlation) >= threshold &&
                        (pair.symbolA.equals(normalizedProposed, ignoreCase = true) ||
                            pair.symbolB.equals(normalizedProposed, ignoreCase = true))
                }
                .flatMap { listOf(it.symbolA.uppercase(), it.symbolB.uppercase()) }
                .toSet() + normalizedProposed
            return correlatedSymbols.sumOf { bySymbol[it] ?: 0.0 }
        }

        return bySymbol.keys.maxOfOrNull { seed ->
            val cluster = matrix.pairs
                .filter { pair ->
                    abs(pair.correlation) >= threshold &&
                        (pair.symbolA.equals(seed, ignoreCase = true) || pair.symbolB.equals(seed, ignoreCase = true))
                }
                .flatMap { listOf(it.symbolA.uppercase(), it.symbolB.uppercase()) }
                .toSet() + seed
            cluster.sumOf { bySymbol[it] ?: 0.0 }
        } ?: 0.0
    }

    private fun buildWarnings(
        totalExposure: Double,
        largestSymbol: Map.Entry<String, Double>?,
        correlatedExposure: Double,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (totalExposure > HIGH_TOTAL_EXPOSURE_PERCENT) {
            warnings += "High total exposure: ${totalExposure.toInt()}%"
        }
        if ((largestSymbol?.value ?: 0.0) > HIGH_SINGLE_SYMBOL_EXPOSURE_PERCENT) {
            warnings += "Concentrated symbol exposure: ${largestSymbol?.key} ${largestSymbol?.value?.toInt()}%"
        }
        if (correlatedExposure > HIGH_CORRELATED_EXPOSURE_PERCENT) {
            warnings += "High correlated exposure: ${correlatedExposure.toInt()}%"
        }
        return warnings
    }

    private fun notional(symbol: String, volume: Double, price: Double): Double =
        abs(volume) * price * instrumentTypeResolver.resolve(symbol).contractSize

    private fun percent(value: Double, equity: Double): Double = (value / equity) * 100.0

    private companion object {
        const val DEFAULT_CORRELATION_THRESHOLD = 0.7
        const val HIGH_TOTAL_EXPOSURE_PERCENT = 500.0
        const val HIGH_SINGLE_SYMBOL_EXPOSURE_PERCENT = 150.0
        const val HIGH_CORRELATED_EXPOSURE_PERCENT = 200.0
    }
}

data class ProposedPosition(
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val entryPrice: Double,
)

data class PositionExposure(
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val notional: Double,
    val exposurePercent: Double,
    val unrealizedPnl: Double,
    val weightPercent: Double = 0.0,
    val proposed: Boolean = false,
)

data class PortfolioRiskSnapshot(
    val positions: List<PositionExposure>,
    val totalExposurePercent: Double,
    val longExposurePercent: Double,
    val shortExposurePercent: Double,
    val netDirectionalExposurePercent: Double,
    val unrealizedPnl: Double,
    val largestSymbol: String?,
    val largestSymbolExposurePercent: Double,
    val correlatedExposurePercent: Double,
    val warnings: List<String>,
)
