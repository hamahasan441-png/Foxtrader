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

        // ---- Policy sanity -------------------------------------------------
        if (policy.requireFreshConfirmation && policy.confirmationMaxAgeMs <= 0L) {
            reasons += "Invalid confirmation timeout policy"
        }
        if (policy.staleQuoteMaxAgeMs <= 0L) {
            reasons += "Invalid stale-quote timeout policy"
        }
        if (!policy.maxDailyLossInAccountCurrency.isFinite() || policy.maxDailyLossInAccountCurrency < 0.0) {
            reasons += "Invalid max-daily-loss policy"
        }
        if (!policy.minFreeMarginInAccountCurrency.isFinite() || policy.minFreeMarginInAccountCurrency < 0.0) {
            reasons += "Invalid minimum-free-margin policy"
        }

        // ---- Explicit fresh confirmation ----------------------------------
        if (policy.requireFreshConfirmation) {
            val age = now - intent.confirmationTimestamp
            if (intent.confirmationTimestamp <= 0L) {
                reasons += "No explicit trade confirmation provided"
            } else if (age < 0L || age > policy.confirmationMaxAgeMs) {
                reasons += "Trade confirmation is stale or has an invalid clock; confirm again"
            }
        }

        // ---- Fresh quote gate (required for live market execution) ---------
        val quote = context.quote
        if (quote == null) {
            reasons += "Reference quote is unavailable"
        } else {
            val quoteAge = now - quote.timestamp
            if (!quote.bid.isFinite() || !quote.ask.isFinite() || quote.bid <= 0.0 || quote.ask <= 0.0 || quote.ask < quote.bid) {
                reasons += "Reference quote is invalid"
            }
            if (!quote.symbol.equals(intent.symbol, ignoreCase = true)) {
                reasons += "Reference quote symbol does not match trade intent"
            }
            if (quoteAge > policy.staleQuoteMaxAgeMs || quoteAge < 0L) {
                reasons += "Reference quote is stale (${quoteAge}ms old)"
            }
        }

        // ---- Max daily loss gate -------------------------------------------
        if (policy.maxDailyLossInAccountCurrency > 0.0) {
            val dailyLoss = context.dailyLossInAccountCurrency
            if (dailyLoss == null || !dailyLoss.isFinite() || dailyLoss < 0.0) {
                reasons += "Daily-loss data is unavailable or invalid"
            } else if (dailyLoss >= policy.maxDailyLossInAccountCurrency) {
                reasons += "Max daily loss reached (${dailyLoss} vs ${policy.maxDailyLossInAccountCurrency})"
            }
        }

        // ---- Free margin gate -----------------------------------------------
        if (policy.minFreeMarginInAccountCurrency > 0.0) {
            val freeMargin = context.freeMargin
            if (freeMargin == null || !freeMargin.isFinite() || freeMargin < 0.0) {
                reasons += "Free-margin data is unavailable or invalid"
            } else if (freeMargin < policy.minFreeMarginInAccountCurrency) {
                reasons += "Insufficient free margin (${freeMargin} < ${policy.minFreeMarginInAccountCurrency})"
            }
        }

        // ---- Broker volume constraints --------------------------------------
        val spec = context.spec
        if (spec == null) {
            reasons += "Broker instrument specification is unavailable"
        } else {
            if (spec.isEstimated) {
                reasons += "Broker instrument specification is estimated; live execution requires authoritative metadata"
            }
            if (!spec.symbol.equals(intent.symbol, ignoreCase = true)) {
                reasons += "Broker instrument specification does not match trade intent"
            }
            if (!spec.isValidVolume(intent.volume)) {
                reasons += "Volume ${intent.volume} outside broker bounds " +
                    "[min=${spec.minVolume}, max=${spec.maxVolume}, step=${spec.volumeStep}]"
            }
        }

        // Use the side that a market order actually executes against. This
        // protects SL/TP validation from a widened spread between review and
        // submission. Fall back to the reviewed entry only when the quote is
        // already invalid/missing (which is independently rejected above).
        val executableEntry = quote?.let { q ->
            if (intent.direction == Direction.BULLISH) q.ask else q.bid
        }?.takeIf { it.isFinite() && it > 0.0 } ?: intent.entryPrice

        // ---- Stop-loss direction ----------------------------------------------
        intent.stopLoss?.let { sl ->
            if (intent.direction == Direction.BULLISH && sl >= executableEntry) {
                reasons += "Bullish stop-loss must be below executable entry"
            }
            if (intent.direction == Direction.BEARISH && sl <= executableEntry) {
                reasons += "Bearish stop-loss must be above executable entry"
            }
        }

        // ---- Take-profit direction ---------------------------------------------
        intent.takeProfit?.let { tp ->
            if (intent.direction == Direction.BULLISH && tp <= executableEntry) {
                reasons += "Bullish take-profit must be above executable entry"
            }
            if (intent.direction == Direction.BEARISH && tp >= executableEntry) {
                reasons += "Bearish take-profit must be below executable entry"
            }
        }

        // ---- Review-to-submit price drift / slippage validation ----------------
        intent.maxSlippagePoints?.let { slippage ->
            if (!slippage.isFinite() || slippage <= 0.0) {
                reasons += "Max slippage must be a positive finite number"
            } else if (spec != null && spec.point.isFinite() && spec.point > 0.0 &&
                executableEntry.isFinite() && intent.entryPrice.isFinite()
            ) {
                val driftPoints = kotlin.math.abs(executableEntry - intent.entryPrice) / spec.point
                if (!driftPoints.isFinite() || driftPoints > slippage + 1e-9) {
                    reasons += "Price moved %.1f points since review (max %.1f); review the order again"
                        .format(java.util.Locale.US, driftPoints, slippage)
                }
            }
        }

        return if (reasons.isEmpty()) ExecutionSafetyDecision.Allowed
        else ExecutionSafetyDecision.Rejected(reasons)
    }
}
