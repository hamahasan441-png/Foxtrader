package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.usecase.risk.InstrumentSpec

/**
 * Live market/account snapshot used by the execution safety layer to decide
 * whether an intent may be submitted. Missing fields disable the gates that
 * depend on them, but the caller is expected to supply whatever is available;
 * the safety layer treats an absent *conversion* as a hard failure (see
 * [ExecutionSafetyLayer]).
 */
data class ExecutionContext(
    /** Latest quote for the intent's symbol, if known. */
    val quote: Mt4Quote? = null,
    /** Broker-reported free margin in account currency, if known. */
    val freeMargin: Double? = null,
    /** Realized loss for the current day in account currency, if known. */
    val dailyLossInAccountCurrency: Double? = null,
    /** Account base currency, e.g. "USD". */
    val accountCurrency: String = "USD",
    /** Broker-authoritative instrument spec for the symbol, if known. */
    val spec: InstrumentSpec? = null,
    /** Account-currency units per 1 quote-currency unit. Null = unknown. */
    val quoteToAccountRate: Double? = null,
)
