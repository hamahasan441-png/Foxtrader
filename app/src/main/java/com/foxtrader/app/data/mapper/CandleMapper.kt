package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.CandleEntity
import com.foxtrader.app.data.remote.dto.CandleDto
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe

// ============================================================================
// MAPPERS — translate between layers (DTO ↔ domain ↔ entity).
// Keeps the domain model pure and independent of Room/Retrofit shapes.
// ============================================================================

fun CandleEntity.toDomain(): Candle = Candle(
    timestamp = timestamp,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
)

fun Candle.toEntity(symbol: String, timeframe: Timeframe): CandleEntity = CandleEntity(
    symbol = symbol,
    timeframe = timeframe.label,
    timestamp = timestamp,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
)

fun CandleDto.toDomain(): Candle = Candle(
    timestamp = timestamp,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
)
