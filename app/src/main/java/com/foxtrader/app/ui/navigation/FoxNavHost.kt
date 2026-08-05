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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foxtrader.app.feature.auth.presentation.LoginScreen
import com.foxtrader.app.feature.alerts.presentation.AlertsScreen
import com.foxtrader.app.feature.backtest.presentation.BacktestLabScreen
import com.foxtrader.app.feature.chart.presentation.ChartScreen
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.journal.presentation.JournalScreen
import com.foxtrader.app.feature.litx.presentation.LitXScreen
import com.foxtrader.app.feature.tradepro.presentation.OpportunityBoardScreen
import com.foxtrader.app.feature.tradepro.presentation.AlertRulesScreen
import com.foxtrader.app.feature.tradepro.presentation.CorrelationScreen
import com.foxtrader.app.feature.tradepro.presentation.DailyPlanScreen
import com.foxtrader.app.feature.tradepro.presentation.RiskSimulatorScreen
import com.foxtrader.app.feature.tradepro.presentation.TraderProfileScreen
import com.foxtrader.app.feature.portfolio.presentation.PortfolioScreen
import com.foxtrader.app.feature.scanner.presentation.ScannerScreen
import com.foxtrader.app.feature.settings.presentation.SettingsScreen
import com.foxtrader.app.feature.strategies.presentation.StrategiesScreen
import com.foxtrader.app.feature.trademanagement.presentation.TradeManagementScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProBacktestReportScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProOptimizerScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProRiskDashboardScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProSimulatorScreen

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
    const val TRADEPRO_OPTIMIZER = "tradepro_optimizer"
    const val TRADEPRO_SIMULATOR = "tradepro_simulator"
    const val RISK_DASHBOARD = "risk_dashboard"
    const val TRADER_PROFILE = "trader_profile"
    const val DAILY_PLAN = "daily_plan"
    const val RISK_SIMULATOR = "risk_simulator"
    const val ALERT_RULES = "alert_rules"
    const val OPPORTUNITY_BOARD = "opportunity_board"
    const val CORRELATION = "correlation"

    // LIT X institutional analysis for a specific symbol/timeframe (reached
    // from the Chart top bar — additive, not a bottom tab).
    const val LITX = "litx/{symbol}/{timeframe}"

    /** Builds a concrete LIT X route; the symbol is URL-encoded so values with
     *  a '/' or space (e.g. "EUR/USD") don't break path-argument matching. */
    fun litx(symbol: String, timeframeLabel: String): String =
        "litx/${android.net.Uri.encode(symbol)}/${android.net.Uri.encode(timeframeLabel)}"
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
    // Immersive full-screen chart focus mode (R1): when the Chart requests it,
    // the bottom navigation bar is hidden so the chart can use the full height.
    // Guarded by the current route so navigating to any other destination —
    // even via an in-chart action while immersive — always restores the bar.
    var chartImmersive by rememberSaveable { mutableStateOf(false) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (!(chartImmersive && currentRoute == FoxRoutes.CHART)) {
                FoxBottomBar(navController = navController)
            }
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
                    onNavigateToLitX = { symbol, tf ->
                        navController.navigate(FoxRoutes.litx(symbol, tf.label))
                    },
                    immersive = chartImmersive,
                    onToggleImmersive = { chartImmersive = !chartImmersive },
                )
            }
            // LIT X Institutional Framework analysis for the charted symbol.
            composable(
                route = FoxRoutes.LITX,
                arguments = listOf(
                    navArgument("symbol") { type = NavType.StringType },
                    navArgument("timeframe") { type = NavType.StringType },
                ),
            ) { entry ->
                val symbol = entry.arguments?.getString("symbol") ?: "EURUSD"
                val tfLabel = entry.arguments?.getString("timeframe") ?: Timeframe.H1.label
                LitXScreen(
                    symbol = symbol,
                    timeframe = Timeframe.fromLabel(tfLabel),
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            // Alerts is reachable from the Chart top bar rather than a 7th
            // bottom tab: Material 3 caps the bar at 5 destinations (already
            // exceeded at 6), and the chart is where a trader reacts to a
            // signal.
            composable(FoxRoutes.ALERTS) {
                AlertsScreen(
                    onNavigateToRules = { navController.navigate(FoxRoutes.ALERT_RULES) },
                )
            }
            // Smart Alert rule builder \u2014 user-defined TRADEPRO alert conditions.
            composable(FoxRoutes.ALERT_RULES) {
                AlertRulesScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.SCANNER) {
                ScannerScreen(
                    onNavigateToOpportunityBoard = { navController.navigate(FoxRoutes.OPPORTUNITY_BOARD) },
                )
            }
            // TRADEPRO Opportunity Board — a watchlist-wide readiness ranking,
            // reached from the Scanner (both are market-wide scan surfaces).
            composable(FoxRoutes.OPPORTUNITY_BOARD) {
                OpportunityBoardScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.STRATEGIES) {
                StrategiesScreen()
            }
            composable(FoxRoutes.BACKTEST_LAB) {
                BacktestLabScreen(
                    onNavigateToTradeProReport = { navController.navigate(FoxRoutes.TRADEPRO_BACKTEST) },
                    onNavigateToRiskSimulator = { navController.navigate(FoxRoutes.RISK_SIMULATOR) },
                )
            }
            // Monte Carlo risk simulator \u2014 reached from the Backtesting Lab
            // (both quantify an edge; the simulator adds tail-risk / risk-of-ruin).
            composable(FoxRoutes.RISK_SIMULATOR) {
                RiskSimulatorScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.JOURNAL) {
                JournalScreen(
                    onNavigateToPortfolio = { navController.navigate(FoxRoutes.PORTFOLIO) },
                    onNavigateToProfile = { navController.navigate(FoxRoutes.TRADER_PROFILE) },
                    onNavigateToDailyPlan = { navController.navigate(FoxRoutes.DAILY_PLAN) },
                )
            }
            // Daily Plan (pre-market briefing + session review) lives with the
            // Journal — planning and reviewing are two ends of the same ritual.
            composable(FoxRoutes.DAILY_PLAN) {
                DailyPlanScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            // Trader Profile (journal analytics / coaching) lives under Journal —
            // it is derived entirely from journal entries.
            composable(FoxRoutes.TRADER_PROFILE) {
                TraderProfileScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            // Portfolio lives under Journal rather than as a 7th bottom tab:
            // Material 3 guidance caps the bar at 5 destinations (already
            // exceeded at 6), and open exposure is derived from open journal
            // trades, so it belongs in that hierarchy.
            composable(FoxRoutes.PORTFOLIO) {
                PortfolioScreen(
                    onNavigateToCorrelation = { navController.navigate(FoxRoutes.CORRELATION) },
                )
            }
            // Correlation matrix \u2014 data-driven concentration risk, under Portfolio.
            composable(FoxRoutes.CORRELATION) {
                CorrelationScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
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
                    onNavigateToSimulator = { navController.navigate(FoxRoutes.TRADEPRO_SIMULATOR) },
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
                    onNavigateToOptimizer = { navController.navigate(FoxRoutes.TRADEPRO_OPTIMIZER) },
                )
            }
            composable(FoxRoutes.TRADEPRO_OPTIMIZER) {
                TradeProOptimizerScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.TRADEPRO_SIMULATOR) {
                TradeProSimulatorScreen(
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
