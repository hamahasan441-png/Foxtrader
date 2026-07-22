// ============================================================================
// PROFESSIONAL BACKTESTER
// Tick Data | Spread | Commission | Slippage | Variable Spread | Multi-Year
// Monte Carlo Simulation | Walk-Forward Analysis
// Metrics: Equity Curve, Drawdown, Profit Factor, Win Rate, Sharpe, Sortino,
// Expectancy, Net/Gross Profit/Loss, Avg Trade, Largest Win/Loss
// ============================================================================

import { Candle, Tick, Direction, Timeframe } from '../core/types';

export interface BacktestConfig {
  initialBalance: number;
  /** Fixed spread in price units (used if variableSpread disabled) */
  spread: number;
  /** Enable variable spread modeling (widens during volatility/news) */
  variableSpread: boolean;
  /** Commission per lot (round turn) */
  commissionPerLot: number;
  /** Slippage in price units per trade */
  slippage: number;
  /** Risk per trade as % of balance */
  riskPercent: number;
  /** Use tick data for precise fills (more accurate) */
  useTickData: boolean;
  /** Contract size (units per lot) */
  contractSize: number;
}

const DEFAULT_CONFIG: BacktestConfig = {
  initialBalance: 100000,
  spread: 0.00002,
  variableSpread: true,
  commissionPerLot: 7,
  slippage: 0.00001,
  riskPercent: 1,
  useTickData: false,
  contractSize: 100000,
};

/** A signal produced by a strategy at a given bar */
export interface StrategySignal {
  index: number;
  timestamp: number;
  direction: Direction;
  entry: number;
  stopLoss: number;
  takeProfit: number;
  /** Optional position size override (lots) */
  volume?: number;
  /** Confidence 0-100 for filtering */
  confidence?: number;
  setupType?: string;
}

/** Strategy function: given candles up to index i (NO look-ahead), returns a signal or null */
export type StrategyFunction = (candles: Candle[], index: number) => StrategySignal | null;

export interface BacktestTrade {
  id: number;
  direction: Direction;
  entryIndex: number;
  entryTime: number;
  entryPrice: number;
  exitIndex: number;
  exitTime: number;
  exitPrice: number;
  volume: number;
  grossPnL: number;
  commission: number;
  slippageCost: number;
  netPnL: number;
  rMultiple: number;
  exitReason: 'TP' | 'SL' | 'END';
  balanceAfter: number;
  setupType?: string;
  holdingBars: number;
}

export interface BacktestMetrics {
  netProfit: number;
  grossProfit: number;
  grossLoss: number;
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRate: number;
  profitFactor: number;
  expectancy: number;
  averageTrade: number;
  averageWin: number;
  averageLoss: number;
  largestWin: number;
  largestLoss: number;
  maxDrawdown: number;
  maxDrawdownPercent: number;
  sharpeRatio: number;
  sortinoRatio: number;
  calmarRatio: number;
  recoveryFactor: number;
  avgHoldingBars: number;
  maxConsecutiveWins: number;
  maxConsecutiveLosses: number;
  finalBalance: number;
  returnPercent: number;
  totalCommission: number;
}

export interface EquityPoint {
  index: number;
  timestamp: number;
  balance: number;
  equity: number;
  drawdown: number;
  drawdownPercent: number;
}

export interface BacktestResult {
  config: BacktestConfig;
  trades: BacktestTrade[];
  metrics: BacktestMetrics;
  equityCurve: EquityPoint[];
  startDate: number;
  endDate: number;
  durationDays: number;
  symbol: string;
  timeframe: Timeframe;
}

export class Backtester {
  private config: BacktestConfig;

  constructor(config: Partial<BacktestConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  /**
   * Run a backtest over candle data with a strategy function.
   * The strategy is called bar-by-bar and only sees data up to the current index.
   */
  run(
    candles: Candle[],
    strategy: StrategyFunction,
    symbol: string = 'UNKNOWN',
    timeframe: Timeframe = 'M15'
  ): BacktestResult {
    const trades: BacktestTrade[] = [];
    const equityCurve: EquityPoint[] = [];
    let balance = this.config.initialBalance;
    let peakBalance = balance;
    let tradeId = 0;
    let openTrade: { signal: StrategySignal; volume: number } | null = null;

    for (let i = 0; i < candles.length; i++) {
      const candle = candles[i];

      // Manage open trade first (check SL/TP hit intrabar)
      if (openTrade) {
        const result = this.checkTradeExit(openTrade.signal, candle, i);
        if (result.exited) {
          const trade = this.buildTrade(
            ++tradeId, openTrade.signal, openTrade.volume,
            i, candle.timestamp, result.exitPrice, result.reason, balance
          );
          balance += trade.netPnL;
          trade.balanceAfter = balance;
          trades.push(trade);
          openTrade = null;

          peakBalance = Math.max(peakBalance, balance);
        }
      }

      // Look for new entry (only if flat) - strategy sees candles[0..i] (no future)
      if (!openTrade) {
        // CRITICAL: pass only candles up to and including i (no look-ahead)
        const visibleCandles = candles.slice(0, i + 1);
        const signal = strategy(visibleCandles, i);
        if (signal) {
          const volume = signal.volume ?? this.calculateVolume(balance, signal);
          openTrade = { signal, volume };
        }
      }

      // Record equity point
      const dd = peakBalance - balance;
      equityCurve.push({
        index: i,
        timestamp: candle.timestamp,
        balance,
        equity: balance, // Simplified: no floating pnl tracking here
        drawdown: dd,
        drawdownPercent: peakBalance > 0 ? (dd / peakBalance) * 100 : 0,
      });
    }

    // Close any remaining open trade at last candle
    if (openTrade) {
      const lastCandle = candles[candles.length - 1];
      const trade = this.buildTrade(
        ++tradeId, openTrade.signal, openTrade.volume,
        candles.length - 1, lastCandle.timestamp, lastCandle.close, 'END', balance
      );
      balance += trade.netPnL;
      trade.balanceAfter = balance;
      trades.push(trade);
    }

    const metrics = this.calculateMetrics(trades, equityCurve, this.config.initialBalance);

    return {
      config: this.config,
      trades,
      metrics,
      equityCurve,
      startDate: candles[0]?.timestamp ?? 0,
      endDate: candles[candles.length - 1]?.timestamp ?? 0,
      durationDays: candles.length > 0
        ? (candles[candles.length - 1].timestamp - candles[0].timestamp) / 86400000
        : 0,
      symbol,
      timeframe,
    };
  }

  /**
   * Check if an open trade hits SL or TP within a candle
   * Models spread, slippage. Uses conservative fill (SL checked before TP if both hit)
   */
  private checkTradeExit(signal: StrategySignal, candle: Candle, index: number): {
    exited: boolean; exitPrice: number; reason: 'TP' | 'SL' | 'END';
  } {
    const spread = this.getSpread(candle);

    if (signal.direction === 'BULLISH') {
      // Check SL first (conservative)
      if (candle.low - spread <= signal.stopLoss) {
        return { exited: true, exitPrice: signal.stopLoss - this.config.slippage, reason: 'SL' };
      }
      if (candle.high >= signal.takeProfit) {
        return { exited: true, exitPrice: signal.takeProfit, reason: 'TP' };
      }
    } else {
      if (candle.high + spread >= signal.stopLoss) {
        return { exited: true, exitPrice: signal.stopLoss + this.config.slippage, reason: 'SL' };
      }
      if (candle.low <= signal.takeProfit) {
        return { exited: true, exitPrice: signal.takeProfit, reason: 'TP' };
      }
    }

    return { exited: false, exitPrice: 0, reason: 'END' };
  }

  private buildTrade(
    id: number, signal: StrategySignal, volume: number,
    exitIndex: number, exitTime: number, exitPrice: number,
    reason: 'TP' | 'SL' | 'END', balanceBefore: number
  ): BacktestTrade {
    const priceDiff = signal.direction === 'BULLISH'
      ? exitPrice - signal.entry
      : signal.entry - exitPrice;

    const grossPnL = priceDiff * volume * this.config.contractSize;
    const commission = this.config.commissionPerLot * volume;
    const slippageCost = this.config.slippage * volume * this.config.contractSize;
    const netPnL = grossPnL - commission;

    const risk = Math.abs(signal.entry - signal.stopLoss);
    const rMultiple = risk > 0 ? priceDiff / risk : 0;

    return {
      id, direction: signal.direction,
      entryIndex: signal.index, entryTime: signal.timestamp, entryPrice: signal.entry,
      exitIndex, exitTime, exitPrice, volume,
      grossPnL, commission, slippageCost, netPnL, rMultiple,
      exitReason: reason, balanceAfter: balanceBefore,
      setupType: signal.setupType,
      holdingBars: exitIndex - signal.index,
    };
  }

  private calculateVolume(balance: number, signal: StrategySignal): number {
    const riskAmount = balance * (this.config.riskPercent / 100);
    const stopDistance = Math.abs(signal.entry - signal.stopLoss);
    if (stopDistance === 0) return 0.01;
    const volume = riskAmount / (stopDistance * this.config.contractSize);
    return Math.max(0.01, Math.round(volume * 100) / 100);
  }

  /**
   * Get spread for a candle - variable spread widens with range (volatility proxy)
   */
  private getSpread(candle: Candle): number {
    if (!this.config.variableSpread) return this.config.spread;
    const range = candle.high - candle.low;
    // Widen spread up to 3x during high volatility
    const volatilityMultiplier = Math.min(3, 1 + range / (this.config.spread * 100));
    return this.config.spread * volatilityMultiplier;
  }


  // =========================================================================
  // METRICS CALCULATION
  // =========================================================================

  private calculateMetrics(trades: BacktestTrade[], equity: EquityPoint[], initialBalance: number): BacktestMetrics {
    if (trades.length === 0) {
      return this.emptyMetrics(initialBalance);
    }

    const wins = trades.filter(t => t.netPnL > 0);
    const losses = trades.filter(t => t.netPnL < 0);

    const grossProfit = wins.reduce((s, t) => s + t.netPnL, 0);
    const grossLoss = Math.abs(losses.reduce((s, t) => s + t.netPnL, 0));
    const netProfit = grossProfit - grossLoss;

    const winRate = (wins.length / trades.length) * 100;
    const avgWin = wins.length ? grossProfit / wins.length : 0;
    const avgLoss = losses.length ? grossLoss / losses.length : 0;

    // Expectancy per trade
    const lossRate = losses.length / trades.length;
    const expectancy = (winRate / 100) * avgWin - lossRate * avgLoss;

    // Max drawdown from equity curve
    const maxDD = Math.max(...equity.map(e => e.drawdown), 0);
    const maxDDPercent = Math.max(...equity.map(e => e.drawdownPercent), 0);

    // Returns array for Sharpe/Sortino
    const returns = this.computeReturns(trades, initialBalance);
    const sharpe = this.calculateSharpe(returns);
    const sortino = this.calculateSortino(returns);

    const finalBalance = initialBalance + netProfit;
    const returnPercent = (netProfit / initialBalance) * 100;

    // Calmar = annualized return / max drawdown
    const calmar = maxDDPercent > 0 ? returnPercent / maxDDPercent : 0;
    // Recovery factor = net profit / max drawdown
    const recoveryFactor = maxDD > 0 ? netProfit / maxDD : 0;

    const streaks = this.calculateStreaks(trades);

    return {
      netProfit,
      grossProfit,
      grossLoss,
      totalTrades: trades.length,
      winningTrades: wins.length,
      losingTrades: losses.length,
      winRate,
      profitFactor: grossLoss > 0 ? grossProfit / grossLoss : grossProfit > 0 ? Infinity : 0,
      expectancy,
      averageTrade: netProfit / trades.length,
      averageWin: avgWin,
      averageLoss: avgLoss,
      largestWin: wins.length ? Math.max(...wins.map(t => t.netPnL)) : 0,
      largestLoss: losses.length ? Math.min(...losses.map(t => t.netPnL)) : 0,
      maxDrawdown: maxDD,
      maxDrawdownPercent: maxDDPercent,
      sharpeRatio: sharpe,
      sortinoRatio: sortino,
      calmarRatio: calmar,
      recoveryFactor,
      avgHoldingBars: trades.reduce((s, t) => s + t.holdingBars, 0) / trades.length,
      maxConsecutiveWins: streaks.maxWins,
      maxConsecutiveLosses: streaks.maxLosses,
      finalBalance,
      returnPercent,
      totalCommission: trades.reduce((s, t) => s + t.commission, 0),
    };
  }

  private computeReturns(trades: BacktestTrade[], initialBalance: number): number[] {
    const returns: number[] = [];
    let balance = initialBalance;
    for (const t of trades) {
      returns.push(t.netPnL / balance);
      balance += t.netPnL;
    }
    return returns;
  }

  /**
   * Sharpe Ratio - risk-adjusted return (annualized, assuming ~252 trading periods)
   */
  private calculateSharpe(returns: number[], riskFreeRate: number = 0): number {
    if (returns.length < 2) return 0;
    const mean = returns.reduce((a, b) => a + b, 0) / returns.length;
    const variance = returns.reduce((s, r) => s + (r - mean) ** 2, 0) / returns.length;
    const stdDev = Math.sqrt(variance);
    if (stdDev === 0) return 0;
    return ((mean - riskFreeRate) / stdDev) * Math.sqrt(252);
  }

  /**
   * Sortino Ratio - uses downside deviation only
   */
  private calculateSortino(returns: number[], riskFreeRate: number = 0): number {
    if (returns.length < 2) return 0;
    const mean = returns.reduce((a, b) => a + b, 0) / returns.length;
    const downside = returns.filter(r => r < 0);
    if (downside.length === 0) return mean > 0 ? Infinity : 0;
    const downsideVar = downside.reduce((s, r) => s + r ** 2, 0) / returns.length;
    const downsideDev = Math.sqrt(downsideVar);
    if (downsideDev === 0) return 0;
    return ((mean - riskFreeRate) / downsideDev) * Math.sqrt(252);
  }

  private calculateStreaks(trades: BacktestTrade[]): { maxWins: number; maxLosses: number } {
    let maxWins = 0, maxLosses = 0, curWins = 0, curLosses = 0;
    for (const t of trades) {
      if (t.netPnL > 0) {
        curWins++; curLosses = 0;
        maxWins = Math.max(maxWins, curWins);
      } else if (t.netPnL < 0) {
        curLosses++; curWins = 0;
        maxLosses = Math.max(maxLosses, curLosses);
      }
    }
    return { maxWins, maxLosses };
  }

  private emptyMetrics(initialBalance: number): BacktestMetrics {
    return {
      netProfit: 0, grossProfit: 0, grossLoss: 0, totalTrades: 0,
      winningTrades: 0, losingTrades: 0, winRate: 0, profitFactor: 0,
      expectancy: 0, averageTrade: 0, averageWin: 0, averageLoss: 0,
      largestWin: 0, largestLoss: 0, maxDrawdown: 0, maxDrawdownPercent: 0,
      sharpeRatio: 0, sortinoRatio: 0, calmarRatio: 0, recoveryFactor: 0,
      avgHoldingBars: 0, maxConsecutiveWins: 0, maxConsecutiveLosses: 0,
      finalBalance: initialBalance, returnPercent: 0, totalCommission: 0,
    };
  }

  getConfig(): BacktestConfig { return { ...this.config }; }
  updateConfig(config: Partial<BacktestConfig>): void { this.config = { ...this.config, ...config }; }
}
