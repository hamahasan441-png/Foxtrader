package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.compass.model.CompassAccuracy
import com.foxtrader.app.domain.usecase.compass.model.CompassCalibration
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict

/**
 * Chooses the confidence threshold above which calls may be published, and
 * decides whether any threshold can be justified at all.
 *
 * The naive version of this is a trap. Score the past calls, try every
 * threshold, keep the one whose accuracy looks best, report that number. It
 * will regularly show 80%+ on pure noise, because trying many thresholds and
 * keeping the winner *is* a search for a lucky subset — the more thresholds
 * tried, the luckier the winner, and none of it survives contact with new data.
 *
 * So the search is treated as what it is: multiple hypothesis testing. Each
 * candidate threshold is a hypothesis "calls above this are at least
 * `minAccuracy` accurate". Each is tested with a lower confidence bound, and
 * the level is split across every candidate tried (a Bonferroni correction), so
 * a wider search makes each individual candidate *harder* to justify rather
 * than easier. Testing twenty thresholds at 95% and reporting the best is a
 * 64% confidence claim wearing a 95% label; splitting the level is what makes
 * the label true.
 *
 * The most conservative surviving threshold is selected rather than the
 * best-scoring one, for the same reason: the highest sample accuracy in a set
 * of candidates is the most likely to be the luckiest.
 */
object CompassCalibrator {

    /** One past call the calibrator learns the threshold from. */
    data class Scored(
        val probability: Double,
        val direction: Direction,
        val verdict: CompassVerdict,
    )

    /**
     * @param scored resolved calls, oldest first.
     */
    fun calibrate(
        scored: List<Scored>,
        config: CompassConfig,
    ): CompassCalibration {
        val resolved = scored.filter { it.verdict == CompassVerdict.RIGHT || it.verdict == CompassVerdict.WRONG }
        val grid = config.thresholdGrid.filter { it.isFinite() }.distinct().sorted()

        if (grid.isEmpty()) return CompassCalibration.none("Compass: no candidate thresholds.")
        if (resolved.size < config.minCalibrationSample) {
            return CompassCalibration.none(
                "Compass: ${resolved.size} of ${config.minCalibrationSample} resolved calls needed to calibrate.",
                grid.size,
            )
        }

        // Split the error budget across every candidate the search will try.
        val perCandidateConfidence = 1.0 - (1.0 - config.confidence) / grid.size
        var best: CompassCalibration? = null

        for (threshold in grid) {
            val selected = resolved.filter { it.probability >= threshold }
            if (selected.size < config.minCalibrationSample) continue

            val accuracy = CompassAccuracy.of(selected.map { it.direction to it.verdict }, perCandidateConfidence)
            val bound = accuracy.accuracyLowerBound ?: continue
            val lift = accuracy.lift ?: continue

            // Both bars must clear: the accuracy asked for, and enough
            // separation from the base rate that the number is skill and not
            // the majority outcome restated. Which accuracy is read — the
            // measured one or its confidence bound — is the caller's choice,
            // because the bound is the honest reading of a small sample and
            // also the thing that keeps the study silent on one.
            val judged = if (config.useConfidenceBound) bound else accuracy.accuracy ?: continue
            if (judged < config.minAccuracy) continue
            if (lift < config.minLiftOverBaseRate) continue

            val candidate = CompassCalibration(
                threshold = threshold,
                selected = selected.size,
                accuracy = accuracy,
                candidatesTested = grid.size,
                reason = "Threshold $threshold justified: ${percent(accuracy.accuracy)} accurate over " +
                    "${selected.size} calls (at least ${percent(bound)} at " +
                    "${percent(config.confidence)} confidence across ${grid.size} candidates), " +
                    "base rate ${percent(accuracy.baseRate)}",
            )
            // The first survivor is the least selective one, which admits the
            // most calls and relies least on having found a lucky subset.
            if (best == null) best = candidate
        }

        return best ?: CompassCalibration.none(
            noThresholdReason(resolved, grid, config, perCandidateConfidence),
            grid.size,
        )
    }

    private fun noThresholdReason(
        resolved: List<Scored>,
        grid: List<Double>,
        config: CompassConfig,
        perCandidateConfidence: Double,
    ): String {
        // Report the best any candidate managed, so a silent engine can still
        // say how far short it fell rather than only that it fell short.
        val attempts = grid.mapNotNull { threshold ->
            val selected = resolved.filter { it.probability >= threshold }
            if (selected.size < config.minCalibrationSample) {
                null
            } else {
                threshold to CompassAccuracy.of(
                    selected.map { it.direction to it.verdict },
                    perCandidateConfidence,
                )
            }
        }
        val bestBound = attempts.maxByOrNull {
            if (config.useConfidenceBound) it.second.accuracyLowerBound ?: 0.0 else it.second.accuracy ?: 0.0
        }
            ?: return "Compass silent — no threshold retained ${config.minCalibrationSample} calls to judge."

        val accuracy = bestBound.second
        return "Compass silent — best candidate measured ${percent(accuracy.accuracy)} over " +
            "${accuracy.resolved} calls (at least ${percent(accuracy.accuracyLowerBound)} at " +
            "${percent(config.confidence)} across ${grid.size} candidates, base rate " +
            "${percent(accuracy.baseRate)}), short of ${percent(config.minAccuracy)}"
    }

    private fun percent(value: Double?): String =
        if (value == null) "n/a" else "${(value * 100).toInt()}%"
}
