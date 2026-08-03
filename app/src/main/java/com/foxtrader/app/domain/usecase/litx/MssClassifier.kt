package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Displacement
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import javax.inject.Inject

/**
 * Classifies the latest market shift from existing structure breaks (reuses
 * [com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase] output — no
 * duplicated structure detection).
 *
 * The upstream detector emits BOS/CHOCH but never MSS, even though the enum
 * defines it. LIT X upgrades a CHOCH to a Market Structure Shift (MSS) when it
 * is corroborated by an aligned [Displacement] — the "strong shift" that ICT
 * traders trade from.
 */
class MssClassifier @Inject constructor() {

    /** The classified shift; [present] is false when no CHOCH/MSS exists yet. */
    data class Result(
        val present: Boolean,
        val direction: Direction?,
        val type: StructureBreakType,
        val breakIndex: Int,
        /** True when a CHOCH was corroborated by aligned displacement. */
        val isStrong: Boolean,
    ) {
        companion object {
            val NONE = Result(false, null, StructureBreakType.BOS, -1, false)
        }
    }

    fun classify(
        breaks: List<StructureBreak>,
        displacement: Displacement?,
        displacementAtrMultiple: Double = 1.2,
    ): Result {
        val shift = breaks.lastOrNull {
            it.type == StructureBreakType.CHOCH || it.type == StructureBreakType.MSS
        } ?: return Result.NONE

        val strong = displacement != null &&
            displacement.direction == shift.direction &&
            displacement.atrMultiple >= displacementAtrMultiple

        return Result(
            present = true,
            direction = shift.direction,
            type = if (strong) StructureBreakType.MSS else shift.type,
            breakIndex = shift.breakIndex,
            isStrong = strong,
        )
    }
}
