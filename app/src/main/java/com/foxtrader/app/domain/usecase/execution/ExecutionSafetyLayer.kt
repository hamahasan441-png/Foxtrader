package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Direction

/** Result of [ExecutionSafetyLayer.evaluate]. */
sealed class ExecutionSafetyDecision {
    /** The intent may proceed to the transport. */
    object Allowed : ExecutionSafetyDecision()

    /** The intent must not be submitted; [reasons] explains why. */
    data class Rejected(val reasons: List<String>) : ExecutionSafetyDecision()
}

/**
 * Fail-closed gate between a [TradeIntent] and the broker transport.
 *
 * Every control below defaults to blocking. If a check cannot be satisfied (a
 * required gate is unsatisfied, a value is non-finite, a quote is stale) the
 * intent is rejected rather than risked. Callers must treat [Rejected] as
 * "do not submit" and [Allowed] as "eligible to submit" — the coordinator still
 * performs idempotency reservation and duplicate-order blocking on top.
 */
class ExecutionSafetyLayer {

    fun evaluate(
        intent: TradeIntent,
        policy: ExecutionPolicy,
        context: ExecutionContext,
        now: Long = System.currentTimeMillis(),
    ): ExecutionSafetyDecision {
        val reasons = mutableListOf<String>()

        // ---- Live-mode master gate ---------------------------------------
        if (!policy.liveModeEnabled) {
            reasons += "Live execution is not enabled"
        }

        // ---- Emergency kill switch ----------------------------------------
        if (policy.emergencyKillSwitch) {
            reasons += "Emergency kill switch is engaged"
        }

        // ---- Explicit fresh confirmation ----------------------------------
        if (policy.requireFreshConfirmation) {
            if (intent.confirmationTimestamp <= 0L) {
                reasons += "No explicit trade confirmation provided"
            } else if (now - intent.confirmationTimestamp > policy.confirmationMaxAgeMs) {
                reasons += "Trade confirmation is stale; confirm again"
            }
        }

        // ---- Stale quote gate ----------------------------------------------
        context.quote?.let { quote ->
            val quoteAge = now - quote.timestamp
            if (quoteAge > policy.staleQuoteMaxAgeMs || quoteAge < 0L) {
                reasons += "Reference quote is stale (${quoteAge}ms old)"
            }
        }

        // ---- Max daily loss gate -------------------------------------------
        context.dailyLossInAccountCurrency?.let { dailyLoss ->
            if (policy.maxDailyLossInAccountCurrency > 0.0 && dailyLoss >= policy.maxDailyLossInAccountCurrency) {
                reasons += "Max daily loss reached (${dailyLoss} vs ${policy.maxDailyLossInAccountCurrency})"
            }
        }

        // ---- Free margin gate -----------------------------------------------
        context.freeMargin?.let { freeMargin ->
            if (policy.minFreeMarginInAccountCurrency > 0.0 && freeMargin < policy.minFreeMarginInAccountCurrency) {
                reasons += "Insufficient free margin (${freeMargin} < ${policy.minFreeMarginInAccountCurrency})"
            }
        }

        // ---- Broker volume constraints --------------------------------------
        context.spec?.let { spec ->
            if (!spec.isValidVolume(intent.volume)) {
                reasons += "Volume ${intent.volume} outside broker bounds " +
                    "[min=${spec.minVolume}, max=${spec.maxVolume}, step=${spec.volumeStep}]"
            }
        }

        // ---- Stop-loss direction ----------------------------------------------
        intent.stopLoss?.let { sl ->
            if (intent.direction == Direction.BULLISH && sl >= intent.entryPrice) {
                reasons += "Bullish stop-loss must be below entry"
            }
            if (intent.direction == Direction.BEARISH && sl <= intent.entryPrice) {
                reasons += "Bearish stop-loss must be above entry"
            }
        }

        // ---- Take-profit direction ---------------------------------------------
        intent.takeProfit?.let { tp ->
            if (intent.direction == Direction.BULLISH && tp <= intent.entryPrice) {
                reasons += "Bullish take-profit must be above entry"
            }
            if (intent.direction == Direction.BEARISH && tp >= intent.entryPrice) {
                reasons += "Bearish take-profit must be below entry"
            }
        }

        // ---- Slippage validation -----------------------------------------------
        intent.maxSlippagePoints?.let { slippage ->
            if (!slippage.isFinite() || slippage <= 0.0) {
                reasons += "Max slippage must be a positive finite number"
            }
        }

        return if (reasons.isEmpty()) ExecutionSafetyDecision.Allowed
        else ExecutionSafetyDecision.Rejected(reasons)
    }
}
