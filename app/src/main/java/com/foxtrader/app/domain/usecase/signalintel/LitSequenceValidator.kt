package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitEventType
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.LitProContext
import com.foxtrader.app.domain.model.LitStage
import javax.inject.Inject

/**
 * Deterministic chronology gate for the canonical LiT Pro execution sequence.
 *
 * This class intentionally validates only semantics that are already explicit in
 * the repository: IDM -> opposite BOS -> CHOCH. Proprietary LIT entry-model names
 * that do not have an implementation/specification in this repository are not
 * inferred here.
 *
 * Keeping chronology in one pure validator prevents live, scanner and backtest
 * callers from slowly diverging into different Boolean combinations.
 */
class LitSequenceValidator @Inject constructor() {

    data class Result(
        val valid: Boolean,
        val stage: LitStage,
        val reason: String,
        val idmToBosBars: Int? = null,
        val bosToChochBars: Int? = null,
    )

    fun validate(context: LitProContext, config: LitConfig): Result {
        val cfg = config.sanitized()
        val idm = context.inducement
            ?: return invalid(LitStage.SCANNING, "missing confirmed IDM")
        val bos = context.bos
            ?: return invalid(LitStage.IDM_CONFIRMED, "missing BOS after IDM")
        val choch = context.choch
            ?: return invalid(LitStage.BOS_CONFIRMED, "missing CHOCH after BOS")

        if (idm.type != LitEventType.IDM) {
            return invalid(LitStage.SCANNING, "inducement event is not IDM")
        }
        if (bos.type != LitEventType.BOS) {
            return invalid(LitStage.IDM_CONFIRMED, "continuation event is not BOS")
        }
        if (choch.type != LitEventType.CHOCH) {
            return invalid(LitStage.BOS_CONFIRMED, "shift event is not CHOCH")
        }

        listOf(idm, bos, choch).forEach { level ->
            if (!isKnowable(level)) {
                return invalid(
                    LitStage.BOS_CONFIRMED,
                    "${level.type.name} has an invalid origin/confirmation boundary",
                )
            }
        }

        // Repository-defined LiT Pro lifecycle: IDM -> opposite BOS -> CHOCH.
        if (idm.confirmationIndex >= bos.confirmationIndex) {
            return invalid(
                LitStage.BOS_CONFIRMED,
                "IDM must be confirmed before BOS",
            )
        }
        if (bos.confirmationIndex >= choch.confirmationIndex) {
            return invalid(
                LitStage.BOS_CONFIRMED,
                "BOS must be confirmed before CHOCH",
            )
        }

        val idmToBosBars = bos.confirmationIndex - idm.confirmationIndex
        if (idmToBosBars > cfg.maxIdmToBosBars) {
            return invalid(
                LitStage.BOS_CONFIRMED,
                "IDM->BOS sequence expired ($idmToBosBars > ${cfg.maxIdmToBosBars} bars)",
                idmToBosBars = idmToBosBars,
            )
        }

        val bosToChochBars = choch.confirmationIndex - bos.confirmationIndex
        if (bosToChochBars > cfg.maxBosToChochBars) {
            return invalid(
                LitStage.BOS_CONFIRMED,
                "BOS->CHOCH sequence expired ($bosToChochBars > ${cfg.maxBosToChochBars} bars)",
                idmToBosBars = idmToBosBars,
                bosToChochBars = bosToChochBars,
            )
        }

        // The current repository implementation defines the IDM in the eventual
        // reversal direction and requires the preceding BOS to be opposite it.
        if (idm.direction == null || idm.direction != choch.direction) {
            return invalid(
                LitStage.BOS_CONFIRMED,
                "IDM direction must match CHOCH direction",
                idmToBosBars,
                bosToChochBars,
            )
        }
        if (bos.direction == null || bos.direction == choch.direction) {
            return invalid(
                LitStage.BOS_CONFIRMED,
                "BOS must be opposite the CHOCH direction",
                idmToBosBars,
                bosToChochBars,
            )
        }

        // POI is allowed to be absent while the engine is still waiting, but if
        // present it must be causally tied to this CHOCH and cannot predate its
        // own origin boundary.
        context.poi?.let { poi ->
            if (poi.originIndex < 0 || poi.confirmationIndex < poi.originIndex) {
                return invalid(
                    LitStage.CHOCH_CONFIRMED,
                    "POI has an invalid origin/confirmation boundary",
                    idmToBosBars,
                    bosToChochBars,
                )
            }
            if (poi.confirmationIndex != choch.confirmationIndex || poi.direction != choch.direction) {
                return invalid(
                    LitStage.CHOCH_CONFIRMED,
                    "POI is not tied to the active CHOCH",
                    idmToBosBars,
                    bosToChochBars,
                )
            }
        }

        context.scob?.let { scob ->
            if (scob.originIndex < 0 || scob.confirmationIndex < scob.originIndex) {
                return invalid(
                    LitStage.POI_READY,
                    "SCOB has an invalid origin/confirmation boundary",
                    idmToBosBars,
                    bosToChochBars,
                )
            }
            if (scob.direction != choch.direction || scob.confirmationIndex <= choch.confirmationIndex) {
                return invalid(
                    LitStage.POI_READY,
                    "SCOB must confirm after CHOCH in the CHOCH direction",
                    idmToBosBars,
                    bosToChochBars,
                )
            }
        }

        return Result(
            valid = true,
            stage = LitStage.CHOCH_CONFIRMED,
            reason = "IDM -> opposite BOS -> CHOCH chronology confirmed",
            idmToBosBars = idmToBosBars,
            bosToChochBars = bosToChochBars,
        )
    }

    private fun isKnowable(level: LitLevel): Boolean =
        level.originIndex >= 0 &&
            level.confirmationIndex >= level.originIndex &&
            level.price.isFinite() &&
            level.price > 0.0

    private fun invalid(
        stage: LitStage,
        reason: String,
        idmToBosBars: Int? = null,
        bosToChochBars: Int? = null,
    ) = Result(
        valid = false,
        stage = stage,
        reason = reason,
        idmToBosBars = idmToBosBars,
        bosToChochBars = bosToChochBars,
    )
}
