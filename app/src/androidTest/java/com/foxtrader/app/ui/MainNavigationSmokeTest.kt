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
class MainNavigationSmokeTest {

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
    fun bottomNavigationTabs_areVisible() {
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Chart").assertExists()
        composeRule.onNodeWithText("Markets").assertExists()
        composeRule.onNodeWithText("Lab").assertExists()
        composeRule.onNodeWithText("More").assertExists()
    }

    @Test
    fun navigation_opensMarketsPortfolioAndSettingsScreens() {
        composeRule.onNodeWithText("Markets").performClick()
        composeRule.onNodeWithText("Strategy").assertIsDisplayed()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Portfolio").assertIsDisplayed()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Risk Management").assertExists()
    }
}
