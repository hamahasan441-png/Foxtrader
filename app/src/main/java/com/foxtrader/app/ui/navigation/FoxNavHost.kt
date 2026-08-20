package com.foxtrader.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Apps
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
import com.foxtrader.app.feature.ai.presentation.AiWorkspaceScreen
import com.foxtrader.app.feature.auth.presentation.LoginScreen
import com.foxtrader.app.feature.alerts.presentation.AlertsScreen
import com.foxtrader.app.feature.backtest.presentation.BacktestLabScreen
import com.foxtrader.app.feature.chart.presentation.ChartScreen
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.home.presentation.HomeScreen
import com.foxtrader.app.feature.litx.presentation.LitXScreen
import com.foxtrader.app.feature.more.presentation.MoreAction
import com.foxtrader.app.feature.more.presentation.MoreScreen
import com.foxtrader.app.feature.mt4.presentation.Mt4AccountScreen
import com.foxtrader.app.feature.mt4.presentation.Mt4LoginScreen
import com.foxtrader.app.feature.tradepro.presentation.OpportunityBoardScreen
import com.foxtrader.app.feature.tradepro.presentation.AlertRulesScreen
import com.foxtrader.app.feature.tradepro.presentation.CorrelationScreen
import com.foxtrader.app.feature.tradepro.presentation.DailyPlanScreen
import com.foxtrader.app.feature.tradepro.presentation.RiskSimulatorScreen
import com.foxtrader.app.feature.tradepro.presentation.TraderProfileScreen
import com.foxtrader.app.feature.papertrading.presentation.PaperTradingScreen
import com.foxtrader.app.feature.portfolio.presentation.PortfolioScreen
import com.foxtrader.app.feature.scanner.presentation.ScannerScreen
import com.foxtrader.app.feature.settings.presentation.SettingsScreen
import com.foxtrader.app.feature.strategies.presentation.StrategiesScreen
import com.foxtrader.app.feature.strategies.presentation.StrategyBuilderScreen
import com.foxtrader.app.feature.subscription.presentation.SubscriptionScreen
import com.foxtrader.app.feature.trademanagement.presentation.TradeManagementScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProBacktestReportScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProOptimizerScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProRiskDashboardScreen
import com.foxtrader.app.feature.tradepro.presentation.TradeProSimulatorScreen
import com.foxtrader.app.feature.watchlist.presentation.WatchlistScreen
import com.foxtrader.app.ui.theme.FoxTheme

/** Type-safe route constants for the app's destinations. */
object FoxRoutes {
    const val HOME = "home"
    const val CHART = "chart"
    const val MARKETS = "markets"
    const val SCANNER = "scanner"
    const val STRATEGIES = "strategies"
    const val STRATEGY_BUILDER = "strategy_builder"
    const val BACKTEST_LAB = "backtest_lab"
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
    const val PAPER_TRADING = "paper_trading"
    const val MORE = "more"
    const val AI = "ai_workspace"
    const val WATCHLIST = "watchlist"
    const val SUBSCRIPTION = "subscription"
    const val MT4_LOGIN = "mt4_login"
    const val MT4_ACCOUNT = "mt4_account"
    const val LITX = "litx/{symbol}/{timeframe}"

    fun litx(symbol: String, timeframeLabel: String): String =
        "litx/${android.net.Uri.encode(symbol)}/${android.net.Uri.encode(timeframeLabel)}"
}

data class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val bottomTabs = listOf(
    BottomNavTab(FoxRoutes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    BottomNavTab(FoxRoutes.CHART, "Chart", Icons.Outlined.ShowChart, Icons.Filled.ShowChart),
    BottomNavTab(FoxRoutes.MARKETS, "Markets", Icons.Outlined.GridView, Icons.Filled.GridView),
    BottomNavTab(FoxRoutes.BACKTEST_LAB, "Lab", Icons.Outlined.Insights, Icons.Filled.Insights),
    BottomNavTab(FoxRoutes.MORE, "More", Icons.Outlined.Apps, Icons.Filled.Apps),
)

@Composable
fun FoxNavHost(
    navController: NavHostController = rememberNavController(),
) {
    var chartImmersive by rememberSaveable { mutableStateOf(false) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val colors = FoxTheme.colors

    Scaffold(
        bottomBar = {
            if (!(chartImmersive && currentRoute == FoxRoutes.CHART)) {
                FoxBottomBar(navController = navController)
            }
        },
        containerColor = colors.background,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = FoxRoutes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(FoxRoutes.HOME) {
                HomeScreen(
                    onOpenChart = { navController.navigate(FoxRoutes.CHART) },
                    onOpenMarkets = { navController.navigate(FoxRoutes.MARKETS) },
                    onOpenAlerts = { navController.navigate(FoxRoutes.ALERTS) },
                    onOpenPortfolio = { navController.navigate(FoxRoutes.PORTFOLIO) },
                    onOpenLab = { navController.navigate(FoxRoutes.BACKTEST_LAB) },
                    onOpenAi = { navController.navigate(FoxRoutes.AI) },
                )
            }
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
            composable(FoxRoutes.ALERTS) {
                AlertsScreen(
                    onNavigateToRules = { navController.navigate(FoxRoutes.ALERT_RULES) },
                )
            }
            composable(FoxRoutes.ALERT_RULES) {
                AlertRulesScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.MARKETS) {
                ScannerScreen(
                    onNavigateToOpportunityBoard = { navController.navigate(FoxRoutes.OPPORTUNITY_BOARD) },
                )
            }
            composable(FoxRoutes.SCANNER) {
                ScannerScreen(
                    onNavigateToOpportunityBoard = { navController.navigate(FoxRoutes.OPPORTUNITY_BOARD) },
                )
            }
            composable(FoxRoutes.OPPORTUNITY_BOARD) {
                OpportunityBoardScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.STRATEGIES) {
                StrategiesScreen()
            }
            composable(FoxRoutes.STRATEGY_BUILDER) {
                StrategyBuilderScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenLab = { navController.navigate(FoxRoutes.BACKTEST_LAB) },
                )
            }
            composable(FoxRoutes.BACKTEST_LAB) {
                BacktestLabScreen(
                    onNavigateToTradeProReport = { navController.navigate(FoxRoutes.TRADEPRO_BACKTEST) },
                    onNavigateToRiskSimulator = { navController.navigate(FoxRoutes.RISK_SIMULATOR) },
                )
            }
            composable(FoxRoutes.RISK_SIMULATOR) {
                RiskSimulatorScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.DAILY_PLAN) {
                DailyPlanScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.TRADER_PROFILE) {
                TraderProfileScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.PORTFOLIO) {
                PortfolioScreen(
                    onNavigateToCorrelation = { navController.navigate(FoxRoutes.CORRELATION) },
                    onNavigateToPaperTrading = { navController.navigate(FoxRoutes.PAPER_TRADING) },
                )
            }
            composable(FoxRoutes.PAPER_TRADING) {
                PaperTradingScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.CORRELATION) {
                CorrelationScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.MORE) {
                MoreScreen(
                    onOpen = { action ->
                        val route = when (action) {
                            MoreAction.PORTFOLIO -> FoxRoutes.PORTFOLIO
                            MoreAction.ALERTS -> FoxRoutes.ALERTS
                            MoreAction.STRATEGIES -> FoxRoutes.STRATEGIES
                            MoreAction.STRATEGY_BUILDER -> FoxRoutes.STRATEGY_BUILDER
                            MoreAction.AI -> FoxRoutes.AI
                            MoreAction.WATCHLIST -> FoxRoutes.WATCHLIST
                            MoreAction.PAPER -> FoxRoutes.PAPER_TRADING
                            MoreAction.TRADE_MANAGEMENT -> FoxRoutes.TRADE_MANAGEMENT
                            MoreAction.DAILY_PLAN -> FoxRoutes.DAILY_PLAN
                            MoreAction.CORRELATION -> FoxRoutes.CORRELATION
                            MoreAction.SETTINGS -> FoxRoutes.SETTINGS
                            MoreAction.SUBSCRIPTION -> FoxRoutes.SUBSCRIPTION
                            MoreAction.MT4 -> FoxRoutes.MT4_LOGIN
                        }
                        navController.navigate(route)
                    },
                )
            }
            composable(FoxRoutes.MT4_LOGIN) {
                Mt4LoginScreen(
                    onConnected = {
                        navController.navigate(FoxRoutes.MT4_ACCOUNT) {
                            popUpTo(FoxRoutes.MT4_LOGIN) { inclusive = true }
                        }
                    },
                    onDismiss = { navController.popBackStack() },
                )
            }
            composable(FoxRoutes.MT4_ACCOUNT) {
                Mt4AccountScreen(
                    onDisconnected = { navController.popBackStack() },
                    onOpenLiveChart = {
                        // Return to the chart (MT4 is selected as the provider by
                        // the user in Settings) so live MT4 quotes feed the chart.
                        navController.navigate(FoxRoutes.CHART) {
                            popUpTo(FoxRoutes.HOME) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(FoxRoutes.AI) {
                AiWorkspaceScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenChart = { navController.navigate(FoxRoutes.CHART) },
                )
            }
            composable(FoxRoutes.WATCHLIST) {
                WatchlistScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.SUBSCRIPTION) {
                SubscriptionScreen(onNavigateBack = { navController.popBackStack() })
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
            composable(FoxRoutes.TRADEPRO_BACKTEST) {
                TradeProBacktestReportScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToOptimizer = { navController.navigate(FoxRoutes.TRADEPRO_OPTIMIZER) },
                )
            }
            composable(FoxRoutes.TRADEPRO_OPTIMIZER) {
                TradeProOptimizerScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.TRADEPRO_SIMULATOR) {
                TradeProSimulatorScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(FoxRoutes.RISK_DASHBOARD) {
                TradeProRiskDashboardScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun FoxBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val colors = FoxTheme.colors

    NavigationBar(
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        tonalElevation = FoxTheme.elevation.none,
    ) {
        bottomTabs.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(FoxRoutes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        if (selected) tab.selectedIcon else tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.accent,
                    selectedTextColor = colors.accent,
                    unselectedIconColor = colors.textMuted,
                    unselectedTextColor = colors.textMuted,
                    indicatorColor = colors.accentMuted,
                ),
            )
        }
    }
}
