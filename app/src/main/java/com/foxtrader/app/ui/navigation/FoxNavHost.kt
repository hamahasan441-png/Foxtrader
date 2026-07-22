package com.foxtrader.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.foxtrader.app.feature.chart.presentation.ChartScreen
import com.foxtrader.app.feature.scanner.presentation.ScannerScreen
import com.foxtrader.app.feature.settings.presentation.SettingsScreen

/** Type-safe route constants for the app's destinations. */
object FoxRoutes {
    const val CHART = "chart"
    const val SCANNER = "scanner"
    const val JOURNAL = "journal"
    const val SETTINGS = "settings"
}

/**
 * Root navigation graph. Single-activity architecture — every screen is a
 * Composable destination. Chart is the start destination (the heart of FoxTrader).
 */
@Composable
fun FoxNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = FoxRoutes.CHART,
    ) {
        composable(FoxRoutes.CHART) {
            ChartScreen()
        }
        composable(FoxRoutes.SCANNER) {
            ScannerScreen()
        }
        composable(FoxRoutes.SETTINGS) {
            SettingsScreen()
        }
        // Journal destination added when that feature lands.
    }
}
