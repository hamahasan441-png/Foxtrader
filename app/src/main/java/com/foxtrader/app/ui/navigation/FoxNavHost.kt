package com.foxtrader.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.foxtrader.app.feature.chart.presentation.ChartScreen
import com.foxtrader.app.feature.journal.presentation.JournalScreen
import com.foxtrader.app.feature.scanner.presentation.ScannerScreen
import com.foxtrader.app.feature.settings.presentation.SettingsScreen
import com.foxtrader.app.feature.strategies.presentation.StrategiesScreen

/** Type-safe route constants for the app's destinations. */
object FoxRoutes {
    const val CHART = "chart"
    const val SCANNER = "scanner"
    const val STRATEGIES = "strategies"
    const val JOURNAL = "journal"
    const val SETTINGS = "settings"
}

/** Bottom navigation tab definition. */
data class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomTabs = listOf(
    BottomNavTab(FoxRoutes.CHART, "Chart", Icons.Default.BarChart),
    BottomNavTab(FoxRoutes.SCANNER, "Scanner", Icons.Default.Search),
    BottomNavTab(FoxRoutes.STRATEGIES, "Strategies", Icons.Default.TrendingUp),
    BottomNavTab(FoxRoutes.JOURNAL, "Journal", Icons.Default.Book),
    BottomNavTab(FoxRoutes.SETTINGS, "Settings", Icons.Default.Settings),
)

/**
 * Root navigation graph with bottom navigation bar.
 * Single-activity architecture — every screen is a Composable destination.
 * Chart is the start destination (the heart of FoxTrader).
 */
@Composable
fun FoxNavHost(
    navController: NavHostController = rememberNavController(),
) {
    Scaffold(
        bottomBar = {
            FoxBottomBar(navController = navController)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = FoxRoutes.CHART,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(FoxRoutes.CHART) {
                ChartScreen()
            }
            composable(FoxRoutes.SCANNER) {
                ScannerScreen()
            }
            composable(FoxRoutes.STRATEGIES) {
                StrategiesScreen()
            }
            composable(FoxRoutes.JOURNAL) {
                JournalScreen()
            }
            composable(FoxRoutes.SETTINGS) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun FoxBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        bottomTabs.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            // Pop up to the start destination to avoid building a large back stack
                            popUpTo(FoxRoutes.CHART) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}
