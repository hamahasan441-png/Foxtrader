// ============================================================================
// HEATMAPS & STRENGTH METERS
// Heatmaps: Forex, Crypto, Stock, Sector | Correlation Matrix
// Strength Meters: Currency, Crypto, Trend, Momentum, Volatility, Sentiment
// ============================================================================

import { Candle } from '../core/types';
import {
  calculateEMA, calculateADX, calculateRSI, calculateMomentum, calculateRelativeVolume,
} from '../indicators/technical';
import { calculateATR } from '../core/utils';

export type HeatmapType = 'FOREX' | 'CRYPTO' | 'STOCK' | 'SECTOR';

export interface HeatmapCell {
  symbol: string;
  changePercent: number;   // % change over the period
  value: number;           // normalized -100..100 for coloring
  volume: number;
  volatility: number;
  trend: 'UP' | 'DOWN' | 'FLAT';
  label: string;
}

export interface Heatmap {
  type: HeatmapType;
  cells: HeatmapCell[];
  period: string;
  generatedAt: number;
  strongest: HeatmapCell | null;
  weakest: HeatmapCell | null;
}

export interface CorrelationMatrix {
  symbols: string[];
  matrix: number[][]; // matrix[i][j] = correlation between symbols[i] and symbols[j]
  generatedAt: number;
  /** Strongly correlated pairs (|corr| > threshold) */
  strongPairs: { a: string; b: string; correlation: number }[];
}

export type StrengthType = 'CURRENCY' | 'CRYPTO' | 'TREND' | 'MOMENTUM' | 'VOLATILITY' | 'SENTIMENT';

export interface StrengthReading {
  name: string;
  strength: number;   // 0-100
  rank: number;
  direction: 'STRONG' | 'WEAK' | 'NEUTRAL';
  change: number;     // change vs previous reading
}

export interface StrengthMeter {
  type: StrengthType;
  readings: StrengthReading[];
  generatedAt: number;
}

export class HeatmapEngine {
  // =========================================================================
  // HEATMAPS
  // =========================================================================

  /**
   * Build a heatmap from symbol -> candles map
   */
  buildHeatmap(type: HeatmapType, data: Map<string, Candle[]>, lookbackBars: number = 24): Heatmap {
    const cells: HeatmapCell[] = [];

    for (const [symbol, candles] of data) {
      if (candles.length < 2) continue;
      const start = Math.max(0, candles.length - lookbackBars);
      const startPrice = candles[start].close;
      const endPrice = candles[candles.length - 1].close;
      const changePercent = ((endPrice - startPrice) / startPrice) * 100;

      const recent = candles.slice(start);
      const volume = recent.reduce((s, c) => s + c.volume, 0);
      const atr = calculateATR(candles, 14);
      const volatility = (atr[atr.length - 1] / endPrice) * 100;

      cells.push({
        symbol,
        changePercent,
        value: Math.max(-100, Math.min(100, changePercent * 10)),
        volume,
        volatility,
        trend: changePercent > 0.1 ? 'UP' : changePercent < -0.1 ? 'DOWN' : 'FLAT',
        label: `${symbol} ${changePercent >= 0 ? '+' : ''}${changePercent.toFixed(2)}%`,
      });
    }

    cells.sort((a, b) => b.changePercent - a.changePercent);

    return {
      type,
      cells,
      period: `${lookbackBars} bars`,
      generatedAt: Date.now(),
      strongest: cells[0] || null,
      weakest: cells[cells.length - 1] || null,
    };
  }

  // =========================================================================
  // CORRELATION MATRIX
  // =========================================================================

  /**
   * Build a correlation matrix between all symbols
   */
  buildCorrelationMatrix(data: Map<string, Candle[]>, lookback: number = 100, threshold: number = 0.7): CorrelationMatrix {
    const symbols = Array.from(data.keys());
    const returns = new Map<string, number[]>();

    // Compute returns for each symbol
    for (const symbol of symbols) {
      const candles = data.get(symbol)!;
      const len = Math.min(lookback, candles.length);
      const start = candles.length - len;
      const rets: number[] = [];
      for (let i = start + 1; i < candles.length; i++) {
        rets.push((candles[i].close - candles[i - 1].close) / candles[i - 1].close);
      }
      returns.set(symbol, rets);
    }

    const n = symbols.length;
    const matrix: number[][] = Array.from({ length: n }, () => new Array(n).fill(0));
    const strongPairs: { a: string; b: string; correlation: number }[] = [];

    for (let i = 0; i < n; i++) {
      for (let j = i; j < n; j++) {
        const corr = i === j ? 1 : this.pearson(returns.get(symbols[i])!, returns.get(symbols[j])!);
        matrix[i][j] = corr;
        matrix[j][i] = corr;
        if (i !== j && Math.abs(corr) >= threshold) {
          strongPairs.push({ a: symbols[i], b: symbols[j], correlation: corr });
        }
      }
    }

    strongPairs.sort((a, b) => Math.abs(b.correlation) - Math.abs(a.correlation));

    return { symbols, matrix, generatedAt: Date.now(), strongPairs };
  }

  private pearson(a: number[], b: number[]): number {
    const n = Math.min(a.length, b.length);
    if (n < 2) return 0;
    const aSlice = a.slice(-n), bSlice = b.slice(-n);
    const meanA = aSlice.reduce((s, v) => s + v, 0) / n;
    const meanB = bSlice.reduce((s, v) => s + v, 0) / n;
    let cov = 0, varA = 0, varB = 0;
    for (let i = 0; i < n; i++) {
      const da = aSlice[i] - meanA, db = bSlice[i] - meanB;
      cov += da * db; varA += da * da; varB += db * db;
    }
    const denom = Math.sqrt(varA * varB);
    return denom === 0 ? 0 : cov / denom;
  }

  // =========================================================================
  // CURRENCY STRENGTH METER
  // =========================================================================

  /**
   * Calculate individual currency strength from forex pairs.
   * Decomposes each pair's move into its two constituent currencies.
   */
  calculateCurrencyStrength(pairData: Map<string, Candle[]>, lookback: number = 24): StrengthMeter {
    const currencyScores = new Map<string, { sum: number; count: number }>();

    for (const [pair, candles] of pairData) {
      if (candles.length < 2 || pair.length < 6) continue;
      const base = pair.slice(0, 3);
      const quote = pair.slice(3, 6);

      const start = Math.max(0, candles.length - lookback);
      const change = ((candles[candles.length - 1].close - candles[start].close) / candles[start].close) * 100;

      // Base currency gains when pair rises; quote currency gains when pair falls
      this.addScore(currencyScores, base, change);
      this.addScore(currencyScores, quote, -change);
    }

    const readings = this.scoresToReadings(currencyScores);
    return { type: 'CURRENCY', readings, generatedAt: Date.now() };
  }

  /**
   * Calculate crypto strength (vs USD baseline)
   */
  calculateCryptoStrength(data: Map<string, Candle[]>, lookback: number = 24): StrengthMeter {
    const scores = new Map<string, { sum: number; count: number }>();
    for (const [symbol, candles] of data) {
      if (candles.length < 2) continue;
      const start = Math.max(0, candles.length - lookback);
      const change = ((candles[candles.length - 1].close - candles[start].close) / candles[start].close) * 100;
      const asset = symbol.replace(/USDT?$/, '').replace(/USD$/, '');
      this.addScore(scores, asset, change);
    }
    return { type: 'CRYPTO', readings: this.scoresToReadings(scores), generatedAt: Date.now() };
  }

  private addScore(map: Map<string, { sum: number; count: number }>, key: string, value: number): void {
    const s = map.get(key) || { sum: 0, count: 0 };
    s.sum += value; s.count++;
    map.set(key, s);
  }

  private scoresToReadings(scores: Map<string, { sum: number; count: number }>): StrengthReading[] {
    const raw = Array.from(scores.entries()).map(([name, { sum, count }]) => ({
      name, avg: count > 0 ? sum / count : 0,
    }));

    // Normalize to 0-100
    const avgs = raw.map(r => r.avg);
    const min = Math.min(...avgs, 0), max = Math.max(...avgs, 0);
    const range = max - min || 1;

    const readings: StrengthReading[] = raw.map(r => {
      const strength = ((r.avg - min) / range) * 100;
      return {
        name: r.name,
        strength: Math.round(strength),
        rank: 0,
        direction: strength >= 66 ? 'STRONG' : strength <= 33 ? 'WEAK' : 'NEUTRAL',
        change: 0,
      };
    });

    readings.sort((a, b) => b.strength - a.strength);
    readings.forEach((r, i) => (r.rank = i + 1));
    return readings;
  }

  // =========================================================================
  // TREND / MOMENTUM / VOLATILITY / SENTIMENT STRENGTH
  // =========================================================================

  /**
   * Trend strength across symbols (based on ADX + EMA alignment)
   */
  calculateTrendStrength(data: Map<string, Candle[]>): StrengthMeter {
    const readings: StrengthReading[] = [];
    for (const [symbol, candles] of data) {
      if (candles.length < 50) continue;
      const { adx } = calculateADX(candles);
      const ema20 = calculateEMA(candles, 20);
      const ema50 = calculateEMA(candles, 50);
      const last = candles.length - 1;

      const adxVal = adx[last];
      const aligned = Math.abs(ema20[last] - ema50[last]) / candles[last].close * 100;
      const strength = Math.min(100, adxVal * 1.5 + aligned * 10);

      readings.push({
        name: symbol,
        strength: Math.round(strength),
        rank: 0,
        direction: strength >= 66 ? 'STRONG' : strength <= 33 ? 'WEAK' : 'NEUTRAL',
        change: 0,
      });
    }
    readings.sort((a, b) => b.strength - a.strength);
    readings.forEach((r, i) => (r.rank = i + 1));
    return { type: 'TREND', readings, generatedAt: Date.now() };
  }

  /**
   * Momentum strength (RSI + rate of change)
   */
  calculateMomentumStrength(data: Map<string, Candle[]>): StrengthMeter {
    const readings: StrengthReading[] = [];
    for (const [symbol, candles] of data) {
      if (candles.length < 20) continue;
      const rsi = calculateRSI(candles);
      const mom = calculateMomentum(candles);
      const last = candles.length - 1;
      // Combine RSI distance from 50 and momentum
      const strength = Math.min(100, Math.abs(rsi[last] - 50) * 1.5 + Math.abs(mom[last]) * 5);
      readings.push({
        name: symbol, strength: Math.round(strength), rank: 0,
        direction: mom[last] > 0 ? 'STRONG' : mom[last] < 0 ? 'WEAK' : 'NEUTRAL', change: 0,
      });
    }
    readings.sort((a, b) => b.strength - a.strength);
    readings.forEach((r, i) => (r.rank = i + 1));
    return { type: 'MOMENTUM', readings, generatedAt: Date.now() };
  }

  /**
   * Volatility strength (ATR relative to price)
   */
  calculateVolatilityStrength(data: Map<string, Candle[]>): StrengthMeter {
    const readings: StrengthReading[] = [];
    for (const [symbol, candles] of data) {
      if (candles.length < 15) continue;
      const atr = calculateATR(candles, 14);
      const last = candles.length - 1;
      const volPct = (atr[last] / candles[last].close) * 100;
      const strength = Math.min(100, volPct * 40); // Scale for typical FX vol
      readings.push({
        name: symbol, strength: Math.round(strength), rank: 0,
        direction: strength >= 66 ? 'STRONG' : strength <= 33 ? 'WEAK' : 'NEUTRAL', change: 0,
      });
    }
    readings.sort((a, b) => b.strength - a.strength);
    readings.forEach((r, i) => (r.rank = i + 1));
    return { type: 'VOLATILITY', readings, generatedAt: Date.now() };
  }

  /**
   * Sentiment strength (composite: momentum direction + volume + candle bias)
   */
  calculateSentimentStrength(data: Map<string, Candle[]>): StrengthMeter {
    const readings: StrengthReading[] = [];
    for (const [symbol, candles] of data) {
      if (candles.length < 20) continue;
      const last = candles.length - 1;
      const recent = candles.slice(-20);

      // Bullish candle ratio
      const bullish = recent.filter(c => c.close > c.open).length;
      const bullRatio = bullish / recent.length;

      // Close position within range (buying/selling pressure)
      const closePositions = recent.map(c => {
        const range = c.high - c.low;
        return range > 0 ? (c.close - c.low) / range : 0.5;
      });
      const avgClosePos = closePositions.reduce((a, b) => a + b, 0) / closePositions.length;

      // Relative volume confirmation
      const relVol = calculateRelativeVolume(candles);
      const volConfirm = Math.min(1.5, relVol[last]);

      const sentiment = ((bullRatio * 0.4 + avgClosePos * 0.4 + (volConfirm / 1.5) * 0.2)) * 100;

      readings.push({
        name: symbol, strength: Math.round(sentiment), rank: 0,
        direction: sentiment >= 60 ? 'STRONG' : sentiment <= 40 ? 'WEAK' : 'NEUTRAL', change: 0,
      });
    }
    readings.sort((a, b) => b.strength - a.strength);
    readings.forEach((r, i) => (r.rank = i + 1));
    return { type: 'SENTIMENT', readings, generatedAt: Date.now() };
  }

  /**
   * Build all strength meters at once
   */
  buildAllStrengthMeters(forexData: Map<string, Candle[]>, cryptoData?: Map<string, Candle[]>): StrengthMeter[] {
    const meters: StrengthMeter[] = [
      this.calculateCurrencyStrength(forexData),
      this.calculateTrendStrength(forexData),
      this.calculateMomentumStrength(forexData),
      this.calculateVolatilityStrength(forexData),
      this.calculateSentimentStrength(forexData),
    ];
    if (cryptoData) meters.push(this.calculateCryptoStrength(cryptoData));
    return meters;
  }
}

// Sector mapping for stock heatmaps
export const STOCK_SECTORS: Record<string, string> = {
  AAPL: 'Technology', MSFT: 'Technology', GOOGL: 'Technology', NVDA: 'Technology', META: 'Technology',
  JPM: 'Financials', BAC: 'Financials', GS: 'Financials', WFC: 'Financials',
  XOM: 'Energy', CVX: 'Energy', COP: 'Energy',
  JNJ: 'Healthcare', PFE: 'Healthcare', UNH: 'Healthcare',
  AMZN: 'Consumer', TSLA: 'Consumer', WMT: 'Consumer', HD: 'Consumer',
  BA: 'Industrials', CAT: 'Industrials', GE: 'Industrials',
};


// ============================================================================
// SECTOR HEATMAP HELPER - aggregates stock performance by sector
// ============================================================================

export interface SectorPerformance {
  sector: string;
  avgChangePercent: number;
  symbolCount: number;
  strongest: string;
  weakest: string;
}

export function buildSectorHeatmap(data: Map<string, Candle[]>, lookbackBars: number = 24): SectorPerformance[] {
  const sectorMap = new Map<string, { changes: { symbol: string; change: number }[] }>();

  for (const [symbol, candles] of data) {
    if (candles.length < 2) continue;
    const sector = STOCK_SECTORS[symbol] || 'Other';
    const start = Math.max(0, candles.length - lookbackBars);
    const change = ((candles[candles.length - 1].close - candles[start].close) / candles[start].close) * 100;

    const s = sectorMap.get(sector) || { changes: [] };
    s.changes.push({ symbol, change });
    sectorMap.set(sector, s);
  }

  const result: SectorPerformance[] = [];
  for (const [sector, { changes }] of sectorMap) {
    const avg = changes.reduce((s, c) => s + c.change, 0) / changes.length;
    const sorted = [...changes].sort((a, b) => b.change - a.change);
    result.push({
      sector,
      avgChangePercent: avg,
      symbolCount: changes.length,
      strongest: sorted[0]?.symbol || '',
      weakest: sorted[sorted.length - 1]?.symbol || '',
    });
  }

  return result.sort((a, b) => b.avgChangePercent - a.avgChangePercent);
}
