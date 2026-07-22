// ============================================================================
// UTILITY FUNCTIONS - Performance-optimized helpers
// ============================================================================

import { Candle, SwingPoint, PriceZone, Timeframe } from './types';

/**
 * Calculate ATR (Average True Range) - No look-ahead bias
 */
export function calculateATR(candles: Candle[], period: number = 14): number[] {
  const atr: number[] = new Array(candles.length).fill(0);
  if (candles.length < 2) return atr;

  // First TR
  atr[0] = candles[0].high - candles[0].low;

  for (let i = 1; i < candles.length; i++) {
    const tr = Math.max(
      candles[i].high - candles[i].low,
      Math.abs(candles[i].high - candles[i - 1].close),
      Math.abs(candles[i].low - candles[i - 1].close)
    );

    if (i < period) {
      atr[i] = (atr[i - 1] * i + tr) / (i + 1);
    } else {
      atr[i] = (atr[i - 1] * (period - 1) + tr) / period;
    }
  }

  return atr;
}

/**
 * Identify swing points using fractal logic - No look-ahead bias
 * Uses left-confirmed swings only (lookback completed)
 */
export function findSwingPoints(
  candles: Candle[],
  leftBars: number = 3,
  rightBars: number = 3
): SwingPoint[] {
  const swings: SwingPoint[] = [];
  const totalBars = candles.length;

  for (let i = leftBars; i < totalBars - rightBars; i++) {
    let isSwingHigh = true;
    let isSwingLow = true;

    for (let j = 1; j <= leftBars; j++) {
      if (candles[i].high <= candles[i - j].high) isSwingHigh = false;
      if (candles[i].low >= candles[i - j].low) isSwingLow = false;
    }

    for (let j = 1; j <= rightBars; j++) {
      if (candles[i].high <= candles[i + j].high) isSwingHigh = false;
      if (candles[i].low >= candles[i + j].low) isSwingLow = false;
    }

    if (isSwingHigh) {
      swings.push({
        type: 'HIGH',
        price: candles[i].high,
        timestamp: candles[i].timestamp,
        index: i,
        structureType: 'SWING',
        significance: calculateSignificance(candles, i, 'HIGH', leftBars + rightBars),
      });
    }

    if (isSwingLow) {
      swings.push({
        type: 'LOW',
        price: candles[i].low,
        timestamp: candles[i].timestamp,
        index: i,
        structureType: 'SWING',
        significance: calculateSignificance(candles, i, 'LOW', leftBars + rightBars),
      });
    }
  }

  return swings;
}

/**
 * Calculate significance of a swing point based on surrounding price action
 */
function calculateSignificance(
  candles: Candle[],
  index: number,
  type: 'HIGH' | 'LOW',
  lookback: number
): number {
  const start = Math.max(0, index - lookback);
  const end = Math.min(candles.length - 1, index + lookback);
  
  let maxRange = 0;
  for (let i = start; i <= end; i++) {
    maxRange = Math.max(maxRange, candles[i].high - candles[i].low);
  }

  if (maxRange === 0) return 50;

  const swingPrice = type === 'HIGH' ? candles[index].high : candles[index].low;
  let distanceSum = 0;
  let count = 0;

  for (let i = start; i <= end; i++) {
    if (i === index) continue;
    const refPrice = type === 'HIGH' ? candles[i].high : candles[i].low;
    distanceSum += Math.abs(swingPrice - refPrice);
    count++;
  }

  const avgDistance = count > 0 ? distanceSum / count : 0;
  return Math.min(100, Math.round((avgDistance / maxRange) * 100));
}

/**
 * Check if two price levels are approximately equal (for EQH/EQL detection)
 */
export function areLevelsEqual(
  price1: number,
  price2: number,
  tolerance: number = 0.0002 // 2 pips default
): boolean {
  return Math.abs(price1 - price2) <= tolerance;
}

/**
 * Check if price is within a zone
 */
export function isPriceInZone(price: number, zone: PriceZone): boolean {
  return price >= zone.low && price <= zone.high;
}

/**
 * Calculate zone midpoint
 */
export function zoneMidpoint(zone: PriceZone): number {
  return (zone.high + zone.low) / 2;
}

/**
 * Get Fibonacci levels between two prices
 */
export function getFibLevels(high: number, low: number): Map<number, number> {
  const range = high - low;
  const levels = new Map<number, number>();
  
  const fibRatios = [0, 0.236, 0.382, 0.5, 0.618, 0.705, 0.786, 0.886, 1.0];
  for (const ratio of fibRatios) {
    levels.set(ratio, high - range * ratio);
  }

  return levels;
}

/**
 * Convert timeframe to milliseconds
 */
export function timeframeToMs(tf: Timeframe): number {
  const map: Record<Timeframe, number> = {
    'TICK': 0,
    'M1': 60_000,
    'M3': 180_000,
    'M5': 300_000,
    'M15': 900_000,
    'M30': 1_800_000,
    'H1': 3_600_000,
    'H4': 14_400_000,
    'D1': 86_400_000,
    'W1': 604_800_000,
    'MN': 2_592_000_000,
  };
  return map[tf];
}

/**
 * Ring buffer for high-performance candle storage
 */
export class CandleBuffer {
  private buffer: Candle[];
  private head: number = 0;
  private _size: number = 0;
  private capacity: number;

  constructor(capacity: number) {
    this.capacity = capacity;
    this.buffer = new Array(capacity);
  }

  push(candle: Candle): void {
    this.buffer[(this.head + this._size) % this.capacity] = candle;
    if (this._size < this.capacity) {
      this._size++;
    } else {
      this.head = (this.head + 1) % this.capacity;
    }
  }

  get(index: number): Candle | undefined {
    if (index < 0 || index >= this._size) return undefined;
    return this.buffer[(this.head + index) % this.capacity];
  }

  get size(): number {
    return this._size;
  }

  get last(): Candle | undefined {
    return this._size > 0 ? this.get(this._size - 1) : undefined;
  }

  toArray(): Candle[] {
    const result: Candle[] = [];
    for (let i = 0; i < this._size; i++) {
      result.push(this.buffer[(this.head + i) % this.capacity]);
    }
    return result;
  }

  slice(start: number, end?: number): Candle[] {
    const actualEnd = end ?? this._size;
    const result: Candle[] = [];
    for (let i = start; i < actualEnd && i < this._size; i++) {
      result.push(this.buffer[(this.head + i) % this.capacity]);
    }
    return result;
  }
}

/**
 * Object pool for reducing GC pressure during high-frequency updates
 */
export class ObjectPool<T> {
  private pool: T[] = [];
  private factory: () => T;
  private reset: (obj: T) => void;

  constructor(factory: () => T, reset: (obj: T) => void, initialSize: number = 100) {
    this.factory = factory;
    this.reset = reset;
    for (let i = 0; i < initialSize; i++) {
      this.pool.push(factory());
    }
  }

  acquire(): T {
    return this.pool.pop() ?? this.factory();
  }

  release(obj: T): void {
    this.reset(obj);
    this.pool.push(obj);
  }

  get available(): number {
    return this.pool.length;
  }
}

/**
 * Determine pip size based on symbol
 */
export function getPipSize(symbol: string): number {
  const jpy = ['USDJPY', 'EURJPY', 'GBPJPY', 'AUDJPY', 'NZDJPY', 'CADJPY', 'CHFJPY'];
  if (jpy.includes(symbol.toUpperCase())) return 0.01;
  if (symbol.toUpperCase().includes('XAU')) return 0.1;
  if (symbol.toUpperCase().includes('BTC')) return 1.0;
  return 0.0001;
}

/**
 * Efficient binary search for timestamp in sorted candle array
 */
export function findCandleByTimestamp(candles: Candle[], timestamp: number): number {
  let left = 0;
  let right = candles.length - 1;

  while (left <= right) {
    const mid = (left + right) >>> 1;
    if (candles[mid].timestamp === timestamp) return mid;
    if (candles[mid].timestamp < timestamp) left = mid + 1;
    else right = mid - 1;
  }

  return left; // Return insertion point
}
