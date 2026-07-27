package com.foxtrader.app.domain.usecase.calculator

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RiskCheckResult
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Position sizing that is checked against the app's actual risk gate.
 *
 * [PositionCalculator] is pure arithmetic: given a risk percentage and a stop
 * distance it returns a lot size. But [RiskEngine.canOpenTrade] is what the
 * order path actually enforces — daily/weekly loss limits, drawdown,
 * consecutive losses, per-trade risk cap, trading halt.
 *
 * Presenting a calculator result **without** that check would be actively
 * misleading: the app would hand a trader a size, and the order service would
 * then refuse it. Worse, during a halt or at a loss limit the calculator would
 * keep confidently suggesting trades the system has already decided must not
 * happen.
 *
 * So this use case computes the size and immediately asks the risk engine
 * whether that risk is permitted, returning both.
 */
@Singleton
class RiskAwarePositionCalculator @Inject constructor(
    private val calculator: PositionCalculator,
    private val instrumentTypeResolver: InstrumentTypeResolver,
    private val riskEngine: RiskEngine,
) {

    data class Request(
        val symbol: String,
        val direction: Direction,
        val entryPrice: Double,
        val stopLossPrice: Double,
        val takeProfitPrice: Double? = null,
        val riskPercent: Double,
        val accountBalance: Double,
        val leverage: Double = DEFAULT_LEVERAGE,
    )

    sealed interface Outcome {
        /** Inputs were not usable; nothing was computed. */
        data class Invalid(val reasons: List<String>) : Outcome

        /** A size was computed. [riskCheck] says whether it may actually be taken. */
        data class Sized(
            val result: PositionCalculator.CalculationResult,
            val instrumentType: PositionCalculator.InstrumentType,
            val partials: List<PositionCalculator.PartialCloseLevel>,
            val riskCheck: RiskCheckResult,
        ) : Outcome {
            val allowed: Boolean get() = riskCheck.allowed
        }
    }

    fun calculate(request: Request): Outcome {
        validate(request).let { problems ->
            if (problems.isNotEmpty()) return Outcome.Invalid(problems)
        }

        val instrumentType = instrumentTypeResolver.resolve(request.symbol)

        val result = calculator.calculate(
            PositionCalculator.CalculationInput(
                accountBalance = request.accountBalance,
                riskPercent = request.riskPercent,
                entryPrice = request.entryPrice,
                stopLossPrice = request.stopLossPrice,
                takeProfitPrice = request.takeProfitPrice,
                direction = request.direction,
                instrumentType = instrumentType,
                leverage = request.leverage,
            )
        )

        val partials = calculator.calculatePartials(
            entryPrice = request.entryPrice,
            stopLoss = request.stopLossPrice,
            direction = request.direction,
        )

        return Outcome.Sized(
            result = result,
            instrumentType = instrumentType,
            partials = partials,
            riskCheck = riskEngine.canOpenTrade(riskAmount = result.riskAmount),
        )
    }

    /**
     * Input validation.
     *
     * The stop-side check is the important one: a "stop" above entry on a long
     * is not a stop at all, it is a target. `PositionCalculator` uses
     * `abs(entry - stop)`, so it would happily size the trade and report a
     * plausible risk figure for a position that can never be stopped out where
     * the user thinks.
     */
    private fun validate(request: Request): List<String> {
        val problems = mutableListOf<String>()

        if (request.accountBalance <= 0.0) problems += "Account balance must be positive."
        if (request.riskPercent <= 0.0) problems += "Risk percent must be greater than zero."
        if (request.riskPercent > MAX_SANE_RISK_PERCENT) {
            problems += "Risking more than $MAX_SANE_RISK_PERCENT% of the account in one trade is not supported."
        }
        if (request.entryPrice <= 0.0) problems += "Entry price must be positive."
        if (request.stopLossPrice <= 0.0) problems += "Stop loss must be positive."
        if (request.leverage <= 0.0) problems += "Leverage must be positive."

        if (request.entryPrice > 0.0 && request.stopLossPrice > 0.0) {
            if (request.entryPrice == request.stopLossPrice) {
                problems += "Stop loss cannot equal the entry price."
            } else {
                val stopIsWrongSide = when (request.direction) {
                    Direction.BULLISH -> request.stopLossPrice > request.entryPrice
                    Direction.BEARISH -> request.stopLossPrice < request.entryPrice
                }
                if (stopIsWrongSide) {
                    problems += when (request.direction) {
                        Direction.BULLISH -> "For a long, the stop must sit below the entry."
                        Direction.BEARISH -> "For a short, the stop must sit above the entry."
                    }
                }
            }
        }

        request.takeProfitPrice?.let { target ->
            if (target <= 0.0) {
                problems += "Take profit must be positive."
            } else {
                val targetIsWrongSide = when (request.direction) {
                    Direction.BULLISH -> target < request.entryPrice
                    Direction.BEARISH -> target > request.entryPrice
                }
                if (targetIsWrongSide) {
                    problems += when (request.direction) {
                        Direction.BULLISH -> "For a long, the target must sit above the entry."
                        Direction.BEARISH -> "For a short, the target must sit below the entry."
                    }
                }
            }
        }

        return problems
    }

    private companion object {
        const val DEFAULT_LEVERAGE = 100.0

        /** Above this, the "risk percent" is almost certainly a typo. */
        const val MAX_SANE_RISK_PERCENT = 100.0
    }
}
