package com.foxtrader.app.domain.usecase.replay

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ReplaySpeed
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.domain.model.Tick
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replay Engine — bar-by-bar historical playback for practice and analysis.
 *
 * Feeds candles one at a time to simulate real-time market movement. The chart
 * renders only the causal prefix revealed so far, so every enabled indicator and
 * strategy is recomputed without future bars.
 *
 * Features:
 * - Play / Pause / Step forward / Step backward
 * - Adjustable speed (0.25x to 16x)
 * - Jump to any bar index
 * - Hard-bounded selected-history replay
 * - Integrates with the existing chart/indicator engine through visibleCandles
 */
@Singleton
class ReplayEngine @Inject constructor(
    private val tickAggregator: TickAggregator,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val _state = MutableStateFlow(ReplayState())
    val state: StateFlow<ReplayState> = _state.asStateFlow()

    private var allCandles: List<Candle> = emptyList()

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    /**
     * Legacy whole-tail replay. [startAt] uses the original exclusive visible
     * count convention so existing UI/tests remain compatible.
     */
    fun start(candles: List<Candle>, startAt: Int = 50) {
        if (candles.size < 2) {
            _state.value = ReplayState()
            return
        }
        val clamped = startAt.coerceIn(1, candles.size - 1)
        begin(candles, startVisibleCount = clamped, endVisibleCount = 0)
    }

    /**
     * Replay only a selected closed historical bar range.
     *
     * [startBarIndex] and [endBarIndex] are inclusive candle indices. Candles
     * before the selected start remain in [ReplayState.visibleCandles] as causal
     * warm-up/context, but playback cannot step/jump before the selected start or
     * beyond the selected end. Future bars after the selected end are never
     * exposed to the chart during this session.
     */
    fun startRange(candles: List<Candle>, startBarIndex: Int, endBarIndex: Int) {
        if (candles.size < 2) {
            _state.value = ReplayState()
            return
        }
        val startBar = startBarIndex.coerceIn(0, candles.lastIndex - 1)
        val endBar = endBarIndex.coerceIn(startBar + 1, candles.lastIndex)
        begin(
            candles = candles,
            startVisibleCount = startBar + 1,
            endVisibleCount = endBar + 1,
        )
    }

    /** Start a replay session from raw ticks, aggregating them into candles first. */
    fun startTickReplay(ticks: List<Tick>, aggregateTo: Timeframe, startAt: Int = 50) {
        val candles = tickAggregator.aggregate(ticks, aggregateTo)
        start(candles, startAt)
    }

    /** Stop replay and return to normal chart mode. */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        allCandles = emptyList()
        _state.value = ReplayState()
    }

    private fun begin(candles: List<Candle>, startVisibleCount: Int, endVisibleCount: Int) {
        playbackJob?.cancel()
        playbackJob = null
        allCandles = candles
        _state.value = ReplayState(
            isActive = true,
            isPaused = true,
            speed = _state.value.speed,
            currentIndex = startVisibleCount,
            totalBars = candles.size,
            startIndex = startVisibleCount,
            endIndex = endVisibleCount,
            visibleCandles = candles.subList(0, startVisibleCount),
        )
    }

    // ========================================================================
    // PLAYBACK CONTROLS
    // ========================================================================

    /** Start/resume auto-play. */
    fun play() {
        val state = _state.value
        if (!state.isActive || atPlaybackEnd(state)) {
            if (state.isActive) _state.value = state.copy(isPaused = true)
            return
        }
        _state.value = state.copy(isPaused = false)
        startPlaybackLoop()
    }

    /** Pause auto-play. */
    fun pause() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = _state.value.copy(isPaused = true)
    }

    /** Toggle play/pause. */
    fun togglePlayPause() {
        if (_state.value.isPaused) play() else pause()
    }

    /** Advance one bar forward, never beyond the selected range end. */
    fun stepForward() {
        val state = _state.value
        if (!state.isActive) return
        val next = (state.currentIndex + 1).coerceAtMost(playbackEnd(state))
        updateIndex(next)
    }

    /** Go one bar backward; bounded sessions cannot escape their selected start. */
    fun stepBackward() {
        val state = _state.value
        if (!state.isActive) return
        val floor = if (state.isBounded) state.startIndex else 1
        val prev = (state.currentIndex - 1).coerceAtLeast(floor)
        updateIndex(prev)
    }

    /** Jump to a replay visible-count index, clamped to the active range. */
    fun jumpTo(index: Int) {
        val state = _state.value
        if (!state.isActive) return
        val min = if (state.isBounded) state.startIndex else 1
        val clamped = index.coerceIn(min, playbackEnd(state))
        updateIndex(clamped)
    }

    /** Change playback speed. */
    fun setSpeed(speed: ReplaySpeed) {
        _state.value = _state.value.copy(speed = speed)
        if (_state.value.isPlaying) {
            playbackJob?.cancel()
            startPlaybackLoop()
        }
    }

    /** Cycle to next speed (wraps around). */
    fun cycleSpeed() {
        val speeds = ReplaySpeed.entries
        val currentIdx = speeds.indexOf(_state.value.speed)
        val next = speeds[(currentIdx + 1) % speeds.size]
        setSpeed(next)
    }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive && _state.value.isActive && !_state.value.isPaused) {
                delay(_state.value.speed.delayMs)
                val state = _state.value
                if (atPlaybackEnd(state)) {
                    _state.value = state.copy(isPaused = true)
                    break
                }
                updateIndex((state.currentIndex + 1).coerceAtMost(playbackEnd(state)))
            }
        }
    }

    private fun playbackEnd(state: ReplayState): Int =
        if (state.isBounded) state.endIndex else allCandles.size

    private fun atPlaybackEnd(state: ReplayState): Boolean =
        state.currentIndex >= playbackEnd(state)

    private fun updateIndex(newIndex: Int) {
        if (allCandles.isEmpty()) return
        val state = _state.value
        val min = if (state.isBounded) state.startIndex else 1
        val max = playbackEnd(state)
        val clamped = newIndex.coerceIn(min, max)
        _state.value = state.copy(
            currentIndex = clamped,
            visibleCandles = allCandles.subList(0, clamped),
            isPaused = if (clamped >= max) true else state.isPaused,
        )
    }
}
