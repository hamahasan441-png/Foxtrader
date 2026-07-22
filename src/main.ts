// ============================================================================
// INSTITUTIONAL TRADING INTELLIGENCE PLATFORM - Main Entry Point
// Built on TradingView Lightweight Charts | Multi-Provider Data Engine
// Non-repainting enforcement | No look-ahead bias | Real-time WebSocket
// ============================================================================

import { TradingEventBus } from './core/event-bus';
import { NonRepaintingGuard } from './core/non-repainting';
import {
  Candle,
  Timeframe,
  TemplateType,
  Bias,
  PlatformEvent,
  AIAnalysis,
  ScannerAlert,
} from './core/types';

import { MarketStructureAnalyzer } from './modules/market-structure';
import { LiquidityAnalyzer } from './modules/liquidity';
import { OrderBlockAnalyzer } from './modules/order-blocks';
import { FairValueGapAnalyzer } from './modules/fair-value-gaps';
import { ICTConceptsAnalyzer } from './modules/ict-concepts';
import { LITTradingAnalyzer } from './modules/lit-trading';
import { SMTAnalyzer } from './modules/smt';
import { SessionAnalyzer } from './modules/sessions';
import { TemplateManager } from './modules/templates';
import { DataEngine } from './modules/data-provider/data-engine';
import { TradingScanner } from './modules/scanner';
import { AITradingAssistant } from './modules/ai-assistant';
import { ChartEngine } from './engine/chart-engine';
import { VisualizationEngine } from './engine/visualization';

import type { DataEngineConfig } from './modules/data-provider/data-engine';
import type { ProviderName } from './modules/data-provider/provider-interface';


export interface PlatformConfig {
  symbol: string;
  timeframe: Timeframe;
  template: TemplateType;
  autoAnalyze: boolean;
  autoScan: boolean;
  chartContainer?: HTMLElement;
  /** Data engine configuration (providers, failover, etc.) */
  dataEngine?: Partial<DataEngineConfig>;
  /** Strict non-repainting mode (throws on look-ahead) */
  strictNonRepainting?: boolean;
}

const DEFAULT_PLATFORM_CONFIG: PlatformConfig = {
  symbol: 'EURUSD',
  timeframe: 'M15',
  template: 'INTRADAY_15M',
  autoAnalyze: true,
  autoScan: true,
  strictNonRepainting: false,
};

export class InstitutionalTradingPlatform {
  // Core
  private eventBus: TradingEventBus;
  private config: PlatformConfig;
  private nonRepaintGuard: NonRepaintingGuard;

  // Modules
  private marketStructure: MarketStructureAnalyzer;
  private liquidity: LiquidityAnalyzer;
  private orderBlocks: OrderBlockAnalyzer;
  private fvg: FairValueGapAnalyzer;
  private ict: ICTConceptsAnalyzer;
  private lit: LITTradingAnalyzer;
  private smt: SMTAnalyzer;
  private sessions: SessionAnalyzer;
  private templates: TemplateManager;
  private dataEngine: DataEngine;
  private scanner: TradingScanner;
  private aiAssistant: AITradingAssistant;

  // Engine
  private chartEngine: ChartEngine;
  private visualization: VisualizationEngine;

  // State
  private candles: Candle[] = [];
  private isInitialized: boolean = false;
  private analysisInterval: number = 0;
  private realtimeSubId: string = '';

  constructor(config: Partial<PlatformConfig> = {}) {
    this.config = { ...DEFAULT_PLATFORM_CONFIG, ...config };
    this.eventBus = new TradingEventBus();

    // Non-repainting enforcement
    this.nonRepaintGuard = new NonRepaintingGuard({
      strictMode: this.config.strictNonRepainting,
      rejectFutureData: true,
      logRejections: true,
      auditTrail: true,
    });

    // Initialize all modules with event bus
    this.marketStructure = new MarketStructureAnalyzer({}, this.eventBus);
    this.liquidity = new LiquidityAnalyzer({}, this.eventBus);
    this.orderBlocks = new OrderBlockAnalyzer({}, this.eventBus);
    this.fvg = new FairValueGapAnalyzer({}, this.eventBus);
    this.ict = new ICTConceptsAnalyzer({}, this.eventBus);
    this.lit = new LITTradingAnalyzer({}, this.eventBus);
    this.smt = new SMTAnalyzer({}, this.eventBus);
    this.sessions = new SessionAnalyzer({}, this.eventBus);
    this.templates = new TemplateManager();
    this.scanner = new TradingScanner({}, this.eventBus);
    this.aiAssistant = new AITradingAssistant({}, this.eventBus);

    // Data engine with multi-provider support
    this.dataEngine = new DataEngine(this.config.dataEngine || {}, this.eventBus);

    // Chart engine (TradingView Lightweight Charts)
    this.chartEngine = new ChartEngine({}, this.eventBus);
    this.visualization = new VisualizationEngine();
  }


  /**
   * Initialize the platform
   */
  async initialize(container?: HTMLElement): Promise<void> {
    console.log('[Platform] Initializing Institutional Trading Platform v2.0...');

    // 1. Initialize data engine (connects all providers)
    await this.dataEngine.initialize();
    console.log('[Platform] Data engine ready:', this.dataEngine.getProviderStatus().map(p => `${p.name}:${p.status}`).join(', '));

    // 2. Load template
    const template = this.templates.loadTemplate(this.config.template);
    this.visualization.updateColors(template.colors);
    console.log(`[Platform] Template: ${template.name}`);

    // 3. Initialize chart engine (TradingView Lightweight Charts)
    if (container || this.config.chartContainer) {
      this.chartEngine.initialize(container || this.config.chartContainer!);
      console.log('[Platform] Chart engine initialized (TradingView Lightweight Charts)');
    }

    // 4. Fetch initial historical data via DataEngine (failover-enabled)
    try {
      const startDate = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
      let rawCandles = await this.dataEngine.getCandles(this.config.symbol, this.config.timeframe, startDate);

      // 5. VALIDATE data through non-repainting guard
      this.candles = this.nonRepaintGuard.validateCandleData(rawCandles);
      console.log(`[Platform] Loaded ${this.candles.length} validated candles (${rawCandles.length - this.candles.length} rejected)`);

      // 6. Load into chart
      this.chartEngine.setData(this.candles);
    } catch (err) {
      console.warn('[Platform] Initial data fetch failed:', err);
      this.candles = [];
    }

    // 7. Run initial analysis
    if (this.candles.length > 0) {
      this.runFullAnalysis();
    }

    // 8. Subscribe to real-time WebSocket feed
    this.startRealtimeFeed();

    // 9. Setup event listeners
    this.setupEventListeners();

    // 10. Periodic analysis
    if (this.config.autoAnalyze) {
      this.analysisInterval = window.setInterval(() => this.runFullAnalysis(), 60000);
    }

    this.isInitialized = true;
    console.log('[Platform] ✓ Initialization complete');
    console.log(`[Platform] Non-repainting: ENABLED | Strict: ${this.config.strictNonRepainting}`);
  }

  /**
   * Start real-time WebSocket feed via DataEngine
   */
  private startRealtimeFeed(): void {
    this.realtimeSubId = this.dataEngine.subscribeRealtime(
      this.config.symbol,
      this.config.timeframe,
      (candle: Candle) => this.onNewCandle(candle),
      undefined // onTick - can be enabled for tick replay
    );

    if (this.realtimeSubId) {
      console.log(`[Platform] Real-time feed active: ${this.realtimeSubId}`);
    } else {
      // Fallback to auto-sync polling if no WebSocket available
      this.dataEngine.startAutoSync(this.config.symbol, this.config.timeframe, (c) => this.onNewCandle(c));
      console.log('[Platform] Using polling-based data sync (no WebSocket provider available)');
    }
  }

  /**
   * Handle new candle from real-time feed
   * Applies non-repainting validation before processing
   */
  private onNewCandle(candle: Candle): void {
    // Validate through non-repainting guard
    if (!this.nonRepaintGuard.validateRealtimeUpdate(candle, this.candles)) {
      return; // Rejected (future data or historical modification attempt)
    }

    // Update or append
    if (this.candles.length > 0 && this.candles[this.candles.length - 1].timestamp === candle.timestamp) {
      this.candles[this.candles.length - 1] = candle;
    } else {
      this.candles.push(candle);
    }

    // Update chart
    this.chartEngine.addCandle(candle);
    this.sessions.updateOnNewCandle(candle);

    // Incremental analysis (only on confirmed new bars, not updates to current bar)
    const isNewBar = this.candles.length > 1 &&
      this.candles[this.candles.length - 1].timestamp !== this.candles[this.candles.length - 2].timestamp;

    if (isNewBar) {
      // New bar confirmed - run incremental checks
      const newFVGs = this.fvg.checkNewCandle(candle, this.candles.length - 1, this.candles);
      const newSweeps = this.liquidity.checkForSweeps(candle, this.candles.length - 1);
      const structureBreaks = this.marketStructure.updateIncremental(this.candles, this.candles.length - 1);

      if (structureBreaks.length > 0 || newSweeps.length > 0) {
        this.runFullAnalysis();
      }
    }

    this.eventBus.emit({ type: 'NEW_CANDLE', data: { timeframe: this.config.timeframe, candle } });
  }


  /**
   * Run full analysis pipeline with non-repainting enforcement
   */
  runFullAnalysis(): void {
    if (this.candles.length < 20) return;
    const startTime = performance.now();

    // All analysis operates on validated candle data only
    // The candles array has already passed through nonRepaintGuard.validateCandleData()

    // 1. Market Structure
    const structure = this.marketStructure.analyze(this.candles);

    // 2. Sessions
    const sessionsData = this.sessions.analyze(this.candles);

    // 3. Liquidity
    const liquidityResult = this.liquidity.analyze(this.candles, sessionsData);

    // 4. Order Blocks
    const blocks = this.orderBlocks.analyze(this.candles, structure.structureBreaks);

    // 5. Fair Value Gaps
    const fvgs = this.fvg.analyze(this.candles);

    // 6. ICT Concepts
    const killZones = this.ict.getActiveKillZones(Date.now());
    const premiumDiscount = this.ict.calculatePremiumDiscount(this.candles);
    const swings = [...structure.swingHighs, ...structure.swingLows];

    // 7. LIT Trading
    const litSetups = this.lit.analyze(
      this.candles, structure.structureBreaks, liquidityResult.levels,
      liquidityResult.sweeps, blocks, fvgs, swings
    );

    // 8. OTE
    let ote = undefined;
    if (structure.swingHighs.length > 0 && structure.swingLows.length > 0) {
      const lastHigh = structure.swingHighs[structure.swingHighs.length - 1];
      const lastLow = structure.swingLows[structure.swingLows.length - 1];
      ote = this.ict.findOTE(lastHigh, lastLow, structure.currentBias === 'BULLISH' ? 'BULLISH' : 'BEARISH');
    }

    // 9. AI Analysis
    const aiAnalysis = this.aiAssistant.analyze({
      candles: this.candles,
      structureBreaks: structure.structureBreaks,
      liquidityLevels: liquidityResult.levels,
      sweeps: liquidityResult.sweeps,
      orderBlocks: blocks,
      fvgs,
      litSetups,
      smtDivergences: this.smt.getDivergences(),
      swings,
      sessions: sessionsData,
      premiumDiscount,
      currentBias: structure.currentBias,
    });

    // 10. Visualization (TradingView Lightweight Charts annotations)
    const annotations = this.visualization.generateAnnotations({
      structureBreaks: structure.structureBreaks,
      orderBlocks: blocks,
      fvgs,
      liquidityLevels: liquidityResult.levels,
      sweeps: liquidityResult.sweeps,
      litSetups,
      smtDivergences: this.smt.getDivergences(),
      premiumDiscount,
      killZones,
      ote,
      sessions: sessionsData,
      aiAnalysis: aiAnalysis || undefined,
    });

    // 11. Update chart annotations
    this.chartEngine.clearAnnotations();
    for (const annotation of annotations) {
      this.chartEngine.addAnnotation(annotation);
    }

    // 12. Scanner
    if (this.config.autoScan) {
      this.scanner.scan(this.config.symbol, this.config.timeframe, this.candles);
    }

    const elapsed = performance.now() - startTime;
    console.log(`[Platform] Analysis: ${elapsed.toFixed(1)}ms | Breaks: ${structure.structureBreaks.length} | OB: ${blocks.length} | FVG: ${fvgs.length} | LIT: ${litSetups.length} | Bias: ${structure.currentBias}`);
  }


  /**
   * Setup internal event listeners
   */
  private setupEventListeners(): void {
    this.eventBus.on('SCANNER_ALERT', (alert: ScannerAlert) => {
      console.log(`[Scanner] ${alert.type} | ${alert.symbol} ${alert.timeframe} | ${alert.message} (${alert.confidence}%)`);
    });

    this.eventBus.on('AI_ANALYSIS', (analysis: AIAnalysis) => {
      console.log(`[AI] ${analysis.setupType} | Confidence: ${analysis.confidenceScore}% | R:R: ${analysis.riskReward.toFixed(1)}`);
    });
  }

  // =========================================================================
  // PUBLIC API
  // =========================================================================

  /** Change symbol - fetches data from best provider */
  async changeSymbol(symbol: string): Promise<void> {
    // Stop current subscriptions
    if (this.realtimeSubId) this.dataEngine.unsubscribeRealtime(this.realtimeSubId);
    this.dataEngine.stopAutoSync(this.config.symbol, this.config.timeframe);

    this.config.symbol = symbol;
    const startDate = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
    const raw = await this.dataEngine.getCandles(symbol, this.config.timeframe, startDate);
    this.candles = this.nonRepaintGuard.validateCandleData(raw);
    this.chartEngine.setData(this.candles);
    this.startRealtimeFeed();
    this.runFullAnalysis();
  }

  /** Change timeframe */
  async changeTimeframe(timeframe: Timeframe): Promise<void> {
    if (this.realtimeSubId) this.dataEngine.unsubscribeRealtime(this.realtimeSubId);
    this.dataEngine.stopAutoSync(this.config.symbol, this.config.timeframe);

    this.config.timeframe = timeframe;
    const startDate = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
    const raw = await this.dataEngine.getCandles(this.config.symbol, timeframe, startDate);
    this.candles = this.nonRepaintGuard.validateCandleData(raw);
    this.chartEngine.setData(this.candles);

    // Auto-select template
    const recommended = this.templates.getRecommendedTemplate(timeframe);
    this.templates.loadTemplate(recommended);
    this.startRealtimeFeed();
    this.runFullAnalysis();
  }

  /** Load template */
  loadTemplate(type: TemplateType): void {
    const template = this.templates.loadTemplate(type);
    this.visualization.updateColors(template.colors);
    this.runFullAnalysis();
  }

  /** Run SMT scan across multiple symbols */
  async runSMTScan(symbols: string[], timeframe: Timeframe): Promise<void> {
    const dataMap = new Map<string, Candle[]>();
    const startDate = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);
    for (const symbol of symbols) {
      try {
        const raw = await this.dataEngine.getCandles(symbol, timeframe, startDate);
        dataMap.set(symbol, this.nonRepaintGuard.validateCandleData(raw));
      } catch { /* skip */ }
    }
    const result = this.smt.scanAllPairs(dataMap, timeframe);
    console.log(`[SMT] ${result.divergences.length} divergences found`);
  }

  /** Start replay mode */
  startReplay(startIndex?: number, speed?: number): void {
    this.chartEngine.startReplay(startIndex ?? 0, speed ?? 1);
  }

  /** Stop replay */
  stopReplay(): void { this.chartEngine.stopReplay(); }

  /** Get AI analysis */
  getAIAnalysis(): AIAnalysis | null { return this.aiAssistant.getLatestAnalysis(); }

  /** Get scanner alerts */
  getAlerts(): ScannerAlert[] { return this.scanner.getAlerts(); }

  /** Get bias */
  getCurrentBias(): Bias { return this.marketStructure.getBias(); }

  /** Subscribe to events */
  on(eventType: PlatformEvent['type'], handler: (data: any) => void): () => void {
    return this.eventBus.on(eventType, handler);
  }

  /** Get platform stats */
  getStats(): Record<string, any> {
    return {
      symbol: this.config.symbol,
      timeframe: this.config.timeframe,
      candleCount: this.candles.length,
      bias: this.marketStructure.getBias(),
      fps: this.chartEngine.getCurrentFPS(),
      activeAlerts: this.scanner.getUnacknowledgedCount(),
      providers: this.dataEngine.getProviderStatus(),
      websocket: this.dataEngine.getWebSocketStatus(),
      cache: this.dataEngine.getCacheStats(),
      nonRepainting: this.nonRepaintGuard.getStats(),
      isReplayActive: this.chartEngine.isReplayActive(),
    };
  }

  /** Get non-repainting validation statistics */
  getNonRepaintingStats() { return this.nonRepaintGuard.getStats(); }

  /** Get non-repainting audit trail */
  getAuditTrail(limit?: number) { return this.nonRepaintGuard.getAuditTrail(limit); }

  /** Destroy platform */
  destroy(): void {
    if (this.realtimeSubId) this.dataEngine.unsubscribeRealtime(this.realtimeSubId);
    this.dataEngine.destroy();
    this.chartEngine.destroy();
    this.eventBus.clear();
    if (this.analysisInterval) clearInterval(this.analysisInterval);
    console.log('[Platform] Destroyed');
  }
}

export default InstitutionalTradingPlatform;
