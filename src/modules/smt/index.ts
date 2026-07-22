// ============================================================================
// SMT (SMART MONEY TECHNIQUE) MODULE
// SMT Divergence, Multi-symbol Scanner, Correlation Scanner,
// Multi-timeframe SMT, Auto Detection
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  SMTDivergence,
  SMTScanResult,
  Direction,
  Timeframe,
  SwingPoint,
} from '../../core/types';
import { findSwingPoints } from '../../core/utils';
import { TradingEventBus } from '../../core/event-bus';

export interface SMTConfig {
  correlationPairs: [string, string][];
  correlationThreshold: number; // Min correlation to consider
  divergenceLookback: number; // Bars to look back for swings
  swingLeftBars: number;
  swingRightBars: number;
  minDivergenceStrength: number; // 0-100
  autoDetectPairs: boolean;
  timeframes: Timeframe[];
}

const DEFAULT_CONFIG: SMTConfig = {
  correlationPairs: [
    ['EURUSD', 'GBPUSD'],
    ['EURUSD', 'DXY'],
    ['ES', 'NQ'],
    ['XAUUSD', 'DXY'],
    ['GBPUSD', 'EURGBP'],
    ['USDJPY', 'DXY'],
    ['AUDUSD', 'NZDUSD'],
  ],
  correlationThreshold: 0.7,
  divergenceLookback: 50,
  swingLeftBars: 3,
  swingRightBars: 3,
  minDivergenceStrength: 50,
  autoDetectPairs: true,
  timeframes: ['M15', 'H1', 'H4', 'D1'],
};


export class SMTAnalyzer {
  private config: SMTConfig;
  private eventBus?: TradingEventBus;
  private divergences: SMTDivergence[] = [];
  private correlationCache: Map<string, number> = new Map();

  constructor(config: Partial<SMTConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Detect SMT Divergence between two symbols
   * SMT occurs when correlated pairs make opposing swing structures
   */
  detectDivergence(
    symbol1: string,
    symbol2: string,
    candles1: Candle[],
    candles2: Candle[],
    timeframe: Timeframe
  ): SMTDivergence[] {
    const divergences: SMTDivergence[] = [];

    if (candles1.length < 10 || candles2.length < 10) return divergences;

    // Find swing points for both symbols
    const swings1 = findSwingPoints(candles1, this.config.swingLeftBars, this.config.swingRightBars);
    const swings2 = findSwingPoints(candles2, this.config.swingLeftBars, this.config.swingRightBars);

    // Align swings by timestamp proximity
    const highs1 = swings1.filter(s => s.type === 'HIGH');
    const highs2 = swings2.filter(s => s.type === 'HIGH');
    const lows1 = swings1.filter(s => s.type === 'LOW');
    const lows2 = swings2.filter(s => s.type === 'LOW');

    // Check for bullish SMT divergence at lows
    // Symbol1 makes lower low, Symbol2 makes higher low (or vice versa)
    for (let i = 1; i < lows1.length; i++) {
      const currentLow1 = lows1[i];
      const previousLow1 = lows1[i - 1];

      // Find corresponding low in symbol2 near same timestamp
      const matchingLow2Current = this.findNearestSwing(lows2, currentLow1.timestamp);
      const matchingLow2Previous = this.findNearestSwing(lows2, previousLow1.timestamp);

      if (!matchingLow2Current || !matchingLow2Previous) continue;

      // Symbol1 lower low + Symbol2 higher low = Bullish SMT
      if (currentLow1.price < previousLow1.price &&
          matchingLow2Current.price > matchingLow2Previous.price) {
        const strength = this.calculateDivergenceStrength(
          currentLow1, previousLow1, matchingLow2Current, matchingLow2Previous
        );

        if (strength >= this.config.minDivergenceStrength) {
          divergences.push({
            symbol1,
            symbol2,
            direction: 'BULLISH',
            timestamp: currentLow1.timestamp,
            timeframe,
            symbol1Action: 'LOWER_LOW',
            symbol2Action: 'HIGHER_HIGH', // Higher low relative
            strength,
            priceLevel1: currentLow1.price,
            priceLevel2: matchingLow2Current.price,
          });
        }
      }

      // Symbol1 higher low + Symbol2 lower low = Bearish SMT (on the symbol2 side)
      if (currentLow1.price > previousLow1.price &&
          matchingLow2Current.price < matchingLow2Previous.price) {
        const strength = this.calculateDivergenceStrength(
          currentLow1, previousLow1, matchingLow2Current, matchingLow2Previous
        );

        if (strength >= this.config.minDivergenceStrength) {
          divergences.push({
            symbol1,
            symbol2,
            direction: 'BULLISH', // Bullish for symbol1
            timestamp: currentLow1.timestamp,
            timeframe,
            symbol1Action: 'HIGHER_HIGH',
            symbol2Action: 'LOWER_LOW',
            strength,
            priceLevel1: currentLow1.price,
            priceLevel2: matchingLow2Current.price,
          });
        }
      }
    }

    // Check for bearish SMT divergence at highs
    for (let i = 1; i < highs1.length; i++) {
      const currentHigh1 = highs1[i];
      const previousHigh1 = highs1[i - 1];

      const matchingHigh2Current = this.findNearestSwing(highs2, currentHigh1.timestamp);
      const matchingHigh2Previous = this.findNearestSwing(highs2, previousHigh1.timestamp);

      if (!matchingHigh2Current || !matchingHigh2Previous) continue;

      // Symbol1 higher high + Symbol2 lower high = Bearish SMT
      if (currentHigh1.price > previousHigh1.price &&
          matchingHigh2Current.price < matchingHigh2Previous.price) {
        const strength = this.calculateDivergenceStrength(
          currentHigh1, previousHigh1, matchingHigh2Current, matchingHigh2Previous
        );

        if (strength >= this.config.minDivergenceStrength) {
          divergences.push({
            symbol1,
            symbol2,
            direction: 'BEARISH',
            timestamp: currentHigh1.timestamp,
            timeframe,
            symbol1Action: 'HIGHER_HIGH',
            symbol2Action: 'LOWER_LOW',
            strength,
            priceLevel1: currentHigh1.price,
            priceLevel2: matchingHigh2Current.price,
          });
        }
      }
    }

    this.divergences.push(...divergences);
    for (const div of divergences) {
      this.eventBus?.emit({ type: 'SMT_DIVERGENCE', data: div });
    }

    return divergences;
  }


  /**
   * Multi-symbol SMT Scanner
   * Scans all configured correlation pairs for divergences
   */
  scanAllPairs(
    dataMap: Map<string, Candle[]>,
    timeframe: Timeframe
  ): SMTScanResult {
    const allDivergences: SMTDivergence[] = [];

    for (const [sym1, sym2] of this.config.correlationPairs) {
      const candles1 = dataMap.get(sym1);
      const candles2 = dataMap.get(sym2);

      if (!candles1 || !candles2) continue;

      const divs = this.detectDivergence(sym1, sym2, candles1, candles2, timeframe);
      allDivergences.push(...divs);
    }

    // Auto-detect additional correlated pairs
    if (this.config.autoDetectPairs) {
      const symbols = Array.from(dataMap.keys());
      for (let i = 0; i < symbols.length; i++) {
        for (let j = i + 1; j < symbols.length; j++) {
          const pair = `${symbols[i]}_${symbols[j]}`;
          if (this.correlationCache.has(pair)) continue;

          const corr = this.calculateCorrelation(
            dataMap.get(symbols[i])!,
            dataMap.get(symbols[j])!
          );
          this.correlationCache.set(pair, corr);

          if (Math.abs(corr) >= this.config.correlationThreshold) {
            const divs = this.detectDivergence(
              symbols[i], symbols[j],
              dataMap.get(symbols[i])!,
              dataMap.get(symbols[j])!,
              timeframe
            );
            allDivergences.push(...divs);
          }
        }
      }
    }

    return {
      divergences: allDivergences,
      correlationCoefficient: 0,
      symbols: Array.from(dataMap.keys()),
      timeframe,
      scanTimestamp: Date.now(),
    };
  }

  /**
   * Multi-timeframe SMT analysis
   * Checks for SMT confluence across multiple timeframes
   */
  multiTimeframeSMT(
    symbol1: string,
    symbol2: string,
    dataByTimeframe: Map<Timeframe, { candles1: Candle[]; candles2: Candle[] }>
  ): SMTDivergence[] {
    const mtfDivergences: SMTDivergence[] = [];

    for (const timeframe of this.config.timeframes) {
      const data = dataByTimeframe.get(timeframe);
      if (!data) continue;

      const divs = this.detectDivergence(
        symbol1, symbol2, data.candles1, data.candles2, timeframe
      );
      mtfDivergences.push(...divs);
    }

    // Find confluent divergences (same direction on multiple timeframes)
    const confluent = mtfDivergences.filter(div => {
      const sameDirectionCount = mtfDivergences.filter(
        d => d.direction === div.direction &&
             d.symbol1 === div.symbol1 &&
             d.symbol2 === div.symbol2
      ).length;
      return sameDirectionCount >= 2; // At least 2 timeframes agree
    });

    // Boost strength for confluent signals
    for (const div of confluent) {
      div.strength = Math.min(100, div.strength + 15);
    }

    return confluent;
  }

  /**
   * Calculate Pearson correlation coefficient between two price series
   */
  calculateCorrelation(candles1: Candle[], candles2: Candle[]): number {
    const len = Math.min(candles1.length, candles2.length, this.config.divergenceLookback);
    if (len < 10) return 0;

    const returns1: number[] = [];
    const returns2: number[] = [];

    const start1 = candles1.length - len;
    const start2 = candles2.length - len;

    for (let i = 1; i < len; i++) {
      returns1.push((candles1[start1 + i].close - candles1[start1 + i - 1].close) / candles1[start1 + i - 1].close);
      returns2.push((candles2[start2 + i].close - candles2[start2 + i - 1].close) / candles2[start2 + i - 1].close);
    }

    const n = returns1.length;
    if (n === 0) return 0;

    const mean1 = returns1.reduce((a, b) => a + b, 0) / n;
    const mean2 = returns2.reduce((a, b) => a + b, 0) / n;

    let cov = 0, var1 = 0, var2 = 0;
    for (let i = 0; i < n; i++) {
      const d1 = returns1[i] - mean1;
      const d2 = returns2[i] - mean2;
      cov += d1 * d2;
      var1 += d1 * d1;
      var2 += d2 * d2;
    }

    const denom = Math.sqrt(var1 * var2);
    return denom === 0 ? 0 : cov / denom;
  }

  /**
   * Find nearest swing to a given timestamp
   */
  private findNearestSwing(swings: SwingPoint[], timestamp: number): SwingPoint | null {
    if (swings.length === 0) return null;

    let nearest = swings[0];
    let minDist = Math.abs(swings[0].timestamp - timestamp);

    for (const swing of swings) {
      const dist = Math.abs(swing.timestamp - timestamp);
      if (dist < minDist) {
        minDist = dist;
        nearest = swing;
      }
    }

    // Only return if reasonably close (within 5 bars equivalent)
    const maxDist = 5 * 3600000; // 5 hours as rough max
    return minDist <= maxDist ? nearest : null;
  }

  /**
   * Calculate divergence strength
   */
  private calculateDivergenceStrength(
    current1: SwingPoint,
    previous1: SwingPoint,
    current2: SwingPoint,
    previous2: SwingPoint
  ): number {
    // Strength based on how divergent the moves are
    const move1 = Math.abs(current1.price - previous1.price) / previous1.price;
    const move2 = Math.abs(current2.price - previous2.price) / previous2.price;

    // Divergence ratio: how different the moves are
    const totalMove = move1 + move2;
    if (totalMove === 0) return 0;

    const divergenceRatio = Math.abs(move1 - move2) / totalMove;
    return Math.min(100, Math.round(divergenceRatio * 200 + 30));
  }

  /** Get all detected divergences */
  getDivergences(): SMTDivergence[] {
    return [...this.divergences];
  }

  /** Get recent divergences */
  getRecentDivergences(lookbackMs: number = 86400000): SMTDivergence[] {
    const cutoff = Date.now() - lookbackMs;
    return this.divergences.filter(d => d.timestamp >= cutoff);
  }

  /** Get correlation for a pair */
  getCorrelation(symbol1: string, symbol2: string): number | null {
    return this.correlationCache.get(`${symbol1}_${symbol2}`) ??
           this.correlationCache.get(`${symbol2}_${symbol1}`) ?? null;
  }

  reset(): void {
    this.divergences = [];
    this.correlationCache.clear();
  }
}
