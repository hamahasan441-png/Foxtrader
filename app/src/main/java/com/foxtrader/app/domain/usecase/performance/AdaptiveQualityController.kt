package com.foxtrader.app.domain.usecase.performance

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive Quality Controller — dynamically adjusts decorative/render density
 * without changing the semantic content the trader explicitly selected.
 *
 * A selected study must never disappear because the device had a slow frame.
 * Doing so makes a healthy indicator look broken and can hide structure exactly
 * when the user is inspecting it. Degradation therefore keeps indicator,
 * profile, session and structure layers enabled and only drops decoration,
 * anti-aliasing and the line-point budget.
 */
@Singleton
class AdaptiveQualityController @Inject constructor(
    private val profiler: PerformanceProfiler,
) {
    private val lock = Any()

    private var currentLevel: QualityLevel = QualityLevel.ULTRA
    private var qualityCeiling: QualityLevel = QualityLevel.ULTRA
    private var consecutiveBadFrames = 0
    private var consecutiveGoodFrames = 0

    fun evaluate(): QualitySettings = synchronized(lock) {
        when (profiler.getPerformanceTier()) {
            PerformanceTier.CRITICAL -> {
                consecutiveGoodFrames = 0
                consecutiveBadFrames = 0
                stepDown()
            }
            PerformanceTier.DEGRADED -> {
                consecutiveGoodFrames = 0
                consecutiveBadFrames++
                if (consecutiveBadFrames >= DOWNGRADE_THRESHOLD) {
                    consecutiveBadFrames = 0
                    stepDown()
                }
            }
            PerformanceTier.EXCELLENT -> {
                consecutiveBadFrames = 0
                consecutiveGoodFrames++
                if (consecutiveGoodFrames >= UPGRADE_THRESHOLD) {
                    consecutiveGoodFrames = 0
                    stepUp()
                }
            }
            PerformanceTier.GOOD, PerformanceTier.ACCEPTABLE -> {
                consecutiveBadFrames = 0
                consecutiveGoodFrames = 0
            }
        }
        getSettings(currentLevel)
    }

    private fun stepDown() {
        if (currentLevel.ordinal < QualityLevel.entries.lastIndex) {
            currentLevel = QualityLevel.entries[currentLevel.ordinal + 1]
        }
    }

    private fun stepUp() {
        if (currentLevel.ordinal > qualityCeiling.ordinal) {
            currentLevel = QualityLevel.entries[currentLevel.ordinal - 1]
        }
    }

    fun getSettings(level: QualityLevel = currentLevel): QualitySettings = when (level) {
        QualityLevel.ULTRA -> semanticSettings(
            gridLines = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = Int.MAX_VALUE,
        )
        QualityLevel.HIGH -> semanticSettings(
            gridLines = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = 500,
        )
        QualityLevel.MEDIUM -> semanticSettings(
            gridLines = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = 300,
        )
        QualityLevel.LOW -> semanticSettings(
            gridLines = true,
            antiAlias = false,
            maxVisibleIndicatorPoints = 150,
        )
        QualityLevel.MINIMAL -> semanticSettings(
            gridLines = false,
            antiAlias = false,
            maxVisibleIndicatorPoints = MINIMAL_INDICATOR_POINT_BUDGET,
        )
    }

    /** All user-selectable semantic study categories stay visible at every tier. */
    private fun semanticSettings(
        gridLines: Boolean,
        antiAlias: Boolean,
        maxVisibleIndicatorPoints: Int,
    ) = QualitySettings(
        gridLines = gridLines,
        volumeProfile = true,
        indicators = true,
        sessions = true,
        structureAnnotations = true,
        antiAlias = antiAlias,
        maxVisibleIndicatorPoints = maxVisibleIndicatorPoints,
    )

    fun getCurrentLevel(): QualityLevel = synchronized(lock) { currentLevel }

    fun forceLevel(level: QualityLevel) = synchronized(lock) {
        currentLevel = if (level.ordinal < qualityCeiling.ordinal) qualityCeiling else level
        consecutiveBadFrames = 0
        consecutiveGoodFrames = 0
    }

    fun setQualityCeiling(ceiling: QualityLevel) = synchronized(lock) {
        qualityCeiling = ceiling
        if (currentLevel.ordinal < ceiling.ordinal) currentLevel = ceiling
    }

    fun reset() = forceLevel(QualityLevel.ULTRA)

    companion object {
        const val UPGRADE_THRESHOLD = 60
        const val DOWNGRADE_THRESHOLD = 5
        const val MINIMAL_INDICATOR_POINT_BUDGET = 80
    }
}

enum class QualityLevel {
    ULTRA,
    HIGH,
    MEDIUM,
    LOW,
    MINIMAL,
}

enum class PerformanceMode(val displayName: String, val ceiling: QualityLevel) {
    SMOOTH("Smooth (full detail)", QualityLevel.ULTRA),
    BALANCED("Balanced", QualityLevel.HIGH),
    BATTERY_SAVER("Battery saver", QualityLevel.LOW),
}

data class QualitySettings(
    val gridLines: Boolean,
    val volumeProfile: Boolean,
    val indicators: Boolean,
    val sessions: Boolean,
    val structureAnnotations: Boolean,
    val antiAlias: Boolean,
    val maxVisibleIndicatorPoints: Int,
) {
    companion object {
        val FULL = QualitySettings(
            gridLines = true,
            volumeProfile = true,
            indicators = true,
            sessions = true,
            structureAnnotations = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = Int.MAX_VALUE,
        )
    }
}
