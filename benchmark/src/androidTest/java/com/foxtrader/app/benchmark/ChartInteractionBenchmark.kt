package com.foxtrader.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartInteractionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun chartScrollJank() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Fox")), 5_000)
        },
    ) {
        val centerY = device.displayHeight / 2
        val startX = (device.displayWidth * 0.80f).toInt()
        val endX = (device.displayWidth * 0.20f).toInt()
        repeat(4) {
            device.swipe(startX, centerY, endX, centerY, 18)
            device.swipe(endX, centerY, startX, centerY, 18)
        }
    }

    @Test
    fun chartPinchZoomFrameTiming() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Fox")), 5_000)
        },
    ) {
        val centerX = device.displayWidth / 2
        val centerY = device.displayHeight / 2
        repeat(4) {
            device.performTwoPointerGesture(
                intArrayOf(centerX - 80, centerY),
                intArrayOf(centerX + 80, centerY),
                intArrayOf(centerX - 220, centerY),
                intArrayOf(centerX + 220, centerY),
                12,
            )
            device.performTwoPointerGesture(
                intArrayOf(centerX - 220, centerY),
                intArrayOf(centerX + 220, centerY),
                intArrayOf(centerX - 80, centerY),
                intArrayOf(centerX + 80, centerY),
                12,
            )
        }
    }
}
