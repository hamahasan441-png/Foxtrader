// ============================================================================
// INSTITUTIONAL AI TRADING PLATFORM - Top-level barrel export
// Aggregates Part 1 (Smart Money analysis + chart engine + data) and
// Part 2 (execution, risk, AI engines, backtest, journal, news, etc.)
// ============================================================================

// --- Core ---
export * from './core';

// --- Part 1: Analysis modules, chart engine, data providers ---
export * from './modules';
export { ChartEngine } from './engine/chart-engine';
export { VisualizationEngine } from './engine/visualization';

// --- Indicators ---
export * from './indicators/technical';

// --- Part 2: Execution & Risk ---
export { ExecutionEngine } from './execution/execution-engine';
export * from './execution/types';
export { RiskEngine } from './risk/risk-engine';

// --- Part 2: AI Engines ---
export { ProbabilityEngine } from './ai/probability-engine';
export { ConfluenceEngine } from './ai/confluence-engine';
export { MarketScanner } from './ai/market-scanner';
export { TradePlanner } from './ai/trade-planner';
export { MentorAssistant } from './ai/mentor-assistant';

// --- Part 2: Journal, Replay, Backtest ---
export { TradeJournal } from './journal/trade-journal';
export { ReplayEngine, ReplayCommentator } from './replay/replay-engine';
export { Backtester } from './backtest/backtester';
export { MonteCarloSimulator, WalkForwardAnalyzer } from './backtest/monte-carlo';
export { Optimizer } from './backtest/optimizer';

// --- Part 2: News, Analytics ---
export { NewsModule } from './news/news-module';
export { HeatmapEngine, buildSectorHeatmap, STOCK_SECTORS } from './analytics/heatmap-strength';

// --- Part 2: Voice, Sync, Customization, Security ---
export { VoiceAssistant } from './voice/voice-assistant';
export { CloudSync } from './sync/cloud-sync';
export { CustomizationManager } from './customization/customization';
export { SecurityManager, AES256Encryption } from './security/security';

// --- Main platform orchestrator ---
export { InstitutionalTradingPlatform } from './main';
export { default } from './main';

// --- Part 2 aggregator ---
export { TradingPlatformPro } from './platform-pro';
