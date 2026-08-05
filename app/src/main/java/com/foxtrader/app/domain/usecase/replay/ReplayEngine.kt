package com.foxtrader.app.domain.usecase.replay

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ReplaySpeed
import com.foxtrader.app.domain.model.ReplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replay Engine — bar-by-bar historical playback for practice and analysis.
 *
 * Feeds candles one at a time to simulate real-time market movement.
 * The chart renders only the candles revealed so far, allowing traders
 * to practice reading price action without hindsight bias.
 *
 * Features:
 * - Play / Pause / Step forward / Step backward
 * - Adjustable speed (0.25x to 16x)
 * - Jump to any bar index
 * - Integrates with existing chart engine (just provides a candle sublist)
 *
 * Pure domain logic — no Android dependencies.
 */
@Singleton
class ReplayEngine @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Guards [playbackJob] + [allCandles], which are touched from both the
    // Default-dispatcher playback loop and UI-thread control calls.
    private val lock = Any()
    private var playbackJob: Job? = null
    private var allCandles: List<Candle> = emptyList()

    private val _state = MutableStateFlow(ReplayState())
    val state: StateFlow<ReplayState> = _state.asStateFlow()

    /** Consistent snapshot of the current dataset under [lock]. */
    private fun candlesSnapshot(): List<Candle> = synchronized(lock) { allCandles }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    /**
     * Start a replay session from a specific bar index.
     * @param candles The full candle dataset to replay through
     * @param startAt The bar index to begin replay (candles before this are visible)
     */
    fun start(candles: List<Candle>, startAt: Int = 50) {
        // Need at least 2 candles to run a replay.
        if (candles.size < 2) {
            _state.value = ReplayState()
            return
        }
        synchronized(lock) { allCandles = candles }
        // Guard: coerceIn requires min <= max. With >=2 candles, size-1 >= 1.
        val clamped = startAt.coerceIn(1, candles.size - 1)
        _state.update { old ->
            ReplayState(
                isActive = true,
                isPaused = true,  // Start paused so user can see the initial state
                speed = old.speed,
                currentIndex = clamped,
                totalBars = candles.size,
                startIndex = clamped,
                visibleCandles = candles.subList(0, clamped),
            )
        }
    }

    /** Stop replay and return to normal chart mode. */
    fun stop() {
        synchronized(lock) {
            playbackJob?.cancel()
            playbackJob = null
            allCandles = emptyList()
        }
        _state.value = ReplayState()
    }

    // ========================================================================
    // PLAYBACK CONTROLS
    // ========================================================================

    /** Start/resume auto-play. */
    fun play() {
        if (!_state.value.isActive) return
        _state.update { it.copy(isPaused = false) }
        startPlaybackLoop()
    }

    /** Pause auto-play. */
    fun pause() {
        synchronized(lock) {
            playbackJob?.cancel()
            playbackJob = null
        }
        _state.update { it.copy(isPaused = true) }
    }

    /** Toggle play/pause. */
    fun togglePlayPause() {
        if (_state.value.isPaused) play() else pause()
    }

    /** Advance one bar forward. */
    fun stepForward() {
        if (!_state.value.isActive) return
        val next = (_state.value.currentIndex + 1).coerceAtMost(candlesSnapshot().size)
        updateIndex(next)
    }

    /** Go one bar backward. */
    fun stepBackward() {
        if (!_state.value.isActive) return
        val prev = (_state.value.currentIndex - 1).coerceAtLeast(1)
        updateIndex(prev)
    }

    /** Jump to a specific bar index. */
    fun jumpTo(index: Int) {
        if (!_state.value.isActive) return
        val clamped = index.coerceIn(1, candlesSnapshot().size)
        updateIndex(clamped)
    }

    /** Change playback speed. */
    fun setSpeed(speed: ReplaySpeed) {
        _state.update { it.copy(speed = speed) }
        // Restart playback loop with the new speed if playing.
        // (startPlaybackLoop cancels the previous job under the lock.)
        if (_state.value.isPlaying) {
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
        synchronized(lock) {
            playbackJob?.cancel()
            playbackJob = scope.launch {
                while (isActive && _state.value.isActive && !_state.value.isPaused) {
                    delay(_state.value.speed.delayMs)
                    val total = candlesSnapshot().size
                    val next = _state.value.currentIndex + 1
                    if (next > total) {
                        // Reached end — pause
                        _state.update { it.copy(isPaused = true) }
                        break
                    }
                    updateIndex(next)
                }
            }
        }
    }

    private fun updateIndex(newIndex: Int) {
        val candles = candlesSnapshot()
        if (candles.isEmpty()) return
        val clamped = newIndex.coerceIn(1, candles.size)
        _state.update {
            it.copy(
                currentIndex = clamped,
                visibleCandles = candles.subList(0, clamped),
            )
        }
    }
}
