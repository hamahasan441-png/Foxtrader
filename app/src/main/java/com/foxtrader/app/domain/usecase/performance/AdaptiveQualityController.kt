package com.foxtrader.app.domain.usecase.performance

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive Quality Controller — dynamically adjusts chart detail level
 * based on real-time performance metrics from the PerformanceProfiler.
 *
 * User-selected indicator lines are never removed completely. Under emergency
 * load we reduce their point budget instead; a selected study disappearing from
 * the chart is semantically worse than drawing it at reduced density.
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
        QualityLevel.ULTRA -> QualitySettings(
            gridLines = true,
            volumeProfile = true,
            indicators = true,
            sessions = true,
            structureAnnotations = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = Int.MAX_VALUE,
        )
        QualityLevel.HIGH -> QualitySettings(
            gridLines = true,
            volumeProfile = true,
            indicators = true,
            sessions = true,
            structureAnnotations = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = 500,
        )
        QualityLevel.MEDIUM -> QualitySettings(
            gridLines = true,
            volumeProfile = false,
            indicators = true,
            sessions = true,
            structureAnnotations = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = 300,
        )
        QualityLevel.LOW -> QualitySettings(
            gridLines = true,
            volumeProfile = false,
            indicators = true,
            sessions = false,
            structureAnnotations = false,
            antiAlias = false,
            maxVisibleIndicatorPoints = 150,
        )
        QualityLevel.MINIMAL -> QualitySettings(
            gridLines = false,
            volumeProfile = false,
            indicators = true,
            sessions = false,
            structureAnnotations = false,
            antiAlias = false,
            maxVisibleIndicatorPoints = MINIMAL_INDICATOR_POINT_BUDGET,
        )
    }

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
