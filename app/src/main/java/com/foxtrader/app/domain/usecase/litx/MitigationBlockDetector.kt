package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.MitigationBlock
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import javax.inject.Inject
import kotlin.math.min

/**
 * Derives Mitigation Blocks from already-detected order blocks (reuses
 * [com.foxtrader.app.domain.usecase.smc.SmcDetector.detectOrderBlocks] output —
 * no duplicated OB detection).
 *
 * A mitigation block is an order block that price returned into (mitigated) and
 * then *reacted away from in the OB's original direction* — i.e. the zone still
 * holds its bias (unlike a breaker, which flips). Non-repainting: the reaction
 * is measured only from bars that already closed after the mitigation.
 */
class MitigationBlockDetector @Inject constructor() {

    fun detect(candles: List<Candle>, orderBlocks: List<OrderBlock>): List<MitigationBlock> {
        if (candles.size < MIN_BARS || orderBlocks.isEmpty()) return emptyList()
        val result = mutableListOf<MitigationBlock>()

        for (ob in orderBlocks) {
            if (!ob.mitigated) continue
            // First bar after the OB forms that trades back into the zone.
            val mitigationIndex = ((ob.endIndex + 1) until candles.size).firstOrNull { idx ->
                candles[idx].low <= ob.highPrice && candles[idx].high >= ob.lowPrice
            } ?: continue

            val reactionEnd = min(mitigationIndex + REACTION_BARS, candles.lastIndex)
            if (reactionEnd <= mitigationIndex) continue

            val mitClose = candles[mitigationIndex].close
            val reactionClose = candles[reactionEnd].close
            val direction = if (ob.type == OrderBlockType.BULLISH) Direction.BULLISH else Direction.BEARISH

            // Respected iff price moved back in the OB's favour after the tap.
            val respected = when (direction) {
                Direction.BULLISH -> reactionClose > mitClose
                Direction.BEARISH -> reactionClose < mitClose
            }
            if (!respected) continue

            result += MitigationBlock(
                direction = direction,
                highPrice = ob.highPrice,
                lowPrice = ob.lowPrice,
                originIndex = ob.startIndex,
                mitigationIndex = mitigationIndex,
                strength = ob.strength,
                confirmationIndex = reactionEnd,
            )
        }
        return result
    }

    private companion object {
        const val MIN_BARS = 10
        const val REACTION_BARS = 3
    }
}
