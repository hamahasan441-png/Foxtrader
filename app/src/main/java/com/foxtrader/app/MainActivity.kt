package com.foxtrader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.ui.navigation.FoxNavHost
import com.foxtrader.app.ui.theme.FoxTraderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host. All screens are Composables rendered inside
 * [FoxNavHost]. [AndroidEntryPoint] enables Hilt injection into this Activity
 * and its ViewModels.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Drive the theme from the user's dark-mode preference so the
            // Settings toggle actually takes effect app-wide.
            val darkMode by appPreferences.darkMode.collectAsStateWithLifecycle()
            FoxTraderAppContent(darkMode = darkMode)
        }
    }
}

/**
 * Root composable. Named *Content to avoid an overload-resolution clash with
 * the [FoxTraderApp] Application class, which shares this package.
 */
@Composable
private fun FoxTraderAppContent(darkMode: Boolean) {
    FoxTraderTheme(darkTheme = darkMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            FoxNavHost()
        }
    }
}
