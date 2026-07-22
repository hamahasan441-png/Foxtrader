// ============================================================================
// TECHNICAL INDICATORS - VWAP, EMA, ADX, RSI, Volume analysis
// Used by Confluence Engine and AI Probability Engine
// No look-ahead bias - all indicators use only past/current data
// ============================================================================

import { Candle } from '../core/types';

/**
 * Exponential Moving Average
 */
export function calculateEMA(candles: Candle[], period: number): number[] {
  const ema: number[] = new Array(candles.length).fill(0);
  if (candles.length === 0) return ema;

  const k = 2 / (period + 1);
  ema[0] = candles[0].close;

  for (let i = 1; i < candles.length; i++) {
    ema[i] = candles[i].close * k + ema[i - 1] * (1 - k);
  }
  return ema;
}

/**
 * Simple Moving Average
 */
export function calculateSMA(candles: Candle[], period: number): number[] {
  const sma: number[] = new Array(candles.length).fill(0);
  let sum = 0;
  for (let i = 0; i < candles.length; i++) {
    sum += candles[i].close;
    if (i >= period) sum -= candles[i - period].close;
    sma[i] = i >= period - 1 ? sum / period : sum / (i + 1);
  }
  return sma;
}

/**
 * VWAP - Volume Weighted Average Price (session-anchored)
 * Resets at the start of each UTC day
 */
export function calculateVWAP(candles: Candle[]): number[] {
  const vwap: number[] = new Array(candles.length).fill(0);
  let cumulativeTPV = 0; // Typical Price * Volume
  let cumulativeVolume = 0;
  let currentDay = -1;

  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];
    const day = new Date(c.timestamp).getUTCDate();

    // Reset at day boundary
    if (day !== currentDay) {
      cumulativeTPV = 0;
      cumulativeVolume = 0;
      currentDay = day;
    }

    const typicalPrice = (c.high + c.low + c.close) / 3;
    const volume = c.volume || 1;
    cumulativeTPV += typicalPrice * volume;
    cumulativeVolume += volume;

    vwap[i] = cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : typicalPrice;
  }
  return vwap;
}

/**
 * ADX - Average Directional Index (trend strength)
 * Returns { adx, plusDI, minusDI } arrays
 */
export function calculateADX(candles: Candle[], period: number = 14): {
  adx: number[];
  plusDI: number[];
  minusDI: number[];
} {
  const len = candles.length;
  const adx = new Array(len).fill(0);
  const plusDI = new Array(len).fill(0);
  const minusDI = new Array(len).fill(0);

  if (len < period * 2) return { adx, plusDI, minusDI };

  const tr: number[] = new Array(len).fill(0);
  const plusDM: number[] = new Array(len).fill(0);
  const minusDM: number[] = new Array(len).fill(0);

  for (let i = 1; i < len; i++) {
    const high = candles[i].high;
    const low = candles[i].low;
    const prevHigh = candles[i - 1].high;
    const prevLow = candles[i - 1].low;
    const prevClose = candles[i - 1].close;

    tr[i] = Math.max(high - low, Math.abs(high - prevClose), Math.abs(low - prevClose));

    const upMove = high - prevHigh;
    const downMove = prevLow - low;
    plusDM[i] = upMove > downMove && upMove > 0 ? upMove : 0;
    minusDM[i] = downMove > upMove && downMove > 0 ? downMove : 0;
  }

  // Wilder's smoothing
  let smoothedTR = 0, smoothedPlusDM = 0, smoothedMinusDM = 0;
  for (let i = 1; i <= period; i++) {
    smoothedTR += tr[i];
    smoothedPlusDM += plusDM[i];
    smoothedMinusDM += minusDM[i];
  }

  const dx: number[] = new Array(len).fill(0);
  for (let i = period; i < len; i++) {
    if (i > period) {
      smoothedTR = smoothedTR - smoothedTR / period + tr[i];
      smoothedPlusDM = smoothedPlusDM - smoothedPlusDM / period + plusDM[i];
      smoothedMinusDM = smoothedMinusDM - smoothedMinusDM / period + minusDM[i];
    }

    plusDI[i] = smoothedTR > 0 ? (smoothedPlusDM / smoothedTR) * 100 : 0;
    minusDI[i] = smoothedTR > 0 ? (smoothedMinusDM / smoothedTR) * 100 : 0;

    const diSum = plusDI[i] + minusDI[i];
    dx[i] = diSum > 0 ? (Math.abs(plusDI[i] - minusDI[i]) / diSum) * 100 : 0;
  }

  // ADX = smoothed DX
  let adxSum = 0;
  for (let i = period; i < period * 2 && i < len; i++) adxSum += dx[i];
  if (period * 2 - 1 < len) adx[period * 2 - 1] = adxSum / period;

  for (let i = period * 2; i < len; i++) {
    adx[i] = (adx[i - 1] * (period - 1) + dx[i]) / period;
  }

  return { adx, plusDI, minusDI };
}

/**
 * RSI - Relative Strength Index (momentum)
 */
export function calculateRSI(candles: Candle[], period: number = 14): number[] {
  const rsi = new Array(candles.length).fill(50);
  if (candles.length < period + 1) return rsi;

  let avgGain = 0, avgLoss = 0;
  for (let i = 1; i <= period; i++) {
    const change = candles[i].close - candles[i - 1].close;
    if (change > 0) avgGain += change;
    else avgLoss += Math.abs(change);
  }
  avgGain /= period;
  avgLoss /= period;

  for (let i = period + 1; i < candles.length; i++) {
    const change = candles[i].close - candles[i - 1].close;
    const gain = change > 0 ? change : 0;
    const loss = change < 0 ? Math.abs(change) : 0;

    avgGain = (avgGain * (period - 1) + gain) / period;
    avgLoss = (avgLoss * (period - 1) + loss) / period;

    const rs = avgLoss > 0 ? avgGain / avgLoss : 100;
    rsi[i] = 100 - 100 / (1 + rs);
  }
  return rsi;
}

/**
 * MACD - Moving Average Convergence Divergence
 */
export function calculateMACD(candles: Candle[], fast = 12, slow = 26, signal = 9): {
  macd: number[];
  signal: number[];
  histogram: number[];
} {
  const emaFast = calculateEMA(candles, fast);
  const emaSlow = calculateEMA(candles, slow);
  const macd = candles.map((_, i) => emaFast[i] - emaSlow[i]);

  // Signal line = EMA of MACD
  const signalLine = new Array(candles.length).fill(0);
  const k = 2 / (signal + 1);
  signalLine[0] = macd[0];
  for (let i = 1; i < macd.length; i++) {
    signalLine[i] = macd[i] * k + signalLine[i - 1] * (1 - k);
  }

  const histogram = macd.map((m, i) => m - signalLine[i]);
  return { macd, signal: signalLine, histogram };
}

/**
 * Volume analysis - relative volume vs moving average
 */
export function calculateRelativeVolume(candles: Candle[], period: number = 20): number[] {
  const relVol = new Array(candles.length).fill(1);
  for (let i = 0; i < candles.length; i++) {
    const start = Math.max(0, i - period);
    let sum = 0, count = 0;
    for (let j = start; j < i; j++) {
      sum += candles[j].volume;
      count++;
    }
    const avgVol = count > 0 ? sum / count : candles[i].volume;
    relVol[i] = avgVol > 0 ? candles[i].volume / avgVol : 1;
  }
  return relVol;
}

/**
 * Momentum - rate of change
 */
export function calculateMomentum(candles: Candle[], period: number = 10): number[] {
  const momentum = new Array(candles.length).fill(0);
  for (let i = period; i < candles.length; i++) {
    momentum[i] = ((candles[i].close - candles[i - period].close) / candles[i - period].close) * 100;
  }
  return momentum;
}
