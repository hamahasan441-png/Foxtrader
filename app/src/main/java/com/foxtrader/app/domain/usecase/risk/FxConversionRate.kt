package com.foxtrader.app.domain.usecase.risk

/**
 * Converts monetary amounts expressed in an instrument's quote currency into
 * the account's base currency.
 *
 * [rate] is the number of account-currency units per one quote-currency unit,
 * so `toAccountCurrency(x) = x * rate`. For example, for an account denominated
 * in EUR and a USD-quoted instrument, a rate of ~0.93 means one USD is worth
 * 0.93 EUR.
 */
data class FxConversionRate(
    val quoteCurrency: String,
    val accountCurrency: String,
    /** Account-currency units per 1 quote-currency unit. Must be finite and > 0. */
    val rate: Double,
) {
    init {
        require(quoteCurrency.isNotBlank()) { "Quote currency must not be blank" }
        require(accountCurrency.isNotBlank()) { "Account currency must not be blank" }
        require(rate.isFinite() && rate > 0.0) { "Rate must be finite and positive" }
    }

    /** Converts an amount expressed in the quote currency to the account currency. */
    fun toAccountCurrency(amountInQuote: Double): Double = amountInQuote * rate

    companion object {
        /**
         * Builds a rate from a *direct* quote: a price for the pair
         * `quoteCurrency/accountCurrency`. Example: quoteCurrency=USD,
         * accountCurrency=EUR, and the EURUSD price ~0.93 means 1 USD = 0.93 EUR.
         *
         * Returns `null` when the currencies are blank or the price is not a
         * positive finite number (a missing conversion must fail closed).
         */
        fun fromDirectPair(
            quoteCurrency: String,
            accountCurrency: String,
            directPrice: Double,
        ): FxConversionRate? {
            if (quoteCurrency.isBlank() || accountCurrency.isBlank()) return null
            if (!directPrice.isFinite() || directPrice <= 0.0) return null
            if (quoteCurrency.equals(accountCurrency, ignoreCase = true)) {
                return FxConversionRate(quoteCurrency, accountCurrency, 1.0)
            }
            return FxConversionRate(quoteCurrency, accountCurrency, directPrice)
        }

        /**
         * Builds a rate from an *inverse* quote: a price for the pair
         * `accountCurrency/quoteCurrency`. Example: quoteCurrency=USD,
         * accountCurrency=EUR, and the USDEUR price ~1.07 means 1 EUR = 1.07 USD,
         * so 1 USD = 1/1.07 EUR.
         *
         * Returns `null` when the currencies are blank or the price is not a
         * positive finite number.
         */
        fun fromInversePair(
            quoteCurrency: String,
            accountCurrency: String,
            inversePrice: Double,
        ): FxConversionRate? {
            if (quoteCurrency.isBlank() || accountCurrency.isBlank()) return null
            if (!inversePrice.isFinite() || inversePrice <= 0.0) return null
            if (quoteCurrency.equals(accountCurrency, ignoreCase = true)) {
                return FxConversionRate(quoteCurrency, accountCurrency, 1.0)
            }
            return FxConversionRate(quoteCurrency, accountCurrency, 1.0 / inversePrice)
        }
    }
}
