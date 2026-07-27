package com.foxtrader.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        maxIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Fox")), 5_000)

        device.findObject(By.text("Scanner"))?.click()
        device.wait(Until.hasObject(By.text("Scanner")), 5_000)

        device.findObject(By.text("Settings"))?.click()
        device.wait(Until.hasObject(By.text("Settings")), 5_000)

        device.findObject(By.text("Journal"))?.click()
        device.wait(Until.hasObject(By.text("Journal")), 5_000)

        device.findObject(By.desc("Open portfolio exposure"))?.click()
        device.wait(Until.hasObject(By.text("Portfolio")), 5_000)
        device.pressBack()

        device.findObject(By.text("Chart"))?.click()
        device.wait(Until.hasObject(By.text("Fox")), 5_000)

        device.findObject(By.descStartsWith("Alerts inbox"))?.click()
        device.wait(Until.hasObject(By.text("Alerts")), 5_000)
    }
}
