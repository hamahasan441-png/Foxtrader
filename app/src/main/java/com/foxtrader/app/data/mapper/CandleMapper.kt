package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.CandleEntity
import com.foxtrader.app.data.remote.dto.CandleDto
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
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

fun Candle.toEntity(
    symbol: String,
    timeframe: Timeframe,
    source: CandleSource = CandleSource.LIVE,
): CandleEntity = CandleEntity(
    symbol = symbol,
    timeframe = timeframe.label,
    timestamp = timestamp,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    source = source.name,
)

/** Collapses a cached series into its least trustworthy provenance. */
fun List<CandleEntity>.provenance(): CandleSource =
    CandleSource.worstOf(map { CandleSource.fromStorage(it.source) })

fun CandleDto.toDomain(): Candle = Candle(
    timestamp = timestamp,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
)
