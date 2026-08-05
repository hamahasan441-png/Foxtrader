package com.foxtrader.app.domain.model

/**
 * Unified chart signal combining LIT X, TradePro, and SMT signal data
 * for display on the chart as live/history markers.
 */
data class ChartSignal(
    val id: String,
    val source: SignalSource,
    val direction: Direction,
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val barIndex: Int,
    val timestamp: Long,
    val confidence: Double,
    val isLive: Boolean,
)

enum class SignalSource {
    LITX,
    TRADEPRO,
    SMT,
}
