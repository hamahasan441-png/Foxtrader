// ============================================================================
// RISK MANAGEMENT ENGINE - Institutional Risk Controls
// Dynamic Position Sizing | Fixed/Percentage Risk | Kelly Criterion
// ATR/Volatility Stops | Max Daily/Weekly Loss | Consecutive Loss Protection
// Drawdown Protection | Portfolio Exposure & Correlation Monitors
// ============================================================================

import { Candle } from '../core/types';
import { calculateATR, getPipSize } from '../core/utils';
import { TradingEventBus } from '../core/event-bus';
import { Position } from '../execution/types';

export type PositionSizingMethod =
  | 'FIXED_LOTS' | 'FIXED_RISK' | 'PERCENTAGE_RISK' | 'KELLY' | 'ATR_BASED' | 'VOLATILITY';

export type StopMethod = 'FIXED' | 'ATR' | 'VOLATILITY' | 'STRUCTURE';

export interface RiskConfig {
  accountBalance: number;
  accountCurrency: string;
  sizingMethod: PositionSizingMethod;
  /** Risk per trade as % of balance (for PERCENTAGE_RISK) */
  riskPercentPerTrade: number;
  /** Fixed risk amount in account currency (for FIXED_RISK) */
  fixedRiskAmount: number;
  /** Fixed lot size (for FIXED_LOTS) */
  fixedLots: number;
  /** Kelly fraction multiplier (0.5 = half-Kelly, safer) */
  kellyFraction: number;
  /** ATR multiplier for ATR-based stops */
  atrStopMultiplier: number;
  /** Volatility stop multiplier */
  volatilityStopMultiplier: number;
  /** Maximum daily loss as % of balance */
  maxDailyLossPercent: number;
  /** Maximum weekly loss as % of balance */
  maxWeeklyLossPercent: number;
  /** Stop trading after N consecutive losses */
  maxConsecutiveLosses: number;
  /** Maximum drawdown % before halting */
  maxDrawdownPercent: number;
  /** Maximum total portfolio exposure as % of balance */
  maxPortfolioExposurePercent: number;
  /** Maximum correlated exposure (correlated positions combined) */
  maxCorrelatedExposurePercent: number;
  /** Correlation threshold to consider positions correlated */
  correlationThreshold: number;
}

const DEFAULT_CONFIG: RiskConfig = {
  accountBalance: 100000,
  accountCurrency: 'USD',
  sizingMethod: 'PERCENTAGE_RISK',
  riskPercentPerTrade: 1.0,
  fixedRiskAmount: 1000,
  fixedLots: 0.1,
  kellyFraction: 0.5,
  atrStopMultiplier: 1.5,
  volatilityStopMultiplier: 2.0,
  maxDailyLossPercent: 3.0,
  maxWeeklyLossPercent: 6.0,
  maxConsecutiveLosses: 4,
  maxDrawdownPercent: 15.0,
  maxPortfolioExposurePercent: 500.0, // 5x leverage
  maxCorrelatedExposurePercent: 200.0,
  correlationThreshold: 0.7,
};

export interface PositionSizeResult {
  volume: number;
  riskAmount: number;
  riskPercent: number;
  stopDistance: number;
  method: PositionSizingMethod;
  warnings: string[];
}

export interface RiskCheckResult {
  allowed: boolean;
  reasons: string[];
  currentDailyLoss: number;
  currentWeeklyLoss: number;
  consecutiveLosses: number;
  currentDrawdown: number;
  portfolioExposure: number;
}

interface TradeOutcome {
  timestamp: number;
  pnl: number;
  win: boolean;
  symbol: string;
}

export class RiskEngine {
  private config: RiskConfig;
  private eventBus?: TradingEventBus;
  private tradeHistory: TradeOutcome[] = [];
  private peakBalance: number;
  private currentBalance: number;
  private tradingHalted: boolean = false;
  private haltReason: string = '';
  /** Correlation matrix between symbols */
  private correlations: Map<string, number> = new Map();

  constructor(config: Partial<RiskConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
    this.peakBalance = this.config.accountBalance;
    this.currentBalance = this.config.accountBalance;
  }

  // =========================================================================
  // POSITION SIZING
  // =========================================================================

  /**
   * Calculate position size based on the configured method
   */
  calculatePositionSize(params: {
    symbol: string;
    entryPrice: number;
    stopLossPrice: number;
    candles?: Candle[];
  }): PositionSizeResult {
    const { symbol, entryPrice, stopLossPrice, candles } = params;
    const warnings: string[] = [];
    const pipSize = getPipSize(symbol);
    const stopDistance = Math.abs(entryPrice - stopLossPrice);

    if (stopDistance === 0) {
      warnings.push('Stop distance is zero - using minimum');
    }

    let volume = 0;
    let riskAmount = 0;

    switch (this.config.sizingMethod) {
      case 'FIXED_LOTS':
        volume = this.config.fixedLots;
        riskAmount = stopDistance * volume * 100000;
        break;

      case 'FIXED_RISK':
        riskAmount = this.config.fixedRiskAmount;
        volume = stopDistance > 0 ? riskAmount / (stopDistance * 100000) : 0;
        break;

      case 'PERCENTAGE_RISK':
        riskAmount = this.currentBalance * (this.config.riskPercentPerTrade / 100);
        volume = stopDistance > 0 ? riskAmount / (stopDistance * 100000) : 0;
        break;

      case 'KELLY': {
        const kellyPercent = this.calculateKellyPercent();
        riskAmount = this.currentBalance * kellyPercent * this.config.kellyFraction;
        volume = stopDistance > 0 ? riskAmount / (stopDistance * 100000) : 0;
        if (kellyPercent <= 0) warnings.push('Kelly suggests no position (negative edge)');
        break;
      }

      case 'ATR_BASED': {
        if (!candles || candles.length < 15) {
          warnings.push('Insufficient data for ATR - falling back to percentage risk');
          riskAmount = this.currentBalance * (this.config.riskPercentPerTrade / 100);
          volume = stopDistance > 0 ? riskAmount / (stopDistance * 100000) : 0;
        } else {
          const atr = calculateATR(candles, 14);
          const currentATR = atr[atr.length - 1];
          const atrStopDist = currentATR * this.config.atrStopMultiplier;
          riskAmount = this.currentBalance * (this.config.riskPercentPerTrade / 100);
          volume = atrStopDist > 0 ? riskAmount / (atrStopDist * 100000) : 0;
        }
        break;
      }

      case 'VOLATILITY': {
        if (!candles || candles.length < 20) {
          warnings.push('Insufficient data for volatility sizing');
          riskAmount = this.currentBalance * (this.config.riskPercentPerTrade / 100);
          volume = stopDistance > 0 ? riskAmount / (stopDistance * 100000) : 0;
        } else {
          const vol = this.calculateVolatility(candles);
          const volStopDist = vol * this.config.volatilityStopMultiplier;
          riskAmount = this.currentBalance * (this.config.riskPercentPerTrade / 100);
          volume = volStopDist > 0 ? riskAmount / (volStopDist * 100000) : 0;
        }
        break;
      }
    }

    // Round to reasonable lot precision
    volume = Math.max(0.01, Math.round(volume * 100) / 100);

    return {
      volume,
      riskAmount,
      riskPercent: (riskAmount / this.currentBalance) * 100,
      stopDistance,
      method: this.config.sizingMethod,
      warnings,
    };
  }

  /**
   * Calculate Kelly Criterion percentage from trade history
   * Kelly % = W - [(1 - W) / R] where W=win rate, R=win/loss ratio
   */
  calculateKellyPercent(): number {
    const wins = this.tradeHistory.filter(t => t.win);
    const losses = this.tradeHistory.filter(t => !t.win);

    if (wins.length < 5 || losses.length < 3) {
      return this.config.riskPercentPerTrade / 100; // Not enough data - use default
    }

    const winRate = wins.length / this.tradeHistory.length;
    const avgWin = wins.reduce((s, t) => s + t.pnl, 0) / wins.length;
    const avgLoss = Math.abs(losses.reduce((s, t) => s + t.pnl, 0) / losses.length);

    if (avgLoss === 0) return this.config.riskPercentPerTrade / 100;

    const winLossRatio = avgWin / avgLoss;
    const kelly = winRate - (1 - winRate) / winLossRatio;

    return Math.max(0, Math.min(kelly, 0.25)); // Cap at 25% for safety
  }

  // =========================================================================
  // STOP LOSS CALCULATION
  // =========================================================================

  /**
   * Calculate stop loss price using the configured method
   */
  calculateStopLoss(params: {
    symbol: string;
    entryPrice: number;
    direction: 'BUY' | 'SELL';
    method: StopMethod;
    candles?: Candle[];
    structureLevel?: number;
  }): number {
    const { entryPrice, direction, method, candles, structureLevel } = params;

    switch (method) {
      case 'ATR': {
        if (!candles || candles.length < 15) return this.fixedStop(entryPrice, direction);
        const atr = calculateATR(candles, 14);
        const dist = atr[atr.length - 1] * this.config.atrStopMultiplier;
        return direction === 'BUY' ? entryPrice - dist : entryPrice + dist;
      }
      case 'VOLATILITY': {
        if (!candles || candles.length < 20) return this.fixedStop(entryPrice, direction);
        const vol = this.calculateVolatility(candles);
        const dist = vol * this.config.volatilityStopMultiplier;
        return direction === 'BUY' ? entryPrice - dist : entryPrice + dist;
      }
      case 'STRUCTURE':
        return structureLevel ?? this.fixedStop(entryPrice, direction);
      case 'FIXED':
      default:
        return this.fixedStop(entryPrice, direction);
    }
  }

  private fixedStop(entryPrice: number, direction: 'BUY' | 'SELL'): number {
    const dist = entryPrice * 0.005; // 0.5% default
    return direction === 'BUY' ? entryPrice - dist : entryPrice + dist;
  }

  private calculateVolatility(candles: Candle[]): number {
    const returns: number[] = [];
    for (let i = 1; i < candles.length; i++) {
      returns.push((candles[i].close - candles[i - 1].close) / candles[i - 1].close);
    }
    const mean = returns.reduce((a, b) => a + b, 0) / returns.length;
    const variance = returns.reduce((s, r) => s + (r - mean) ** 2, 0) / returns.length;
    const stdDev = Math.sqrt(variance);
    return stdDev * candles[candles.length - 1].close; // Convert to price units
  }


  // =========================================================================
  // RISK CHECKS - Pre-trade gatekeeping
  // =========================================================================

  /**
   * Check whether a new trade is allowed under all risk rules
   */
  canOpenTrade(params: {
    symbol: string;
    riskAmount: number;
    openPositions: Position[];
  }): RiskCheckResult {
    const { symbol, riskAmount, openPositions } = params;
    const reasons: string[] = [];

    const dailyLoss = this.getDailyLoss();
    const weeklyLoss = this.getWeeklyLoss();
    const consecutive = this.getConsecutiveLosses();
    const drawdown = this.getCurrentDrawdown();
    const exposure = this.getPortfolioExposure(openPositions);

    // Already halted
    if (this.tradingHalted) {
      reasons.push(`Trading halted: ${this.haltReason}`);
    }

    // Daily loss limit
    const maxDaily = this.currentBalance * (this.config.maxDailyLossPercent / 100);
    if (dailyLoss >= maxDaily) {
      reasons.push(`Daily loss limit reached (${dailyLoss.toFixed(2)} >= ${maxDaily.toFixed(2)})`);
    }

    // Weekly loss limit
    const maxWeekly = this.currentBalance * (this.config.maxWeeklyLossPercent / 100);
    if (weeklyLoss >= maxWeekly) {
      reasons.push(`Weekly loss limit reached (${weeklyLoss.toFixed(2)} >= ${maxWeekly.toFixed(2)})`);
    }

    // Consecutive losses
    if (consecutive >= this.config.maxConsecutiveLosses) {
      reasons.push(`Consecutive loss limit reached (${consecutive})`);
    }

    // Drawdown
    if (drawdown >= this.config.maxDrawdownPercent) {
      reasons.push(`Max drawdown reached (${drawdown.toFixed(1)}%)`);
    }

    // Portfolio exposure
    const maxExposure = this.currentBalance * (this.config.maxPortfolioExposurePercent / 100);
    const newExposure = exposure + riskAmount;
    if (newExposure > maxExposure) {
      reasons.push(`Portfolio exposure limit exceeded`);
    }

    // Correlated exposure
    const correlatedExposure = this.getCorrelatedExposure(symbol, openPositions);
    const maxCorrelated = this.currentBalance * (this.config.maxCorrelatedExposurePercent / 100);
    if (correlatedExposure + riskAmount > maxCorrelated) {
      reasons.push(`Correlated exposure limit exceeded for ${symbol}`);
    }

    return {
      allowed: reasons.length === 0,
      reasons,
      currentDailyLoss: dailyLoss,
      currentWeeklyLoss: weeklyLoss,
      consecutiveLosses: consecutive,
      currentDrawdown: drawdown,
      portfolioExposure: exposure,
    };
  }

  // =========================================================================
  // TRADE OUTCOME TRACKING
  // =========================================================================

  /**
   * Record a completed trade outcome
   */
  recordTrade(pnl: number, symbol: string): void {
    this.tradeHistory.push({
      timestamp: Date.now(),
      pnl,
      win: pnl > 0,
      symbol,
    });

    this.currentBalance += pnl;
    if (this.currentBalance > this.peakBalance) {
      this.peakBalance = this.currentBalance;
    }

    // Auto-halt checks
    this.checkAutoHalt();
  }

  private checkAutoHalt(): void {
    const drawdown = this.getCurrentDrawdown();
    if (drawdown >= this.config.maxDrawdownPercent) {
      this.haltTrading(`Max drawdown ${drawdown.toFixed(1)}% reached`);
    }
    const consecutive = this.getConsecutiveLosses();
    if (consecutive >= this.config.maxConsecutiveLosses) {
      this.haltTrading(`${consecutive} consecutive losses`);
    }
    const dailyLoss = this.getDailyLoss();
    if (dailyLoss >= this.currentBalance * (this.config.maxDailyLossPercent / 100)) {
      this.haltTrading('Daily loss limit');
    }
  }

  // =========================================================================
  // LOSS & DRAWDOWN CALCULATIONS
  // =========================================================================

  getDailyLoss(): number {
    const dayStart = new Date();
    dayStart.setUTCHours(0, 0, 0, 0);
    const todayTrades = this.tradeHistory.filter(t => t.timestamp >= dayStart.getTime());
    const netPnL = todayTrades.reduce((s, t) => s + t.pnl, 0);
    return netPnL < 0 ? Math.abs(netPnL) : 0;
  }

  getWeeklyLoss(): number {
    const weekStart = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const weekTrades = this.tradeHistory.filter(t => t.timestamp >= weekStart);
    const netPnL = weekTrades.reduce((s, t) => s + t.pnl, 0);
    return netPnL < 0 ? Math.abs(netPnL) : 0;
  }

  getConsecutiveLosses(): number {
    let count = 0;
    for (let i = this.tradeHistory.length - 1; i >= 0; i--) {
      if (!this.tradeHistory[i].win) count++;
      else break;
    }
    return count;
  }

  getCurrentDrawdown(): number {
    if (this.peakBalance === 0) return 0;
    return ((this.peakBalance - this.currentBalance) / this.peakBalance) * 100;
  }

  // =========================================================================
  // EXPOSURE & CORRELATION MONITORS
  // =========================================================================

  getPortfolioExposure(positions: Position[]): number {
    return positions
      .filter(p => p.status !== 'CLOSED')
      .reduce((sum, p) => sum + p.volume * p.avgEntryPrice * 100000, 0);
  }

  getPortfolioExposurePercent(positions: Position[]): number {
    return (this.getPortfolioExposure(positions) / this.currentBalance) * 100;
  }

  /**
   * Get combined exposure of positions correlated with the given symbol
   */
  getCorrelatedExposure(symbol: string, positions: Position[]): number {
    let exposure = 0;
    for (const pos of positions) {
      if (pos.status === 'CLOSED') continue;
      const corr = this.getCorrelation(symbol, pos.symbol);
      if (Math.abs(corr) >= this.config.correlationThreshold) {
        exposure += pos.volume * pos.avgEntryPrice * 100000;
      }
    }
    return exposure;
  }

  /**
   * Set correlation between two symbols (from SMT/correlation scanner)
   */
  setCorrelation(symbol1: string, symbol2: string, correlation: number): void {
    this.correlations.set(this.corrKey(symbol1, symbol2), correlation);
  }

  getCorrelation(symbol1: string, symbol2: string): number {
    if (symbol1 === symbol2) return 1.0;
    return this.correlations.get(this.corrKey(symbol1, symbol2)) ?? 0;
  }

  private corrKey(a: string, b: string): string {
    return [a, b].sort().join('|');
  }

  /**
   * Get a full correlation-risk report
   */
  getCorrelationRiskReport(positions: Position[]): {
    clusters: { symbols: string[]; combinedExposure: number; risk: 'LOW' | 'MEDIUM' | 'HIGH' }[];
    warnings: string[];
  } {
    const open = positions.filter(p => p.status !== 'CLOSED');
    const clusters: { symbols: string[]; combinedExposure: number; risk: 'LOW' | 'MEDIUM' | 'HIGH' }[] = [];
    const visited = new Set<string>();
    const warnings: string[] = [];

    for (const pos of open) {
      if (visited.has(pos.id)) continue;
      const cluster = [pos.symbol];
      let exposure = pos.volume * pos.avgEntryPrice * 100000;
      visited.add(pos.id);

      for (const other of open) {
        if (visited.has(other.id)) continue;
        if (Math.abs(this.getCorrelation(pos.symbol, other.symbol)) >= this.config.correlationThreshold) {
          cluster.push(other.symbol);
          exposure += other.volume * other.avgEntryPrice * 100000;
          visited.add(other.id);
        }
      }

      if (cluster.length > 1) {
        const expPercent = (exposure / this.currentBalance) * 100;
        const risk = expPercent > 150 ? 'HIGH' : expPercent > 75 ? 'MEDIUM' : 'LOW';
        clusters.push({ symbols: cluster, combinedExposure: exposure, risk });
        if (risk === 'HIGH') {
          warnings.push(`High correlated exposure: ${cluster.join(', ')} (${expPercent.toFixed(0)}%)`);
        }
      }
    }

    return { clusters, warnings };
  }

  // =========================================================================
  // TRADING HALT CONTROL
  // =========================================================================

  haltTrading(reason: string): void {
    this.tradingHalted = true;
    this.haltReason = reason;
    this.eventBus?.emit({ type: 'RISK_HALT', data: { reason, timestamp: Date.now() } });
    console.warn(`[Risk] TRADING HALTED: ${reason}`);
  }

  resumeTrading(): void {
    this.tradingHalted = false;
    this.haltReason = '';
    console.log('[Risk] Trading resumed');
  }

  isTradingHalted(): boolean { return this.tradingHalted; }

  /** Reset daily counters (call at session rollover) */
  resetDaily(): void {
    if (this.getDailyLoss() < this.currentBalance * (this.config.maxDailyLossPercent / 100)) {
      if (this.haltReason.includes('Daily')) this.resumeTrading();
    }
  }

  // =========================================================================
  // GETTERS & STATS
  // =========================================================================

  getRiskStatus(positions: Position[]): {
    balance: number;
    peakBalance: number;
    drawdown: number;
    dailyLoss: number;
    weeklyLoss: number;
    consecutiveLosses: number;
    exposurePercent: number;
    kellyPercent: number;
    halted: boolean;
    haltReason: string;
  } {
    return {
      balance: this.currentBalance,
      peakBalance: this.peakBalance,
      drawdown: this.getCurrentDrawdown(),
      dailyLoss: this.getDailyLoss(),
      weeklyLoss: this.getWeeklyLoss(),
      consecutiveLosses: this.getConsecutiveLosses(),
      exposurePercent: this.getPortfolioExposurePercent(positions),
      kellyPercent: this.calculateKellyPercent() * 100,
      halted: this.tradingHalted,
      haltReason: this.haltReason,
    };
  }

  updateBalance(balance: number): void {
    this.currentBalance = balance;
    if (balance > this.peakBalance) this.peakBalance = balance;
  }

  getBalance(): number { return this.currentBalance; }
  updateConfig(config: Partial<RiskConfig>): void { this.config = { ...this.config, ...config }; }
  getConfig(): RiskConfig { return { ...this.config }; }

  reset(): void {
    this.tradeHistory = [];
    this.peakBalance = this.config.accountBalance;
    this.currentBalance = this.config.accountBalance;
    this.tradingHalted = false;
    this.haltReason = '';
    this.correlations.clear();
  }
}
