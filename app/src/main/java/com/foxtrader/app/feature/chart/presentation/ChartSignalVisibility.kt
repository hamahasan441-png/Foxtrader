package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.SignalSource

/**
 * Selects chart arrows without allowing the global history toggle to hide an
 * active LiT Adventure indicator's confirmed signals.
 */
internal fun selectVisibleChartSignals(
    signals: List<ChartSignal>,
    showSignalHistory: Boolean,
    litAdventureEnabled: Boolean,
    displayCandleCount: Int,
): List<ChartSignal> {
    if (showSignalHistory) return signals

    val recentCutoff = (displayCandleCount - RECENT_SIGNAL_BARS).coerceAtLeast(0)
    val recent = signals
        .filter { it.isLive || it.barIndex >= recentCutoff }
        .takeLast(RECENT_SIGNAL_LIMIT)
    if (!litAdventureEnabled) return recent

    val selected = LinkedHashMap<String, ChartSignal>()
    signals.asSequence()
        .filter { it.source == SignalSource.LITX }
        .forEach { selected[signalVisibilityKey(it)] = it }
    recent.forEach { selected[signalVisibilityKey(it)] = it }
    return selected.values.sortedWith(compareBy<ChartSignal> { it.barIndex }.thenBy { it.id })
}

private fun signalVisibilityKey(signal: ChartSignal): String =
    signal.eventKey?.takeIf { it.isNotBlank() } ?: "${signal.source}|${signal.id}"

private const val RECENT_SIGNAL_BARS = 120
private const val RECENT_SIGNAL_LIMIT = 24
