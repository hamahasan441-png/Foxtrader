package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Renko bricks from standard OHLCV candles.
 *
 * Each brick has a fixed [brickSize] body. A new bullish brick forms when
 * price exceeds the top of the previous brick by [brickSize]; a bearish brick
 * forms when price drops below the bottom by [brickSize]. Intermediate
 * fluctuations are ignored.
 *
 * The output candles carry the timestamp of the source candle that triggered
 * each new brick. Volume is summed from all contributing source candles.
 */
@Singleton
class CandleRenkoBuilder @Inject constructor() {

    fun build(candles: List<Candle>, brickSize: Double): List<Candle> {
        if (candles.isEmpty() || brickSize <= 0.0) return emptyList()

        val bricks = mutableListOf<Candle>()
        var basePrice = candles[0].close
        var accumulatedVolume = 0.0

        for (candle in candles) {
            accumulatedVolume += candle.volume

            // Build as many bricks as the price movement allows.
            while (true) {
                when {
                    candle.close >= basePrice + brickSize -> {
                        val brickOpen = basePrice
                        val brickClose = basePrice + brickSize
                        bricks.add(
                            Candle(
                                timestamp = candle.timestamp,
                                open = brickOpen,
                                high = brickClose,
                                low = brickOpen,
                                close = brickClose,
                                volume = accumulatedVolume,
                            )
                        )
                        basePrice = brickClose
                        accumulatedVolume = 0.0
                    }
                    candle.close <= basePrice - brickSize -> {
                        val brickOpen = basePrice
                        val brickClose = basePrice - brickSize
                        bricks.add(
                            Candle(
                                timestamp = candle.timestamp,
                                open = brickOpen,
                                high = brickOpen,
                                low = brickClose,
                                close = brickClose,
                                volume = accumulatedVolume,
                            )
                        )
                        basePrice = brickClose
                        accumulatedVolume = 0.0
                    }
                    else -> break
                }
            }
        }

        return bricks
    }
}
