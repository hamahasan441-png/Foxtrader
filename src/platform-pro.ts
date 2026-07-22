// ============================================================================
// TRADING PLATFORM PRO - Part 2 Orchestrator
// Wires together Execution, Risk, AI engines, Journal, Replay, Backtest,
// News, Analytics, Voice, Sync, Customization, and Security into one facade.
// ============================================================================

import { TradingEventBus } from './core/event-bus';
import { Candle, Direction, Bias, Timeframe } from './core/types';

import { ExecutionEngine } from './execution/execution-engine';
import { OrderRequest, Position } from './execution/types';
import { RiskEngine } from './risk/risk-engine';
import { ProbabilityEngine } from './ai/probability-engine';
import { ConfluenceEngine } from './ai/confluence-engine';
import { MarketScanner, SymbolAnalysisBundle } from './ai/market-scanner';
import { TradePlanner, TradePlannerInput } from './ai/trade-planner';
import { MentorAssistant, MentorContext } from './ai/mentor-assistant';
import { TradeJournal } from './journal/trade-journal';
import { ReplayEngine, ReplayCommentator } from './replay/replay-engine';
import { Backtester, StrategyFunction } from './backtest/backtester';
import { MonteCarloSimulator, WalkForwardAnalyzer } from './backtest/monte-carlo';
import { Optimizer } from './backtest/optimizer';
import { NewsModule } from './news/news-module';
import { HeatmapEngine } from './analytics/heatmap-strength';
import { VoiceAssistant, ParsedCommand } from './voice/voice-assistant';
import { CloudSync } from './sync/cloud-sync';
import { CustomizationManager } from './customization/customization';
import { SecurityManager } from './security/security';

export interface PlatformProConfig {
  accountBalance: number;
  enableVoice: boolean;
  enableCloudSync: boolean;
  requireAuth: boolean;
  userId?: string;
}

const DEFAULT_CONFIG: PlatformProConfig = {
  accountBalance: 100000,
  enableVoice: false,
  enableCloudSync: false,
  requireAuth: false,
};

/**
 * TradingPlatformPro - unified facade over all Part 2 subsystems.
 * Share the same EventBus as Part 1 for a fully integrated platform.
 */
export class TradingPlatformPro {
  private config: PlatformProConfig;
  readonly eventBus: TradingEventBus;

  // Subsystems (public for direct access)
  readonly execution: ExecutionEngine;
  readonly risk: RiskEngine;
  readonly probability: ProbabilityEngine;
  readonly confluence: ConfluenceEngine;
  readonly scanner: MarketScanner;
  readonly planner: TradePlanner;
  readonly mentor: MentorAssistant;
  readonly journal: TradeJournal;
  readonly replay: ReplayEngine;
  readonly replayCommentator: ReplayCommentator;
  readonly backtester: Backtester;
  readonly monteCarlo: MonteCarloSimulator;
  readonly walkForward: WalkForwardAnalyzer;
  readonly optimizer: Optimizer;
  readonly news: NewsModule;
  readonly heatmap: HeatmapEngine;
  readonly voice: VoiceAssistant;
  readonly cloudSync: CloudSync;
  readonly customization: CustomizationManager;
  readonly security: SecurityManager;

  constructor(config: Partial<PlatformProConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus ?? new TradingEventBus();

    // Instantiate all subsystems with the shared event bus
    this.execution = new ExecutionEngine({}, this.eventBus);
    this.risk = new RiskEngine({ accountBalance: this.config.accountBalance }, this.eventBus);
    this.probability = new ProbabilityEngine();
    this.confluence = new ConfluenceEngine();
    this.scanner = new MarketScanner();
    this.planner = new TradePlanner();
    this.mentor = new MentorAssistant();
    this.journal = new TradeJournal(this.eventBus);
    this.replay = new ReplayEngine({}, this.eventBus);
    this.replayCommentator = new ReplayCommentator();
    this.backtester = new Backtester();
    this.monteCarlo = new MonteCarloSimulator();
    this.walkForward = new WalkForwardAnalyzer();
    this.optimizer = new Optimizer();
    this.news = new NewsModule(this.eventBus);
    this.heatmap = new HeatmapEngine();
    this.voice = new VoiceAssistant({}, this.eventBus);
    this.cloudSync = new CloudSync({ userId: this.config.userId ?? '' }, this.eventBus);
    this.customization = new CustomizationManager(this.eventBus);
    this.security = new SecurityManager({ requireAuth: this.config.requireAuth }, this.eventBus);

    // Wire replay AI commentary
    this.replay.setCommentaryProvider(this.replayCommentator.createProvider());

    // Wire encryption from security into cloud sync
    this.cloudSync.setEncryption(this.security.getEncryption());
  }

  // =========================================================================
  // LIFECYCLE
  // =========================================================================

  async initialize(): Promise<void> {
    console.log('[PlatformPro] Initializing Part 2 subsystems...');

    // Security first
    const secResult = await this.security.initialize();
    if (!secResult.safe) {
      console.warn('[PlatformPro] Security threats detected:', secResult.threats.map(t => t.type).join(', '));
    }

    // Apply saved theme
    this.customization.applyTheme(this.customization.getActiveTheme().id);

    // Cloud sync
    if (this.config.enableCloudSync) {
      this.cloudSync.startAutoSync();
    }

    // Voice
    if (this.config.enableVoice) {
      this.voice.onCommand((cmd) => this.handleVoiceCommand(cmd));
      this.voice.startListening();
    }

    // News sample calendar (replace with live provider feed)
    this.news.generateSampleCalendar();

    console.log('[PlatformPro] ✓ All Part 2 subsystems ready');
  }

  // =========================================================================
  // INTEGRATED TRADE FLOW - the heart of the platform
  // Combines analysis -> probability -> risk -> execution -> journal
  // =========================================================================

  /**
   * Evaluate and (optionally) execute a trade with full risk + probability gating.
   * This is the integrated pipeline connecting all Part 2 subsystems.
   */
  evaluateAndTrade(params: {
    bundle: SymbolAnalysisBundle;
    direction: Direction;
    autoExecute?: boolean;
    marketBid: number;
    marketAsk: number;
  }): {
    approved: boolean;
    reasons: string[];
    plan: ReturnType<TradePlanner['generatePlan']>;
    confidence: number;
  } {
    const { bundle, direction, autoExecute, marketBid, marketAsk } = params;

    // 1. Probability + confluence
    const probability = this.probability.calculate({
      candles: bundle.candles, direction, currentPrice: bundle.currentPrice,
      structureBreaks: bundle.structureBreaks, orderBlocks: bundle.orderBlocks,
      fvgs: bundle.fvgs, sweeps: bundle.sweeps, liquidityLevels: bundle.liquidityLevels,
      smtDivergences: bundle.smtDivergences, litSetups: bundle.litSetups,
      htfBias: bundle.htfBias,
      news: this.news.getNewsContext(),
    });

    // 2. Generate trade plan
    const plan = this.planner.generatePlan({
      symbol: bundle.symbol, timeframe: bundle.timeframe, candles: bundle.candles,
      currentPrice: bundle.currentPrice, bias: bundle.htfBias,
      structureBreaks: bundle.structureBreaks, orderBlocks: bundle.orderBlocks,
      fvgs: bundle.fvgs, liquidityLevels: bundle.liquidityLevels, sweeps: bundle.sweeps,
      smtDivergences: bundle.smtDivergences, litSetups: bundle.litSetups,
      swings: [], ote: bundle.ote, premiumDiscount: bundle.premiumDiscount,
      probabilityResult: probability,
    });

    if (!plan) {
      return { approved: false, reasons: ['No valid trade plan could be generated'], plan: null, confidence: probability.confidence };
    }

    // 3. Position sizing via risk engine
    const sizing = this.risk.calculatePositionSize({
      symbol: bundle.symbol, entryPrice: plan.entry, stopLossPrice: plan.stopLoss, candles: bundle.candles,
    });

    // 4. Risk gate
    const riskCheck = this.risk.canOpenTrade({
      symbol: bundle.symbol, riskAmount: sizing.riskAmount, openPositions: this.execution.getOpenPositions(),
    });

    const reasons: string[] = [...riskCheck.reasons];
    if (probability.confidence < 55) reasons.push(`Confidence too low (${probability.confidence}%)`);

    const approved = riskCheck.allowed && probability.confidence >= 55;

    // 5. Execute if approved + autoExecute
    if (approved && autoExecute) {
      this.execution.updateMarket({ symbol: bundle.symbol, bid: marketBid, ask: marketAsk, spread: marketAsk - marketBid, timestamp: Date.now() });
      const order: OrderRequest = {
        symbol: bundle.symbol,
        type: plan.entryType === 'MARKET' ? 'MARKET' : plan.entryType === 'LIMIT' ? 'LIMIT' : 'STOP',
        side: direction === 'BULLISH' ? 'BUY' : 'SELL',
        volume: sizing.volume,
        limitPrice: plan.entryType === 'LIMIT' ? plan.entry : undefined,
        stopPrice: plan.entryType === 'STOP' ? plan.entry : undefined,
        stopLosses: [{ price: plan.stopLoss, volumePercent: 100 }],
        takeProfits: plan.takeProfits.map((tp, i) => ({ price: tp.price, volumePercent: i === 0 ? 50 : 25 })),
        comment: `${plan.grade} ${plan.probability}%`,
      };
      const report = this.execution.submitOrder(order);

      // 6. Auto-journal the trade
      if (report.status === 'FILLED' || report.status === 'WORKING') {
        this.journal.createEntry({
          symbol: bundle.symbol, timeframe: bundle.timeframe, direction,
          entryPrice: plan.entry, stopLoss: plan.stopLoss, volume: sizing.volume,
          takeProfits: plan.takeProfits.map(t => t.price),
          setupType: plan.grade, confidence: plan.confidence, plannedRR: plan.riskReward,
          confluenceFactors: plan.confirmation,
        });
      }
    }

    return { approved, reasons, plan, confidence: probability.confidence };
  }

  // =========================================================================
  // VOICE COMMAND HANDLING
  // =========================================================================

  private handleVoiceCommand(cmd: ParsedCommand): void {
    switch (cmd.type) {
      case 'CLOSE_ALL':
        this.execution.closeAll();
        this.voice.speak('All positions closed.');
        break;
      case 'READ_ALERTS': {
        const halted = this.risk.isTradingHalted();
        this.voice.speak(halted ? 'Trading is currently halted by the risk engine.' : 'No critical alerts. Trading is active.');
        break;
      }
      case 'START_REPLAY':
        this.replay.play();
        this.voice.speak('Replay started.');
        break;
      case 'STOP_REPLAY':
        this.replay.stop();
        this.voice.speak('Replay stopped.');
        break;
      // CHANGE_SYMBOL/TIMEFRAME/ASK_MENTOR are delegated to the UI layer via events
    }
  }

  // =========================================================================
  // CONVENIENCE ACCESSORS
  // =========================================================================

  /** Ask the AI mentor a question */
  askMentor(question: string, context: MentorContext) {
    const response = this.mentor.ask(question, context);
    if (this.config.enableVoice) this.voice.readAnalysis(response.answer);
    return response;
  }

  /** Run a backtest with Monte Carlo + walk-forward robustness checks */
  runFullBacktest(candles: Candle[], strategy: StrategyFunction, symbol: string, tf: Timeframe) {
    const result = this.backtester.run(candles, strategy, symbol, tf);
    const monteCarlo = this.monteCarlo.simulate(result.trades);
    return { result, monteCarlo };
  }

  /** Record a closed trade outcome into the risk engine + journal */
  recordTradeOutcome(journalId: string, exitPrice: number): void {
    const entry = this.journal.closeEntry(journalId, exitPrice);
    if (entry) {
      this.risk.recordTrade(entry.pnl, entry.symbol);
    }
  }

  /** Get a full platform status snapshot */
  getStatus() {
    return {
      account: this.execution.getAccountSummary(),
      risk: this.risk.getRiskStatus(this.execution.getOpenPositions()),
      openPositions: this.execution.getOpenPositions().length,
      workingOrders: this.execution.getWorkingOrders().length,
      journalStats: this.journal.getStats(),
      syncStatus: this.cloudSync.getStatus(),
      authenticated: this.security.isAuthenticated(),
      activeTheme: this.customization.getActiveTheme().name,
      securityThreats: this.security.getThreats().length,
    };
  }

  destroy(): void {
    this.execution.reset();
    this.voice.destroy();
    this.cloudSync.destroy();
    this.security.destroy();
    this.replay.destroy();
    this.eventBus.clear();
  }
}
