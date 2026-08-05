package com.foxtrader.app.domain.model

/**
 * A real-time price quote for a symbol streamed from the MT4 account via MetaApi.
 *
 * @param symbol The trading instrument (e.g. "EURUSD").
 * @param bid Current bid price.
 * @param ask Current ask price.
 * @param timestamp Epoch milliseconds when the quote was generated.
 * @param spread Difference between ask and bid (computed by default).
 */
data class Mt4Quote(
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val timestamp: Long,
    val spread: Double = ask - bid,
)
