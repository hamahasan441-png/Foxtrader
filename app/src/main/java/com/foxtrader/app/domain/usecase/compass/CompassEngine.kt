package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.compass.model.CompassAccuracy
import com.foxtrader.app.domain.usecase.compass.model.CompassAnalysis
import com.foxtrader.app.domain.usecase.compass.model.CompassCalibration
import com.foxtrader.app.domain.usecase.compass.model.CompassCall
import com.foxtrader.app.domain.usecase.compass.model.CompassSignal
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compass — directional accuracy with a selective-accuracy guarantee.
 *
 * Where the consensus engine asks *do the methodologies agree*, Compass asks a
 * narrower and more answerable question: **given everything visible at this
 * bar, how often has a call like this one been right?** It publishes only the
 * calls whose estimated probability clears a threshold that has been shown, on
 * past data the engine had already seen resolve, to deliver the required
 * accuracy.
 *
 * Three things are worth stating plainly.
 *
 * **Accuracy here means direction and nothing else.** The barrier is the same
 * distance on both sides, so the figure cannot be improved by moving a target
 * closer — the manoeuvre that turns most published win rates into fiction.
 *
 * **Accuracy is always reported against its base rate.** Eighty percent where a
 * skill-free rule scores seventy-eight is nearly nothing. The engine requires
 * both an absolute level and a margin over the base rate, because either alone
 * is easy to satisfy without any skill at all.
 *
 * **The threshold search is corrected for its own size.** Trying twenty
 * cut-offs and keeping the best is a search for a lucky subset; the confidence
 * level is divided across the candidates so that a wider search makes each one
 * harder to justify rather than easier.
 *
 * What none of this promises is that the next call will be right. It promises
 * that while the engine cannot demonstrate the accuracy asked of it, on data
 * it did not use to choose the threshold, it publishes nothing.
 */
@Singleton
class CompassEngine @Inject constructor(
    private val callSource: CompassCallSource,
) {

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: CompassConfig = CompassConfig(),
    ): CompassAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) return CompassAnalysis.empty(integrity.reason ?: "Invalid market data.")

        val calls = buildCalls(symbol, timeframe, candles, config)
        if (calls.isEmpty()) return CompassAnalysis.empty("Compass: no primary calls to judge yet.")

        val barriers = calls.associate {
            it.index to CompassLabeler.atrAt(candles, it.index, config.atrPeriod) * config.barrierAtrMultiple
        }
        val signals = publish(symbol, timeframe, calls, barriers, config)
        val published = if (config.historicalSignals) {
            signals
        } else {
            val cutoff = candles.lastIndex - config.liveWindowBars + 1
            signals.filter { it.index >= cutoff }
        }

        val rawAccuracy = CompassAccuracy.of(calls.map { it.direction to it.verdict }, config.confidence)
        val publishedAccuracy = CompassAccuracy.of(
            published.map { it.call.direction to it.call.verdict },
            config.confidence,
        )
        val finalCalibration = calibrationAt(calls, candles.lastIndex + 1, config).second

        return CompassAnalysis(
            calls = calls,
            signals = published,
            rawAccuracy = rawAccuracy,
            publishedAccuracy = publishedAccuracy,
            calibration = finalCalibration,
            statusText = statusText(calls, published, rawAccuracy, finalCalibration, config),
        )
    }

    // ------------------------------------------------------------------
    // Primary calls
    // ------------------------------------------------------------------

    private fun buildCalls(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: CompassConfig,
    ): List<CompassCall> = callSource.calls(symbol, timeframe, candles)
        .asSequence()
        .filter { it.index in candles.indices }
        .distinctBy { Triple(it.index, it.source, it.direction) }
        .sortedBy { it.index }
        .mapNotNull { raw ->
            val atr = CompassLabeler.atrAt(candles, raw.index, config.atrPeriod)
            if (atr <= 0.0) return@mapNotNull null
            val barrier = atr * config.barrierAtrMultiple

            val (verdict, decidedIndex) = CompassLabeler.judge(
                candles = candles,
                index = raw.index,
                direction = raw.direction,
                barrier = barrier,
                horizonBars = config.horizonBars,
            )

            CompassCall(
                source = raw.source,
                direction = raw.direction,
                index = raw.index,
                timestamp = candles[raw.index].timestamp,
                price = candles[raw.index].close,
                features = CompassFeatures.extract(candles, raw.index, raw.direction),
                verdict = verdict,
                decidedIndex = decidedIndex,
            )
        }
        .toList()

    // ------------------------------------------------------------------
    // Walk-forward publication
    // ------------------------------------------------------------------

    /**
     * The scorer and threshold in force at [asOfIndex].
     *
     * Both are built **only** from calls whose verdict was already known before
     * that bar. Fitting on a call and then judging it is not a mistake that
     * shows up as a small optimism — it produces near-perfect accuracy and
     * teaches nothing.
     */
    private fun calibrationAt(
        calls: List<CompassCall>,
        asOfIndex: Int,
        config: CompassConfig,
    ): Pair<CompassScorer, CompassCalibration> {
        val known = calls
            .filter { it.resolved && (it.decidedIndex ?: Int.MAX_VALUE) < asOfIndex }
            .sortedBy { it.decidedIndex }
            .takeLast(config.learningWindow)

        if (known.size < config.minCalibrationSample) {
            return CompassScorer.UNINFORMED to CompassCalibration.none(
                "Compass: ${known.size} of ${config.minCalibrationSample} resolved calls needed to calibrate.",
                config.thresholdGrid.size,
            )
        }

        val scorer = CompassScorer.fit(known.map { it.features to it.verdict })
        val calibration = CompassCalibrator.calibrate(
            scored = known.map {
                CompassCalibrator.Scored(scorer.probability(it.features), it.direction, it.verdict)
            },
            config = config,
        )
        return scorer to calibration
    }

    private fun publish(
        symbol: String,
        timeframe: Timeframe,
        calls: List<CompassCall>,
        barriers: Map<Int, Double>,
        config: CompassConfig,
    ): List<CompassSignal> {
        val out = ArrayList<CompassSignal>()
        var scorer = CompassScorer.UNINFORMED
        var calibration = CompassCalibration.none("Compass: not calibrated yet.", config.thresholdGrid.size)
        // Nullable rather than a sentinel: `index - Int.MIN_VALUE` overflows to
        // a negative number, so a sentinel silently means "never recalibrate"
        // and the engine goes quiet for a reason that has nothing to do with
        // the market.
        var recalibratedAt: Int? = null

        for (call in calls) {
            // Recalibrating on every call would be correct and far too slow;
            // recalibrating on a schedule is the same decision made slightly
            // less often, and always from strictly past information.
            val due = recalibratedAt?.let { call.index - it >= config.recalibrateEveryBars } ?: true
            if (due) {
                val (fitted, decided) = calibrationAt(calls, call.index, config)
                scorer = fitted
                calibration = decided
                recalibratedAt = call.index
            }

            // Never draw where the engine itself could not have run. Below its
            // minimum the analysis refuses outright, so a signal there is one no
            // live chart could ever have shown — visible only because a longer
            // series was available afterwards.
            if (call.index < MIN_BARS) continue

            val threshold = calibration.threshold
            val probability = scorer.probability(call.features)
            when {
                // Calibrated: the threshold selects, which is the study's job.
                threshold != null -> if (probability < threshold) continue
                // Not calibrated yet. Draw the setups the member engines found
                // and attach whatever the scorer can say about them, rather
                // than showing an empty chart until a cut-off can be justified.
                !config.publishBeforeCalibrated -> continue
            }

            out += CompassSignal(
                symbol = symbol,
                timeframe = timeframe,
                call = call,
                probability = probability,
                barrier = barriers[call.index] ?: continue,
                calibration = calibration,
                reasons = reasonsFor(call, probability, calibration),
            )
        }
        return out
    }

    private fun reasonsFor(
        call: CompassCall,
        probability: Double,
        calibration: CompassCalibration,
    ): List<String> = buildList {
        add("${call.source} call, scored ${(probability * 100).toInt()}%")
        add(
            "Threshold ${((calibration.threshold ?: 0.0) * 100).toInt()}% earned on " +
                "${calibration.selected} past calls at ${percent(calibration.accuracy.accuracy)} " +
                "(base rate ${percent(calibration.accuracy.baseRate)})",
        )
    }

    private fun statusText(
        calls: List<CompassCall>,
        published: List<CompassSignal>,
        raw: CompassAccuracy,
        calibration: CompassCalibration,
        config: CompassConfig,
    ): String = when {
        !calibration.guaranteed -> calibration.reason
        published.isEmpty() ->
            "Compass: threshold ${percent(calibration.threshold)} in force, no call has cleared it yet"
        else ->
            "Compass: ${published.size} of ${calls.size} calls published · unfiltered " +
                "${percent(raw.accuracy)} (base rate ${percent(raw.baseRate)}) · required " +
                "${percent(config.minAccuracy)}"
    }

    private fun percent(value: Double?): String =
        if (value == null) "n/a" else "${(value * 100).toInt()}%"

    // ------------------------------------------------------------------
    // Backtest entry points
    // ------------------------------------------------------------------

    /**
     * A published call as a tradeable signal.
     *
     * The stop and target are placed at the **same** barrier the accuracy was
     * measured against. Any other geometry would make the reported accuracy a
     * statistic about a trade nobody took.
     */
    fun signalAt(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
        config: CompassConfig = CompassConfig(),
    ): StrategySignal? {
        if (index !in candles.indices) return null
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)
        val signal = analyze(symbol, timeframe, visible, config).signals
            .lastOrNull { it.index == index } ?: return null
        return signal.toStrategySignal()
    }

    /**
     * The same function computed in one pass, for the backtester.
     *
     * Legitimate for the same reason the per-bar version is: every scorer fit
     * and every threshold comes from calls resolved strictly before the bar
     * being decided, so a longer series cannot change what was published
     * earlier. `CompassEngineTest` asserts the two agree.
     */
    fun backtestFunction(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: CompassConfig = CompassConfig(),
    ): (List<Candle>, Int) -> StrategySignal? {
        val byIndex = analyze(symbol, timeframe, candles, config).signals.associateBy { it.index }
        return { visible, index ->
            val bar = visible.getOrNull(index)
            byIndex[index]
                ?.takeIf { bar != null && bar.timestamp == it.timestamp }
                ?.toStrategySignal()
        }
    }

    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: CompassConfig = CompassConfig(),
    ): (List<Candle>, Int) -> StrategySignal? = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    private fun CompassSignal.toStrategySignal(): StrategySignal? {
        if (!barrier.isFinite() || barrier <= 0.0) return null
        val bullish = direction == Direction.BULLISH

        return StrategySignal(
            index = index,
            timestamp = timestamp,
            direction = direction,
            entry = price,
            stopLoss = if (bullish) price - barrier else price + barrier,
            takeProfit = if (bullish) price + barrier else price - barrier,
            confidence = (probability * 100).toInt(),
            setupType = "Compass ${call.source}",
        )
    }

    fun minimumBars(): Int = MIN_BARS

    companion object {
        /**
         * Enough bars for calls to exist, resolve, and still leave a
         * calibration window behind them.
         */
        const val MIN_BARS = 400
    }
}

/** One primary call before Compass judges it. */
data class CompassRawCall(
    val source: String,
    val direction: Direction,
    val index: Int,
)
