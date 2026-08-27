package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The primary layer: the calls Compass judges.
 *
 * Compass is a filter, not a generator. It needs a stream of direction calls to
 * be right or wrong about, and the app's existing methodologies are that
 * stream. They are used exactly as they ship — the point is to learn which of
 * *their* calls hold up, and retuning them would change the thing being
 * measured while it was being measured.
 *
 * The source of each call is kept, because "which engine made this call" is
 * itself part of what the scorer can learn from.
 */
@Singleton
class CompassCallSource @Inject constructor(
    private val liquiditySweep: LiquiditySweepEngine,
    private val virginWick: VirginWickEngine,
    private val rsiOrderFlow: RsiOrderFlowSignalEngine,
    private val pivotSweepDivergence: PivotSweepDivergenceEngine,
    private val valueAreaRejection: ValueAreaLiquidityRejectionEngine,
    private val amd: AccumulationManipulationDistributionEngine,
) {

    fun calls(symbol: String, timeframe: Timeframe, candles: List<Candle>): List<CompassRawCall> {
        val out = ArrayList<CompassRawCall>()

        // A failing methodology costs its own calls, never the whole analysis.
        out += safely {
            liquiditySweep.analyze(symbol, timeframe, candles).signals
                .map { CompassRawCall("Liquidity Sweep", it.direction, it.entryIndex) }
        }
        out += safely {
            virginWick.analyze(symbol, timeframe, candles).signals
                .map { CompassRawCall("Virgin Wick", it.direction, it.entryIndex) }
        }
        out += safely {
            rsiOrderFlow.analyze(symbol, timeframe, candles).signals
                .map { CompassRawCall("RSI Orderflow", it.direction, it.confirmationIndex) }
        }
        // These three keep only their most recent `maxSignals` results by
        // default. That is a sensible display cap and a disastrous input to a
        // learning layer: as the series grows the oldest results silently
        // disappear, so history Compass already learned from would change
        // underneath it and identical bars would produce different signals.
        // Compass therefore asks for the uncapped view; the engines' own
        // defaults are untouched for every other caller.
        out += safely {
            pivotSweepDivergence
                .analyze(symbol, timeframe, candles, PivotSweepDivergenceEngine.Config(maxSignals = UNCAPPED))
                .signals
                .map { CompassRawCall("Pivot Sweep Divergence", it.direction, it.confirmationIndex) }
        }
        out += safely {
            valueAreaRejection
                .analyze(symbol, timeframe, candles, ValueAreaLiquidityRejectionEngine.Config(maxSignals = UNCAPPED))
                .signals
                .map { CompassRawCall("Value Area Rejection", it.direction, it.confirmationIndex) }
        }
        out += safely {
            amd
                .analyze(
                    symbol,
                    timeframe,
                    candles,
                    AccumulationManipulationDistributionEngine.Config(maxSignals = UNCAPPED),
                )
                .signals
                .map { CompassRawCall("AMD", it.direction, it.confirmationIndex) }
        }

        return out.sortedWith(compareBy({ it.index }, { it.source }))
    }

    private fun safely(block: () -> List<CompassRawCall>): List<CompassRawCall> =
        runCatching(block).getOrDefault(emptyList())

    private companion object {
        /** High enough that no realistic series reaches it. */
        const val UNCAPPED = 1_000_000
    }
}
