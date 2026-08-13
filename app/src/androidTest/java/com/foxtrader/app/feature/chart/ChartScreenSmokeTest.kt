package com.foxtrader.app.feature.chart

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
class ChartScreenSmokeTest {

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
    fun chartScreen_rendersPrimaryControls() {
        composeRule.onNodeWithText("Chart").performClick()
        composeRule.onNodeWithText("Fox").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Current symbol: EURUSD. Tap to change.")
            .assertExists()
        composeRule.onNodeWithContentDescription("Timeframe selector").assertExists()
        composeRule.onNodeWithContentDescription("Open position size calculator").assertExists()
        composeRule.onNodeWithText("Multi-chart").assertExists()
    }
}
