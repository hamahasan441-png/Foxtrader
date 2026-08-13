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
class ScannerSettingsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scannerModesAndSettingsJourney() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("FoxTrader")), 5_000)
        },
    ) {
        device.findObject(By.text("Markets"))?.click()
        device.wait(Until.hasObject(By.text("Strategy")), 5_000)
        device.findObject(By.text("HEATMAP"))?.click()
        device.findObject(By.text("LIST"))?.click()

        device.findObject(By.text("More"))?.click()
        device.findObject(By.text("Settings"))?.click()
        device.wait(Until.hasObject(By.text("Settings")), 5_000)
        device.findObject(By.text("Save Settings"))?.click()
    }
}
