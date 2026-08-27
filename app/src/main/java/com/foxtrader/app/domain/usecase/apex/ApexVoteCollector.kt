package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.apex.model.ApexVote
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the member methodologies and normalises what they produce.
 *
 * Each member keeps its own rules and its own defaults. Apex deliberately does
 * not retune them: a member that has been changed to agree more often is no
 * longer independent evidence, and independence is the only reason agreement
 * between them is worth anything.
 *
 * A member that throws is dropped rather than allowed to take the whole
 * analysis down — one misbehaving methodology should cost its own vote, not
 * every other one.
 */
@Singleton
class ApexVoteCollector @Inject constructor(
    private val liquiditySweep: LiquiditySweepEngine,
    private val virginWick: VirginWickEngine,
    private val rsiOrderFlow: RsiOrderFlowSignalEngine,
    private val pivotSweepDivergence: PivotSweepDivergenceEngine,
    private val valueAreaRejection: ValueAreaLiquidityRejectionEngine,
    private val amd: AccumulationManipulationDistributionEngine,
) {

    fun collect(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: ApexConfig,
    ): List<ApexVote> {
        val out = ArrayList<ApexVote>()

        for (member in config.members) {
            val votes = runCatching { votesFor(member, symbol, timeframe, candles) }.getOrDefault(emptyList())
            out += votes.filter { it.index in candles.indices && isWellFormed(it) }
        }
        return out.sortedWith(compareBy({ it.index }, { it.member.name }))
    }

    private fun votesFor(
        member: ApexMember,
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
    ): List<ApexVote> = when (member) {
        ApexMember.LIQUIDITY_SWEEP ->
            liquiditySweep.analyze(symbol, timeframe, candles).signals.map {
                ApexVote(member, it.direction, it.entryIndex, it.timestamp, it.entry, it.stop, it.target)
            }

        ApexMember.VIRGIN_WICK ->
            virginWick.analyze(symbol, timeframe, candles).signals.map {
                ApexVote(member, it.direction, it.entryIndex, it.timestamp, it.entry, it.stop, it.target)
            }

        ApexMember.RSI_ORDERFLOW ->
            rsiOrderFlow.analyze(symbol, timeframe, candles).signals.map {
                ApexVote(member, it.direction, it.confirmationIndex, it.timestamp, it.entry, it.stopLoss, it.takeProfit)
            }

        ApexMember.PIVOT_SWEEP_DIVERGENCE ->
            pivotSweepDivergence.analyze(symbol, timeframe, candles).signals.map {
                ApexVote(member, it.direction, it.confirmationIndex, it.timestamp, it.entry, it.stopLoss, it.takeProfit)
            }

        ApexMember.VALUE_AREA_REJECTION ->
            valueAreaRejection.analyze(symbol, timeframe, candles).signals.map {
                ApexVote(member, it.direction, it.confirmationIndex, it.timestamp, it.entry, it.stopLoss, it.takeProfit)
            }

        ApexMember.AMD ->
            amd.analyze(symbol, timeframe, candles).signals.map {
                ApexVote(member, it.direction, it.confirmationIndex, it.timestamp, it.entry, it.stopLoss, it.takeProfit)
            }
    }

    private fun isWellFormed(vote: ApexVote): Boolean =
        vote.entry.isFinite() && vote.entry > 0.0 &&
            vote.stop.isFinite() && vote.stop > 0.0 &&
            vote.target.isFinite() && vote.target > 0.0 &&
            vote.stop != vote.entry
}
