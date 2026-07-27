package com.foxtrader.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class FeatureSurfaceSmokeTest {

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
    fun scanner_shows_strategy_and_view_mode_controls() {
        composeRule.onNodeWithText("Scanner").performClick()
        composeRule.onNodeWithText("Strategy").assertIsDisplayed()
        composeRule.onNodeWithText("LIST").assertExists()
        composeRule.onNodeWithText("HEATMAP").assertExists()
    }

    @Test
    fun settings_shows_core_sections() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Account").assertExists()
        composeRule.onNodeWithText("Security").assertExists()
        composeRule.onNodeWithText("Risk Management").assertExists()
        composeRule.onNodeWithText("Alerts").assertExists()
        composeRule.onNodeWithText("AI Decision Engine").assertExists()
        composeRule.onNodeWithText("Data Provider").assertExists()
        composeRule.onNodeWithText("General").assertExists()
    }
}
