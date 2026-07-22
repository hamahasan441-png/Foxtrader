// ============================================================================
// MONTE CARLO SIMULATION & WALK-FORWARD ANALYSIS
// Stress-test strategy robustness by resampling trade sequences and
// validating on out-of-sample data.
// ============================================================================

import { Candle, Timeframe } from '../core/types';
import { Backtester, BacktestTrade, BacktestResult, StrategyFunction, BacktestConfig } from './backtester';

export interface MonteCarloConfig {
  iterations: number;
  /** Confidence level for percentiles (e.g. 0.95) */
  confidenceLevel: number;
  /** Resample method: SHUFFLE trade order, or BOOTSTRAP with replacement */
  method: 'SHUFFLE' | 'BOOTSTRAP';
  initialBalance: number;
}

const DEFAULT_MC_CONFIG: MonteCarloConfig = {
  iterations: 1000,
  confidenceLevel: 0.95,
  method: 'BOOTSTRAP',
  initialBalance: 100000,
};

export interface MonteCarloResult {
  iterations: number;
  /** Distribution of final balances */
  finalBalances: number[];
  /** Distribution of max drawdowns (%) */
  maxDrawdowns: number[];
  /** Percentile stats */
  medianFinalBalance: number;
  worstCaseBalance: number;  // 5th percentile
  bestCaseBalance: number;   // 95th percentile
  medianMaxDrawdown: number;
  worstCaseDrawdown: number; // 95th percentile
  /** Probability of profit */
  probabilityOfProfit: number;
  /** Probability of ruin (balance dropping below threshold) */
  riskOfRuin: number;
  /** Confidence interval for return */
  returnCI: { lower: number; upper: number };
}

export class MonteCarloSimulator {
  private config: MonteCarloConfig;

  constructor(config: Partial<MonteCarloConfig> = {}) {
    this.config = { ...DEFAULT_MC_CONFIG, ...config };
  }

  /**
   * Run Monte Carlo simulation on a set of backtest trades.
   * Resamples the P&L sequence many times to build a distribution of outcomes.
   */
  simulate(trades: BacktestTrade[]): MonteCarloResult {
    const pnls = trades.map(t => t.netPnL);
    if (pnls.length === 0) {
      return this.emptyResult();
    }

    const finalBalances: number[] = [];
    const maxDrawdowns: number[] = [];
    const ruinThreshold = this.config.initialBalance * 0.5; // 50% loss = ruin
    let ruinCount = 0;

    for (let iter = 0; iter < this.config.iterations; iter++) {
      const sequence = this.config.method === 'SHUFFLE'
        ? this.shuffle(pnls)
        : this.bootstrap(pnls);

      let balance = this.config.initialBalance;
      let peak = balance;
      let maxDD = 0;
      let hitRuin = false;

      for (const pnl of sequence) {
        balance += pnl;
        peak = Math.max(peak, balance);
        const dd = ((peak - balance) / peak) * 100;
        maxDD = Math.max(maxDD, dd);
        if (balance <= ruinThreshold) hitRuin = true;
      }

      finalBalances.push(balance);
      maxDrawdowns.push(maxDD);
      if (hitRuin) ruinCount++;
    }

    finalBalances.sort((a, b) => a - b);
    maxDrawdowns.sort((a, b) => a - b);

    const profitable = finalBalances.filter(b => b > this.config.initialBalance).length;
    const lowerPct = (1 - this.config.confidenceLevel) / 2;
    const upperPct = 1 - lowerPct;

    return {
      iterations: this.config.iterations,
      finalBalances,
      maxDrawdowns,
      medianFinalBalance: this.percentile(finalBalances, 0.5),
      worstCaseBalance: this.percentile(finalBalances, 0.05),
      bestCaseBalance: this.percentile(finalBalances, 0.95),
      medianMaxDrawdown: this.percentile(maxDrawdowns, 0.5),
      worstCaseDrawdown: this.percentile(maxDrawdowns, 0.95),
      probabilityOfProfit: (profitable / this.config.iterations) * 100,
      riskOfRuin: (ruinCount / this.config.iterations) * 100,
      returnCI: {
        lower: ((this.percentile(finalBalances, lowerPct) - this.config.initialBalance) / this.config.initialBalance) * 100,
        upper: ((this.percentile(finalBalances, upperPct) - this.config.initialBalance) / this.config.initialBalance) * 100,
      },
    };
  }

  private shuffle(arr: number[]): number[] {
    const result = [...arr];
    for (let i = result.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
  }

  private bootstrap(arr: number[]): number[] {
    const result: number[] = [];
    for (let i = 0; i < arr.length; i++) {
      result.push(arr[Math.floor(Math.random() * arr.length)]);
    }
    return result;
  }

  private percentile(sorted: number[], p: number): number {
    if (sorted.length === 0) return 0;
    const idx = Math.floor(p * (sorted.length - 1));
    return sorted[idx];
  }

  private emptyResult(): MonteCarloResult {
    return {
      iterations: 0, finalBalances: [], maxDrawdowns: [],
      medianFinalBalance: this.config.initialBalance,
      worstCaseBalance: this.config.initialBalance,
      bestCaseBalance: this.config.initialBalance,
      medianMaxDrawdown: 0, worstCaseDrawdown: 0,
      probabilityOfProfit: 0, riskOfRuin: 0,
      returnCI: { lower: 0, upper: 0 },
    };
  }
}

// ============================================================================
// WALK-FORWARD ANALYSIS
// Splits data into in-sample (optimization) and out-of-sample (validation)
// windows that "walk forward" through time to test robustness.
// ============================================================================

export interface WalkForwardConfig {
  /** Number of bars in each in-sample (training) window */
  inSampleBars: number;
  /** Number of bars in each out-of-sample (test) window */
  outSampleBars: number;
  /** Anchored (expanding) or rolling window */
  anchored: boolean;
  backtestConfig: Partial<BacktestConfig>;
}

const DEFAULT_WF_CONFIG: WalkForwardConfig = {
  inSampleBars: 1000,
  outSampleBars: 250,
  anchored: false,
  backtestConfig: {},
};

export interface WalkForwardWindow {
  windowIndex: number;
  inSampleStart: number;
  inSampleEnd: number;
  outSampleStart: number;
  outSampleEnd: number;
  inSampleResult: BacktestResult;
  outSampleResult: BacktestResult;
  /** Walk-forward efficiency = OOS return / IS return */
  efficiency: number;
}

export interface WalkForwardResult {
  windows: WalkForwardWindow[];
  /** Average WF efficiency across all windows (>0.5 is robust) */
  avgEfficiency: number;
  /** Combined out-of-sample metrics */
  combinedOOSReturn: number;
  combinedOOSWinRate: number;
  /** Consistency: % of OOS windows that were profitable */
  consistency: number;
  robust: boolean;
}

export class WalkForwardAnalyzer {
  private config: WalkForwardConfig;
  private backtester: Backtester;

  constructor(config: Partial<WalkForwardConfig> = {}) {
    this.config = { ...DEFAULT_WF_CONFIG, ...config };
    this.backtester = new Backtester(this.config.backtestConfig);
  }

  /**
   * Run walk-forward analysis.
   * @param strategyFactory - Given in-sample candles, returns an optimized strategy.
   *                          If your strategy has no optimization, return the same strategy.
   */
  run(
    candles: Candle[],
    strategyFactory: (inSampleCandles: Candle[]) => StrategyFunction,
    symbol: string = 'UNKNOWN',
    timeframe: Timeframe = 'M15'
  ): WalkForwardResult {
    const windows: WalkForwardWindow[] = [];
    const { inSampleBars, outSampleBars, anchored } = this.config;

    let windowIndex = 0;
    let cursor = 0;

    while (cursor + inSampleBars + outSampleBars <= candles.length) {
      const inStart = anchored ? 0 : cursor;
      const inEnd = cursor + inSampleBars;
      const outStart = inEnd;
      const outEnd = outStart + outSampleBars;

      const inSampleCandles = candles.slice(inStart, inEnd);
      const outSampleCandles = candles.slice(outStart, outEnd);

      // Optimize strategy on in-sample
      const strategy = strategyFactory(inSampleCandles);

      // Test on both windows
      const inSampleResult = this.backtester.run(inSampleCandles, strategy, symbol, timeframe);
      const outSampleResult = this.backtester.run(outSampleCandles, strategy, symbol, timeframe);

      const efficiency = inSampleResult.metrics.returnPercent !== 0
        ? outSampleResult.metrics.returnPercent / inSampleResult.metrics.returnPercent
        : 0;

      windows.push({
        windowIndex: windowIndex++,
        inSampleStart: inStart, inSampleEnd: inEnd,
        outSampleStart: outStart, outSampleEnd: outEnd,
        inSampleResult, outSampleResult, efficiency,
      });

      cursor += outSampleBars; // Walk forward by OOS window size
    }

    return this.aggregate(windows);
  }

  private aggregate(windows: WalkForwardWindow[]): WalkForwardResult {
    if (windows.length === 0) {
      return { windows: [], avgEfficiency: 0, combinedOOSReturn: 0, combinedOOSWinRate: 0, consistency: 0, robust: false };
    }

    const avgEfficiency = windows.reduce((s, w) => s + w.efficiency, 0) / windows.length;
    const combinedOOSReturn = windows.reduce((s, w) => s + w.outSampleResult.metrics.returnPercent, 0);
    const combinedOOSWinRate = windows.reduce((s, w) => s + w.outSampleResult.metrics.winRate, 0) / windows.length;
    const profitableWindows = windows.filter(w => w.outSampleResult.metrics.netProfit > 0).length;
    const consistency = (profitableWindows / windows.length) * 100;

    // Robust if efficiency > 0.5 and consistency > 60%
    const robust = avgEfficiency > 0.5 && consistency > 60;

    return { windows, avgEfficiency, combinedOOSReturn, combinedOOSWinRate, consistency, robust };
  }
}
