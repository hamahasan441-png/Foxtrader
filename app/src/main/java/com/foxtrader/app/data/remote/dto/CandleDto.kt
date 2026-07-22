package com.foxtrader.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Network DTO for a candle from the FoxTrader backend (FastAPI).
 * Serialized with kotlinx.serialization.
 */
@Serializable
data class CandleDto(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)

@Serializable
data class CandlesResponse(
    val symbol: String,
    val timeframe: String,
    val candles: List<CandleDto>,
)
