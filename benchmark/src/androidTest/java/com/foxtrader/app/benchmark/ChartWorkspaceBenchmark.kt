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
class ChartWorkspaceBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun multiChartWorkspaceToggles() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("FoxTrader")), 5_000)
            device.findObject(By.text("Chart"))?.click()
            device.wait(Until.hasObject(By.text("Fox")), 5_000)
        },
    ) {
        device.findObject(By.text("ADD"))?.click()
        device.findObject(By.text("1×2"))?.click()
        device.findObject(By.text("LINKED"))?.click()
        device.findObject(By.text("SYM-LINK"))?.click()
        device.findObject(By.text("TF-LINK"))?.click()
        device.findObject(By.text("X-SYNC"))?.click()
        device.findObject(By.text("UNLINKED"))?.click()
        device.findObject(By.text("Chart"))?.click()
        device.wait(Until.hasObject(By.text("Fox")), 5_000)
    }
}
