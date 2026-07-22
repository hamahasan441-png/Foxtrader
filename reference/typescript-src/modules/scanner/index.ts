// ============================================================================
// SCANNER MODULE - Real-time multi-symbol, multi-timeframe scanning
// BOS, CHOCH, Order Blocks, FVG, SMT, LIT Setups, Breakouts,
// Reversals, Trend Continuation
// Instant alerts with confidence scoring
// ============================================================================

import {
  Candle,
  ScannerAlert,
  ScannerSignalType,
  Timeframe,
  Direction,
  StructureBreak,
  OrderBlock,
  FairValueGap,
  SMTDivergence,
  LITSetup,
} from '../../core/types';
import { TradingEventBus } from '../../core/event-bus';
import { MarketStructureAnalyzer } from '../market-structure';
import { LiquidityAnalyzer } from '../liquidity';
import { OrderBlockAnalyzer } from '../order-blocks';
import { FairValueGapAnalyzer } from '../fair-value-gaps';
import { SMTAnalyzer } from '../smt';
import { LITTradingAnalyzer } from '../lit-trading';
import { SessionAnalyzer } from '../sessions';
import { calculateATR } from '../../core/utils';

export interface ScannerConfig {
  symbols: string[];
  timeframes: Timeframe[];
  enabledSignals: ScannerSignalType[];
  minConfidence: number;
  maxAlerts: number;
  alertCooldown: number; // ms between same-type alerts for same symbol
  soundEnabled: boolean;
  popupEnabled: boolean;
}

const DEFAULT_SCANNER_CONFIG: ScannerConfig = {
  symbols: ['EURUSD', 'GBPUSD', 'USDJPY', 'XAUUSD', 'US30', 'NAS100'],
  timeframes: ['M5', 'M15', 'H1', 'H4'],
  enabledSignals: ['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP', 'BREAKOUT', 'REVERSAL', 'TREND_CONTINUATION'],
  minConfidence: 60,
  maxAlerts: 500,
  alertCooldown: 300000, // 5 minutes
  soundEnabled: true,
  popupEnabled: true,
};


export class TradingScanner {
  private config: ScannerConfig;
  private eventBus?: TradingEventBus;
  private alerts: ScannerAlert[] = [];
  private lastAlertTimes: Map<string, number> = new Map();
  private alertIdCounter: number = 0;

  // Module instances for each symbol/timeframe
  private analyzers: Map<string, {
    structure: MarketStructureAnalyzer;
    liquidity: LiquidityAnalyzer;
    orderBlocks: OrderBlockAnalyzer;
    fvg: FairValueGapAnalyzer;
    smt: SMTAnalyzer;
    lit: LITTradingAnalyzer;
    sessions: SessionAnalyzer;
  }> = new Map();

  constructor(config: Partial<ScannerConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_SCANNER_CONFIG, ...config };
    this.eventBus = eventBus;
    this.initializeAnalyzers();
  }

  /**
   * Initialize analyzer instances for each symbol/timeframe combo
   */
  private initializeAnalyzers(): void {
    for (const symbol of this.config.symbols) {
      for (const tf of this.config.timeframes) {
        const key = `${symbol}_${tf}`;
        this.analyzers.set(key, {
          structure: new MarketStructureAnalyzer(),
          liquidity: new LiquidityAnalyzer(),
          orderBlocks: new OrderBlockAnalyzer(),
          fvg: new FairValueGapAnalyzer(),
          smt: new SMTAnalyzer(),
          lit: new LITTradingAnalyzer(),
          sessions: new SessionAnalyzer(),
        });
      }
    }
  }

  /**
   * Full scan of a symbol on a specific timeframe
   */
  scan(symbol: string, timeframe: Timeframe, candles: Candle[]): ScannerAlert[] {
    const key = `${symbol}_${timeframe}`;
    const modules = this.analyzers.get(key);
    if (!modules || candles.length < 20) return [];

    const newAlerts: ScannerAlert[] = [];

    // 1. Market Structure scan
    if (this.isSignalEnabled('BOS') || this.isSignalEnabled('CHOCH')) {
      const structure = modules.structure.analyze(candles);
      
      for (const brk of structure.structureBreaks.slice(-5)) {
        if (brk.type === 'BOS' && this.isSignalEnabled('BOS')) {
          const alert = this.createAlert('BOS', symbol, timeframe, brk.direction, brk.breakPrice, brk.breakTimestamp, 70, `BOS ${brk.direction} at ${brk.breakPrice.toFixed(5)} (${brk.structureType})`, brk);
          if (alert) newAlerts.push(alert);
        }
        if ((brk.type === 'CHOCH' || brk.type === 'MSS') && this.isSignalEnabled('CHOCH')) {
          const confidence = brk.type === 'MSS' ? 80 : 70;
          const alert = this.createAlert('CHOCH', symbol, timeframe, brk.direction, brk.breakPrice, brk.breakTimestamp, confidence, `${brk.type} ${brk.direction} at ${brk.breakPrice.toFixed(5)}`, brk);
          if (alert) newAlerts.push(alert);
        }
      }
    }

    // 2. Order Block scan
    if (this.isSignalEnabled('ORDER_BLOCK')) {
      const blocks = modules.orderBlocks.analyze(candles, modules.structure.getState().structureBreaks);
      const activeBlocks = blocks.filter(b => !b.mitigated).slice(-5);

      for (const ob of activeBlocks) {
        const alert = this.createAlert('ORDER_BLOCK', symbol, timeframe, ob.direction, (ob.zone.high + ob.zone.low) / 2, ob.timestamp, ob.strength, `${ob.type} ${ob.direction} at ${ob.zone.low.toFixed(5)}-${ob.zone.high.toFixed(5)}`, ob);
        if (alert) newAlerts.push(alert);
      }
    }

    // 3. FVG scan
    if (this.isSignalEnabled('FVG')) {
      const fvgs = modules.fvg.analyze(candles);
      const activeFVGs = fvgs.filter(f => !f.filled && f.type === 'FVG').slice(-5);

      for (const fvg of activeFVGs) {
        const alert = this.createAlert('FVG', symbol, timeframe, fvg.direction, (fvg.zone.high + fvg.zone.low) / 2, fvg.timestamp, 65, `${fvg.type} ${fvg.direction} gap ${fvg.zone.low.toFixed(5)}-${fvg.zone.high.toFixed(5)}`, fvg);
        if (alert) newAlerts.push(alert);
      }
    }

    // 4. LIT Setup scan
    if (this.isSignalEnabled('LIT_SETUP')) {
      const structure = modules.structure.getState();
      const liquidity = modules.liquidity.analyze(candles);
      const blocks = modules.orderBlocks.getAllBlocks();
      const fvgs = modules.fvg.getActiveFVGs();
      const swings = [...structure.swingHighs, ...structure.swingLows];

      const litSetups = modules.lit.analyze(
        candles, structure.structureBreaks, liquidity.levels,
        liquidity.sweeps, blocks, fvgs, swings
      );

      for (const setup of litSetups.slice(-3)) {
        const alert = this.createAlert('LIT_SETUP', symbol, timeframe, setup.direction, setup.price, setup.timestamp, setup.confidence, `LIT ${setup.type} ${setup.direction} (${setup.confirmations.length} confirmations)`, setup);
        if (alert) newAlerts.push(alert);
      }
    }

    // 5. Breakout scan
    if (this.isSignalEnabled('BREAKOUT')) {
      const breakout = this.detectBreakout(candles);
      if (breakout) {
        const alert = this.createAlert('BREAKOUT', symbol, timeframe, breakout.direction, breakout.price, breakout.timestamp, breakout.confidence, `Breakout ${breakout.direction} at ${breakout.price.toFixed(5)}`, breakout);
        if (alert) newAlerts.push(alert);
      }
    }

    // 6. Reversal scan
    if (this.isSignalEnabled('REVERSAL')) {
      const reversal = this.detectReversal(candles);
      if (reversal) {
        const alert = this.createAlert('REVERSAL', symbol, timeframe, reversal.direction, reversal.price, reversal.timestamp, reversal.confidence, `Reversal ${reversal.direction} signal at ${reversal.price.toFixed(5)}`, reversal);
        if (alert) newAlerts.push(alert);
      }
    }

    // 7. Trend Continuation scan
    if (this.isSignalEnabled('TREND_CONTINUATION')) {
      const continuation = this.detectTrendContinuation(candles);
      if (continuation) {
        const alert = this.createAlert('TREND_CONTINUATION', symbol, timeframe, continuation.direction, continuation.price, continuation.timestamp, continuation.confidence, `Trend continuation ${continuation.direction} at ${continuation.price.toFixed(5)}`, continuation);
        if (alert) newAlerts.push(alert);
      }
    }

    // Store and emit alerts
    for (const alert of newAlerts) {
      this.alerts.push(alert);
      this.eventBus?.emit({ type: 'SCANNER_ALERT', data: alert });
    }

    // Trim old alerts
    if (this.alerts.length > this.config.maxAlerts) {
      this.alerts = this.alerts.slice(-this.config.maxAlerts);
    }

    return newAlerts;
  }


  /**
   * Detect breakout patterns
   */
  private detectBreakout(candles: Candle[]): { direction: Direction; price: number; timestamp: number; confidence: number } | null {
    if (candles.length < 20) return null;

    const recent = candles.slice(-20);
    const last = recent[recent.length - 1];
    const atr = calculateATR(recent, 14);
    const currentATR = atr[atr.length - 1] || 0;

    // Find consolidation range (last 15 candles)
    const consolidation = recent.slice(0, -5);
    const rangeHigh = Math.max(...consolidation.map(c => c.high));
    const rangeLow = Math.min(...consolidation.map(c => c.low));
    const range = rangeHigh - rangeLow;

    // Tight consolidation followed by expansion
    if (range < currentATR * 2) {
      if (last.close > rangeHigh && (last.close - last.open) > currentATR * 0.8) {
        return { direction: 'BULLISH', price: last.close, timestamp: last.timestamp, confidence: 70 };
      }
      if (last.close < rangeLow && (last.open - last.close) > currentATR * 0.8) {
        return { direction: 'BEARISH', price: last.close, timestamp: last.timestamp, confidence: 70 };
      }
    }

    return null;
  }

  /**
   * Detect reversal patterns
   */
  private detectReversal(candles: Candle[]): { direction: Direction; price: number; timestamp: number; confidence: number } | null {
    if (candles.length < 10) return null;

    const last = candles[candles.length - 1];
    const prev = candles[candles.length - 2];
    const atr = calculateATR(candles.slice(-20), 14);
    const currentATR = atr[atr.length - 1] || 0;

    // Strong rejection candle (long wick)
    const totalRange = last.high - last.low;
    const body = Math.abs(last.close - last.open);
    const upperWick = last.high - Math.max(last.open, last.close);
    const lowerWick = Math.min(last.open, last.close) - last.low;

    // Bearish reversal: long upper wick after uptrend
    if (upperWick > body * 2 && totalRange > currentATR * 1.2) {
      const inUptrend = candles.slice(-5, -1).every((c, i, arr) =>
        i === 0 || c.close > arr[i - 1].close
      );
      if (inUptrend) {
        return { direction: 'BEARISH', price: last.close, timestamp: last.timestamp, confidence: 65 };
      }
    }

    // Bullish reversal: long lower wick after downtrend
    if (lowerWick > body * 2 && totalRange > currentATR * 1.2) {
      const inDowntrend = candles.slice(-5, -1).every((c, i, arr) =>
        i === 0 || c.close < arr[i - 1].close
      );
      if (inDowntrend) {
        return { direction: 'BULLISH', price: last.close, timestamp: last.timestamp, confidence: 65 };
      }
    }

    return null;
  }

  /**
   * Detect trend continuation patterns
   */
  private detectTrendContinuation(candles: Candle[]): { direction: Direction; price: number; timestamp: number; confidence: number } | null {
    if (candles.length < 20) return null;

    const last = candles[candles.length - 1];
    const atr = calculateATR(candles.slice(-20), 14);
    const currentATR = atr[atr.length - 1] || 0;

    // Check for pullback in trend followed by continuation
    const trend = this.detectTrend(candles.slice(-20));
    if (!trend) return null;

    const recent5 = candles.slice(-5);
    const isPullback = trend === 'BULLISH'
      ? recent5.slice(0, -1).some(c => c.close < c.open) && last.close > last.open
      : recent5.slice(0, -1).some(c => c.close > c.open) && last.close < last.open;

    if (isPullback && Math.abs(last.close - last.open) > currentATR * 0.6) {
      return { direction: trend, price: last.close, timestamp: last.timestamp, confidence: 60 };
    }

    return null;
  }

  /**
   * Simple trend detection
   */
  private detectTrend(candles: Candle[]): Direction | null {
    if (candles.length < 5) return null;
    const first = candles[0].close;
    const last = candles[candles.length - 1].close;
    const change = (last - first) / first;

    if (change > 0.002) return 'BULLISH';
    if (change < -0.002) return 'BEARISH';
    return null;
  }

  /**
   * Create alert with cooldown check
   */
  private createAlert(
    type: ScannerSignalType,
    symbol: string,
    timeframe: Timeframe,
    direction: Direction,
    price: number,
    timestamp: number,
    confidence: number,
    message: string,
    details: Record<string, unknown> | any
  ): ScannerAlert | null {
    if (confidence < this.config.minConfidence) return null;

    // Cooldown check
    const cooldownKey = `${type}_${symbol}_${timeframe}`;
    const lastTime = this.lastAlertTimes.get(cooldownKey) || 0;
    if (Date.now() - lastTime < this.config.alertCooldown) return null;

    this.lastAlertTimes.set(cooldownKey, Date.now());
    this.alertIdCounter++;

    return {
      id: `alert_${this.alertIdCounter}_${Date.now()}`,
      type,
      symbol,
      timeframe,
      direction,
      price,
      timestamp,
      confidence,
      message,
      details: details as Record<string, unknown>,
      acknowledged: false,
    };
  }

  private isSignalEnabled(type: ScannerSignalType): boolean {
    return this.config.enabledSignals.includes(type);
  }

  // --- Public API ---

  getAlerts(filter?: { type?: ScannerSignalType; symbol?: string; minConfidence?: number }): ScannerAlert[] {
    let results = [...this.alerts];
    if (filter?.type) results = results.filter(a => a.type === filter.type);
    if (filter?.symbol) results = results.filter(a => a.symbol === filter.symbol);
    if (filter?.minConfidence) results = results.filter(a => a.confidence >= filter.minConfidence!);
    return results;
  }

  acknowledgeAlert(id: string): void {
    const alert = this.alerts.find(a => a.id === id);
    if (alert) alert.acknowledged = true;
  }

  clearAlerts(): void {
    this.alerts = [];
  }

  getUnacknowledgedCount(): number {
    return this.alerts.filter(a => !a.acknowledged).length;
  }

  updateConfig(config: Partial<ScannerConfig>): void {
    this.config = { ...this.config, ...config };
  }

  reset(): void {
    this.alerts = [];
    this.lastAlertTimes.clear();
    this.analyzers.clear();
    this.initializeAnalyzers();
  }
}
