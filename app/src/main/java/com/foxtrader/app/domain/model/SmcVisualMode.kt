package com.foxtrader.app.domain.model

/**
 * Visual intensity for Smart Money overlays.
 * Does not change detection — only how loudly the chart paints structure.
 */
enum class SmcVisualMode(val label: String, val intensity: Float) {
    MINIMAL("Minimal", 0.45f),
    PROFESSIONAL("Professional", 1.0f),
    FULL("Full analysis", 1.35f),
}
