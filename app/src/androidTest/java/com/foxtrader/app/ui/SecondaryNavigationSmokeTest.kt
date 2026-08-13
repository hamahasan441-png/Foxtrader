package com.foxtrader.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxtrader.app.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondaryNavigationSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${composeRule.activity.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
            ).close()
        }
    }

    @Test
    fun navigation_opensStrategiesAndLab() {
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Strategies").performClick()
        composeRule.onNodeWithContentDescription("Rescan").assertExists()

        composeRule.onNodeWithText("Lab").performClick()
        composeRule.onNodeWithText("Backtesting Lab").assertIsDisplayed()
        composeRule.onNodeWithText("Run Non-Repainting Backtest").assertExists()
    }

    @Test
    fun chartAlertsButton_opensAlertsInbox() {
        composeRule.onNodeWithContentDescription("Alerts inbox").performClick()
        composeRule.onNodeWithText("Alerts").assertIsDisplayed()
    }
}
