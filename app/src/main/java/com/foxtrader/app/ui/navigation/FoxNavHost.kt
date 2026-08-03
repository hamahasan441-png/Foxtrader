package com.foxtrader.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
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
import com.foxtrader.app.feature.auth.presentation.LoginScreen
import com.foxtrader.app.feature.alerts.presentation.AlertsScreen
import com.foxtrader.app.feature.backtest.presentation.BacktestLabScreen
import com.foxtrader.app.feature.chart.presentation.ChartScreen
import com.foxtrader.app.feature.journal.presentation.JournalScreen
import com.foxtrader.app.feature.portfolio.presentation.PortfolioScreen
import com.foxtrader.app.feature.scanner.presentation.ScannerScreen
import com.foxtrader.app.feature.settings.presentation.SettingsScreen
import com.foxtrader.app.feature.strategies.presentation.StrategiesScreen
import com.foxtrader.app.feature.trademanagement.presentation.TradeManagementScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProBacktestReportScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProRiskDashboardScreen

/** Type-safe route constants for the app's destinations. */
object FoxRoutes {
    const val CHART = "chart"
    const val SCANNER = "scanner"
    const val STRATEGIES = "strategies"
    const val BACKTEST_LAB = "backtest_lab"
    const val JOURNAL = "journal"
    const val PORTFOLIO = "portfolio"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val TRADE_MANAGEMENT = "trade_management"
    const val TRADEPRO_BACKTEST = "tradepro_backtest"
    const val RISK_DASHBOARD = "risk_dashboard"
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
    BottomNavTab(FoxRoutes.BACKTEST_LAB, "Lab", Icons.Default.ShowChart),
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
                ChartScreen(
                    onNavigateToAlerts = { navController.navigate(FoxRoutes.ALERTS) },
                    onNavigateToTradeManagement = { navController.navigate(FoxRoutes.TRADE_MANAGEMENT) },
                )
            }
            // Alerts is reachable from the Chart top bar rather than a 7th
            // bottom tab: Material 3 caps the bar at 5 destinations (already
            // exceeded at 6), and the chart is where a trader reacts to a
            // signal.
            composable(FoxRoutes.ALERTS) {
                AlertsScreen()
            }
            composable(FoxRoutes.SCANNER) {
                ScannerScreen()
            }
            composable(FoxRoutes.STRATEGIES) {
                StrategiesScreen()
            }
            composable(FoxRoutes.BACKTEST_LAB) {
                BacktestLabScreen(
                    onNavigateToTradeProReport = { navController.navigate(FoxRoutes.TRADEPRO_BACKTEST) },
                )
            }
            composable(FoxRoutes.JOURNAL) {
                JournalScreen(
                    onNavigateToPortfolio = { navController.navigate(FoxRoutes.PORTFOLIO) },
                )
            }
            // Portfolio lives under Journal rather than as a 7th bottom tab:
            // Material 3 guidance caps the bar at 5 destinations (already
            // exceeded at 6), and open exposure is derived from open journal
            // trades, so it belongs in that hierarchy.
            composable(FoxRoutes.PORTFOLIO) {
                PortfolioScreen()
            }
            composable(FoxRoutes.SETTINGS) {
                SettingsScreen(
                    onNavigateToLogin = { navController.navigate(FoxRoutes.LOGIN) },
                )
            }
            composable(FoxRoutes.LOGIN) {
                LoginScreen(
                    onAuthenticated = { navController.popBackStack() },
                    onDismiss = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.TRADE_MANAGEMENT) {
                TradeManagementScreen(
                    onNavigateToRiskDashboard = { navController.navigate(FoxRoutes.RISK_DASHBOARD) },
                )
            }
            // TRADEPRO Backtest Report is reached from the Backtesting Lab (it
            // shares the Lab's symbol/timeframe mental model) rather than a 7th
            // bottom tab: Material 3 caps the bar at 5 destinations (already
            // exceeded at 6).
            composable(FoxRoutes.TRADEPRO_BACKTEST) {
                TradeProBacktestReportScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.RISK_DASHBOARD) {
                TradeProRiskDashboardScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
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
