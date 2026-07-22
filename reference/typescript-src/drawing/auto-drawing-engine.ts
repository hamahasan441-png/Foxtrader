// ============================================================================
// AUTO DRAWING ENGINE
// Automatically draws: Trendlines, Channels, Support/Resistance, Fibonacci,
// Premium/Discount, Liquidity, FVG, Order Blocks, OTE, POI, Targets,
// Invalidation — all update automatically on each new bar.
// ============================================================================

import { Candle, Direction, Bias, SwingPoint, PriceZone } from '../core/types';
import { findSwingPoints, getFibLevels, calculateATR } from '../core/utils';
import { calculateEMA } from '../indicators/technical';

export type DrawingType =
  | 'TRENDLINE' | 'CHANNEL' | 'SUPPORT' | 'RESISTANCE'
  | 'FIBONACCI_RET' | 'FIBONACCI_EXT'
  | 'PREMIUM_ZONE' | 'DISCOUNT_ZONE' | 'EQUILIBRIUM'
  | 'LIQUIDITY_BSL' | 'LIQUIDITY_SSL'
  | 'FVG_ZONE' | 'ORDER_BLOCK_ZONE' | 'BREAKER_ZONE'
  | 'OTE_ZONE' | 'POI' | 'TARGET' | 'INVALIDATION';

export interface AutoDrawing {
  id: string;
  type: DrawingType;
  /** Start point (bar index or timestamp) */
  startIndex: number;
  startPrice: number;
  /** End point */
  endIndex: number;
  endPrice: number;
  /** Zone boundaries (for rectangles) */
  zone?: { high: number; low: number };
  /** Color suggestion */
  color: string;
  /** Line style: solid, dashed, dotted */
  style: 'solid' | 'dashed' | 'dotted';
  /** Line width */
  width: number;
  /** Label to display */
  label: string;
  /** Visibility priority (higher = more important) */
  priority: number;
  /** Whether this extends to the right edge */
  extendRight: boolean;
  /** Direction association */
  direction?: Direction;
  /** Auto-update: recalculate on new bars */
  autoUpdate: boolean;
  /** Timestamp of creation/last update */
  updatedAt: number;
}

export interface AutoDrawingResult {
  drawings: AutoDrawing[];
  updatedAt: number;
  totalCount: number;
}


let drawSeq = 0;
function genDrawId(type: DrawingType): string { return `draw_${type}_${++drawSeq}`; }

export class AutoDrawingEngine {
  private drawings: AutoDrawing[] = [];
  private maxDrawings: number = 200;

  /**
   * Generate all automatic drawings from candle data.
   * Called on each new bar or on demand.
   */
  generate(candles: Candle[], bias?: Bias): AutoDrawingResult {
    this.drawings = [];
    if (candles.length < 30) return { drawings: [], updatedAt: Date.now(), totalCount: 0 };

    const swings = findSwingPoints(candles, 5, 5);
    const atr = calculateATR(candles, 14);
    const currentATR = atr[candles.length - 1] || 0;
    const last = candles.length - 1;

    // 1. Support & Resistance (from swing levels)
    this.drawSupportResistance(candles, swings);

    // 2. Trendlines (connect swing highs / swing lows)
    this.drawTrendlines(candles, swings);

    // 3. Channels (parallel trendlines)
    this.drawChannels(candles, swings);

    // 4. Fibonacci Retracements & Extensions
    this.drawFibonacci(candles, swings);

    // 5. Premium / Discount / Equilibrium
    this.drawPremiumDiscount(candles);

    // 6. Liquidity levels (BSL / SSL)
    this.drawLiquidity(candles, swings);

    // 7. FVG zones
    this.drawFVGs(candles, currentATR);

    // 8. Order Blocks
    this.drawOrderBlocks(candles, currentATR);

    // 9. OTE zone
    this.drawOTE(candles, swings, bias);

    // 10. POI (Point of Interest — confluence zone)
    this.drawPOI(candles, swings, currentATR);

    // 11. Targets (nearest liquidity targets)
    this.drawTargets(candles, swings, bias);

    // 12. Invalidation level
    this.drawInvalidation(candles, swings, bias, currentATR);

    // Cap drawings for performance
    if (this.drawings.length > this.maxDrawings) {
      this.drawings.sort((a, b) => b.priority - a.priority);
      this.drawings = this.drawings.slice(0, this.maxDrawings);
    }

    return { drawings: [...this.drawings], updatedAt: Date.now(), totalCount: this.drawings.length };
  }


  // =========================================================================
  // SUPPORT & RESISTANCE
  // =========================================================================

  private drawSupportResistance(candles: Candle[], swings: SwingPoint[]): void {
    const last = candles.length - 1;
    const currentPrice = candles[last].close;

    // Cluster swing highs/lows by price proximity
    const highs = swings.filter(s => s.type === 'HIGH').map(s => s.price);
    const lows = swings.filter(s => s.type === 'LOW').map(s => s.price);
    const tolerance = (Math.max(...highs, ...lows) - Math.min(...highs, ...lows)) * 0.01;

    const resistanceLevels = this.clusterLevels(highs, tolerance).slice(0, 5);
    const supportLevels = this.clusterLevels(lows, tolerance).slice(0, 5);

    for (const level of resistanceLevels) {
      if (level > currentPrice) {
        this.drawings.push({
          id: genDrawId('RESISTANCE'), type: 'RESISTANCE',
          startIndex: 0, startPrice: level, endIndex: last, endPrice: level,
          color: '#ef4444', style: 'solid', width: 1, label: `R ${level.toFixed(5)}`,
          priority: 7, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
        });
      }
    }
    for (const level of supportLevels) {
      if (level < currentPrice) {
        this.drawings.push({
          id: genDrawId('SUPPORT'), type: 'SUPPORT',
          startIndex: 0, startPrice: level, endIndex: last, endPrice: level,
          color: '#10b981', style: 'solid', width: 1, label: `S ${level.toFixed(5)}`,
          priority: 7, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
        });
      }
    }
  }

  private clusterLevels(prices: number[], tolerance: number): number[] {
    if (prices.length === 0) return [];
    const sorted = [...prices].sort((a, b) => a - b);
    const clusters: { sum: number; count: number }[] = [];
    let current = { sum: sorted[0], count: 1 };

    for (let i = 1; i < sorted.length; i++) {
      if (sorted[i] - sorted[i - 1] <= tolerance) {
        current.sum += sorted[i];
        current.count++;
      } else {
        clusters.push(current);
        current = { sum: sorted[i], count: 1 };
      }
    }
    clusters.push(current);

    return clusters
      .filter(c => c.count >= 2) // At least 2 touches
      .sort((a, b) => b.count - a.count)
      .map(c => c.sum / c.count);
  }

  // =========================================================================
  // TRENDLINES
  // =========================================================================

  private drawTrendlines(candles: Candle[], swings: SwingPoint[]): void {
    const last = candles.length - 1;
    const highs = swings.filter(s => s.type === 'HIGH').slice(-6);
    const lows = swings.filter(s => s.type === 'LOW').slice(-6);

    // Bullish trendline: connect rising lows
    if (lows.length >= 2) {
      for (let i = 0; i < lows.length - 1; i++) {
        if (lows[i + 1].price > lows[i].price) {
          const slope = (lows[i + 1].price - lows[i].price) / (lows[i + 1].index - lows[i].index);
          const endPrice = lows[i].price + slope * (last - lows[i].index);
          this.drawings.push({
            id: genDrawId('TRENDLINE'), type: 'TRENDLINE',
            startIndex: lows[i].index, startPrice: lows[i].price,
            endIndex: last, endPrice,
            color: '#10b981', style: 'solid', width: 1.5, label: 'Bullish TL',
            priority: 6, extendRight: true, direction: 'BULLISH', autoUpdate: true, updatedAt: Date.now(),
          });
          break; // Only most recent valid trendline
        }
      }
    }

    // Bearish trendline: connect falling highs
    if (highs.length >= 2) {
      for (let i = 0; i < highs.length - 1; i++) {
        if (highs[i + 1].price < highs[i].price) {
          const slope = (highs[i + 1].price - highs[i].price) / (highs[i + 1].index - highs[i].index);
          const endPrice = highs[i].price + slope * (last - highs[i].index);
          this.drawings.push({
            id: genDrawId('TRENDLINE'), type: 'TRENDLINE',
            startIndex: highs[i].index, startPrice: highs[i].price,
            endIndex: last, endPrice,
            color: '#ef4444', style: 'solid', width: 1.5, label: 'Bearish TL',
            priority: 6, extendRight: true, direction: 'BEARISH', autoUpdate: true, updatedAt: Date.now(),
          });
          break;
        }
      }
    }
  }

  // =========================================================================
  // CHANNELS
  // =========================================================================

  private drawChannels(candles: Candle[], swings: SwingPoint[]): void {
    const highs = swings.filter(s => s.type === 'HIGH').slice(-4);
    const lows = swings.filter(s => s.type === 'LOW').slice(-4);
    const last = candles.length - 1;

    if (highs.length >= 2 && lows.length >= 2) {
      // Rising channel: connect lows + parallel line through highs
      const lowSlope = (lows[lows.length - 1].price - lows[0].price) / (lows[lows.length - 1].index - lows[0].index);
      const highSlope = (highs[highs.length - 1].price - highs[0].price) / (highs[highs.length - 1].index - highs[0].index);

      // Check if slopes are roughly parallel (within 30%)
      if (Math.abs(lowSlope) > 0 && Math.abs(1 - highSlope / lowSlope) < 0.4) {
        const lowEnd = lows[0].price + lowSlope * (last - lows[0].index);
        const highEnd = highs[0].price + highSlope * (last - highs[0].index);
        const dir: Direction = lowSlope > 0 ? 'BULLISH' : 'BEARISH';

        this.drawings.push({
          id: genDrawId('CHANNEL'), type: 'CHANNEL',
          startIndex: lows[0].index, startPrice: lows[0].price,
          endIndex: last, endPrice: lowEnd,
          color: '#6366f1', style: 'dashed', width: 1, label: `${dir} Channel`,
          priority: 5, extendRight: true, direction: dir, autoUpdate: true, updatedAt: Date.now(),
        });
        this.drawings.push({
          id: genDrawId('CHANNEL'), type: 'CHANNEL',
          startIndex: highs[0].index, startPrice: highs[0].price,
          endIndex: last, endPrice: highEnd,
          color: '#6366f1', style: 'dashed', width: 1, label: '',
          priority: 5, extendRight: true, direction: dir, autoUpdate: true, updatedAt: Date.now(),
        });
      }
    }
  }


  // =========================================================================
  // FIBONACCI
  // =========================================================================

  private drawFibonacci(candles: Candle[], swings: SwingPoint[]): void {
    const highs = swings.filter(s => s.type === 'HIGH');
    const lows = swings.filter(s => s.type === 'LOW');
    if (highs.length === 0 || lows.length === 0) return;

    const lastHigh = highs[highs.length - 1];
    const lastLow = lows[lows.length - 1];
    const last = candles.length - 1;

    // Determine the most recent completed swing for Fib
    const high = lastHigh.price;
    const low = lastLow.price;
    const fibLevels = getFibLevels(high, low);
    const fibs = [0.236, 0.382, 0.5, 0.618, 0.786];
    const colors = ['#6366f1', '#8b5cf6', '#a78bfa', '#f59e0b', '#ef4444'];

    for (let i = 0; i < fibs.length; i++) {
      const price = fibLevels.get(fibs[i]) ?? 0;
      this.drawings.push({
        id: genDrawId('FIBONACCI_RET'), type: 'FIBONACCI_RET',
        startIndex: Math.min(lastHigh.index, lastLow.index),
        startPrice: price, endIndex: last, endPrice: price,
        color: colors[i], style: 'dotted', width: 1,
        label: `${(fibs[i] * 100).toFixed(1)}% — ${price.toFixed(5)}`,
        priority: 5, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
      });
    }
  }

  // =========================================================================
  // PREMIUM / DISCOUNT / EQUILIBRIUM
  // =========================================================================

  private drawPremiumDiscount(candles: Candle[]): void {
    const period = Math.min(100, candles.length);
    const recent = candles.slice(-period);
    const high = Math.max(...recent.map(c => c.high));
    const low = Math.min(...recent.map(c => c.low));
    const eq = (high + low) / 2;
    const last = candles.length - 1;
    const startIdx = candles.length - period;

    this.drawings.push({
      id: genDrawId('PREMIUM_ZONE'), type: 'PREMIUM_ZONE',
      startIndex: startIdx, startPrice: high, endIndex: last, endPrice: eq,
      zone: { high, low: eq }, color: 'rgba(239,68,68,0.08)', style: 'solid', width: 0,
      label: 'PREMIUM', priority: 3, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
    });
    this.drawings.push({
      id: genDrawId('DISCOUNT_ZONE'), type: 'DISCOUNT_ZONE',
      startIndex: startIdx, startPrice: eq, endIndex: last, endPrice: low,
      zone: { high: eq, low }, color: 'rgba(16,185,129,0.08)', style: 'solid', width: 0,
      label: 'DISCOUNT', priority: 3, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
    });
    this.drawings.push({
      id: genDrawId('EQUILIBRIUM'), type: 'EQUILIBRIUM',
      startIndex: startIdx, startPrice: eq, endIndex: last, endPrice: eq,
      color: '#64748b', style: 'dashed', width: 1, label: `EQ ${eq.toFixed(5)}`,
      priority: 4, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
    });
  }

  // =========================================================================
  // LIQUIDITY LEVELS
  // =========================================================================

  private drawLiquidity(candles: Candle[], swings: SwingPoint[]): void {
    const last = candles.length - 1;
    const currentPrice = candles[last].close;
    const highs = swings.filter(s => s.type === 'HIGH' && s.price > currentPrice).slice(-5);
    const lows = swings.filter(s => s.type === 'LOW' && s.price < currentPrice).slice(-5);

    for (const h of highs) {
      this.drawings.push({
        id: genDrawId('LIQUIDITY_BSL'), type: 'LIQUIDITY_BSL',
        startIndex: h.index, startPrice: h.price, endIndex: last, endPrice: h.price,
        color: '#06b6d4', style: 'dashed', width: 1, label: `BSL ${h.price.toFixed(5)}`,
        priority: 8, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
      });
    }
    for (const l of lows) {
      this.drawings.push({
        id: genDrawId('LIQUIDITY_SSL'), type: 'LIQUIDITY_SSL',
        startIndex: l.index, startPrice: l.price, endIndex: last, endPrice: l.price,
        color: '#8b5cf6', style: 'dashed', width: 1, label: `SSL ${l.price.toFixed(5)}`,
        priority: 8, extendRight: true, autoUpdate: true, updatedAt: Date.now(),
      });
    }
  }

  // =========================================================================
  // FVG ZONES
  // =========================================================================

  private drawFVGs(candles: Candle[], atr: number): void {
    const last = candles.length - 1;
    for (let i = 1; i < candles.length - 1; i++) {
      const c1 = candles[i - 1], c3 = candles[i + 1];
      // Bullish FVG
      if (c3.low > c1.high && (c3.low - c1.high) >= atr * 0.1) {
        this.drawings.push({
          id: genDrawId('FVG_ZONE'), type: 'FVG_ZONE',
          startIndex: i - 1, startPrice: c3.low, endIndex: last, endPrice: c1.high,
          zone: { high: c3.low, low: c1.high },
          color: 'rgba(0,220,130,0.12)', style: 'solid', width: 0,
          label: 'FVG ↑', priority: 6, extendRight: true, direction: 'BULLISH',
          autoUpdate: true, updatedAt: Date.now(),
        });
      }
      // Bearish FVG
      if (c1.low > c3.high && (c1.low - c3.high) >= atr * 0.1) {
        this.drawings.push({
          id: genDrawId('FVG_ZONE'), type: 'FVG_ZONE',
          startIndex: i - 1, startPrice: c1.low, endIndex: last, endPrice: c3.high,
          zone: { high: c1.low, low: c3.high },
          color: 'rgba(255,71,87,0.12)', style: 'solid', width: 0,
          label: 'FVG ↓', priority: 6, extendRight: true, direction: 'BEARISH',
          autoUpdate: true, updatedAt: Date.now(),
        });
      }
    }
  }

  // =========================================================================
  // ORDER BLOCKS
  // =========================================================================

  private drawOrderBlocks(candles: Candle[], atr: number): void {
    const last = candles.length - 1;
    for (let i = 2; i < candles.length - 1; i++) {
      const c = candles[i];
      const next = candles[i + 1];
      // Bullish OB: bearish candle followed by strong bullish displacement
      if (c.close < c.open && next.close > next.open) {
        const displacement = Math.abs(next.close - next.open);
        if (displacement > atr * 1.0) {
          this.drawings.push({
            id: genDrawId('ORDER_BLOCK_ZONE'), type: 'ORDER_BLOCK_ZONE',
            startIndex: i, startPrice: c.high, endIndex: last, endPrice: c.low,
            zone: { high: c.high, low: c.low },
            color: 'rgba(16,185,129,0.18)', style: 'solid', width: 0,
            label: 'Bull OB', priority: 8, extendRight: true, direction: 'BULLISH',
            autoUpdate: true, updatedAt: Date.now(),
          });
        }
      }
      // Bearish OB: bullish candle followed by strong bearish displacement
      if (c.close > c.open && next.close < next.open) {
        const displacement = Math.abs(next.open - next.close);
        if (displacement > atr * 1.0) {
          this.drawings.push({
            id: genDrawId('ORDER_BLOCK_ZONE'), type: 'ORDER_BLOCK_ZONE',
            startIndex: i, startPrice: c.high, endIndex: last, endPrice: c.low,
            zone: { high: c.high, low: c.low },
            color: 'rgba(239,68,68,0.18)', style: 'solid', width: 0,
            label: 'Bear OB', priority: 8, extendRight: true, direction: 'BEARISH',
            autoUpdate: true, updatedAt: Date.now(),
          });
        }
      }
    }
  }


  // =========================================================================
  // OTE ZONE
  // =========================================================================

  private drawOTE(candles: Candle[], swings: SwingPoint[], bias?: Bias): void {
    const highs = swings.filter(s => s.type === 'HIGH');
    const lows = swings.filter(s => s.type === 'LOW');
    if (highs.length === 0 || lows.length === 0) return;

    const lastHigh = highs[highs.length - 1];
    const lastLow = lows[lows.length - 1];
    const range = lastHigh.price - lastLow.price;
    const last = candles.length - 1;

    // OTE = 0.618 to 0.786 retracement
    const direction: Direction = lastHigh.index > lastLow.index ? 'BEARISH' : 'BULLISH';
    let oteHigh: number, oteLow: number;

    if (direction === 'BULLISH') {
      // Retracement into discount
      oteHigh = lastHigh.price - range * 0.618;
      oteLow = lastHigh.price - range * 0.786;
    } else {
      oteHigh = lastLow.price + range * 0.786;
      oteLow = lastLow.price + range * 0.618;
    }

    this.drawings.push({
      id: genDrawId('OTE_ZONE'), type: 'OTE_ZONE',
      startIndex: Math.min(lastHigh.index, lastLow.index), startPrice: oteHigh,
      endIndex: last, endPrice: oteLow,
      zone: { high: oteHigh, low: oteLow },
      color: 'rgba(99,102,241,0.15)', style: 'solid', width: 0,
      label: `OTE ${direction} (0.618-0.786)`, priority: 9,
      extendRight: true, direction, autoUpdate: true, updatedAt: Date.now(),
    });
  }

  // =========================================================================
  // POI (Point of Interest — where OB + FVG + Fib overlap)
  // =========================================================================

  private drawPOI(candles: Candle[], swings: SwingPoint[], atr: number): void {
    // Find zones where multiple drawings overlap
    const zones: { high: number; low: number }[] = this.drawings
      .filter(d => d.zone && ['FVG_ZONE', 'ORDER_BLOCK_ZONE', 'OTE_ZONE'].includes(d.type))
      .map(d => d.zone!);

    const last = candles.length - 1;

    // Find overlapping zones
    for (let i = 0; i < zones.length; i++) {
      for (let j = i + 1; j < zones.length; j++) {
        const overlapHigh = Math.min(zones[i].high, zones[j].high);
        const overlapLow = Math.max(zones[i].low, zones[j].low);
        if (overlapHigh > overlapLow) {
          this.drawings.push({
            id: genDrawId('POI'), type: 'POI',
            startIndex: last - 20, startPrice: overlapHigh,
            endIndex: last, endPrice: overlapLow,
            zone: { high: overlapHigh, low: overlapLow },
            color: 'rgba(245,158,11,0.25)', style: 'solid', width: 1,
            label: 'POI (Confluence)', priority: 10,
            extendRight: true, autoUpdate: true, updatedAt: Date.now(),
          });
          break; // Only show strongest POI
        }
      }
      if (this.drawings.some(d => d.type === 'POI')) break;
    }
  }

  // =========================================================================
  // TARGETS (nearest liquidity / structure targets)
  // =========================================================================

  private drawTargets(candles: Candle[], swings: SwingPoint[], bias?: Bias): void {
    const last = candles.length - 1;
    const price = candles[last].close;
    const direction = bias === 'BEARISH' ? 'BEARISH' : 'BULLISH';

    const targets = swings
      .filter(s => direction === 'BULLISH'
        ? s.type === 'HIGH' && s.price > price
        : s.type === 'LOW' && s.price < price)
      .sort((a, b) => direction === 'BULLISH' ? a.price - b.price : b.price - a.price)
      .slice(0, 3);

    targets.forEach((t, i) => {
      this.drawings.push({
        id: genDrawId('TARGET'), type: 'TARGET',
        startIndex: t.index, startPrice: t.price, endIndex: last, endPrice: t.price,
        color: '#3b82f6', style: 'dotted', width: 1.5,
        label: `TP${i + 1} ${t.price.toFixed(5)}`, priority: 8,
        extendRight: true, direction, autoUpdate: true, updatedAt: Date.now(),
      });
    });
  }

  // =========================================================================
  // INVALIDATION
  // =========================================================================

  private drawInvalidation(candles: Candle[], swings: SwingPoint[], bias?: Bias, atr: number = 0): void {
    const last = candles.length - 1;
    const direction = bias === 'BEARISH' ? 'BEARISH' : 'BULLISH';

    // Invalidation = beyond the protective swing
    const protectiveSwings = swings.filter(s =>
      direction === 'BULLISH' ? s.type === 'LOW' : s.type === 'HIGH'
    ).sort((a, b) => b.index - a.index);

    if (protectiveSwings.length > 0) {
      const swing = protectiveSwings[0];
      const invalidPrice = direction === 'BULLISH'
        ? swing.price - atr * 0.2
        : swing.price + atr * 0.2;

      this.drawings.push({
        id: genDrawId('INVALIDATION'), type: 'INVALIDATION',
        startIndex: swing.index, startPrice: invalidPrice,
        endIndex: last, endPrice: invalidPrice,
        color: '#f43f5e', style: 'dashed', width: 2,
        label: `INVALIDATION ${invalidPrice.toFixed(5)}`, priority: 9,
        extendRight: true, direction, autoUpdate: true, updatedAt: Date.now(),
      });
    }
  }

  // =========================================================================
  // PUBLIC API
  // =========================================================================

  /** Get all current drawings */
  getDrawings(): AutoDrawing[] { return [...this.drawings]; }

  /** Get drawings by type */
  getByType(type: DrawingType): AutoDrawing[] { return this.drawings.filter(d => d.type === type); }

  /** Get drawings by priority (higher first) */
  getTopDrawings(count: number = 50): AutoDrawing[] {
    return [...this.drawings].sort((a, b) => b.priority - a.priority).slice(0, count);
  }

  /** Clear all drawings */
  clear(): void { this.drawings = []; }

  /** Set max drawings limit */
  setMaxDrawings(max: number): void { this.maxDrawings = max; }
}
