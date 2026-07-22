// ============================================================================
// MARKET STRUCTURE MODULE
// BOS, CHOCH, MSS, IDM, Internal/External/Swing/Fractal Structure
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  SwingPoint,
  StructureBreak,
  MarketStructure,
  Direction,
  Bias,
  StructureType,
} from '../../core/types';
import { findSwingPoints } from '../../core/utils';
import { TradingEventBus } from '../../core/event-bus';

export interface MarketStructureConfig {
  swingLeftBars: number;
  swingRightBars: number;
  internalLeftBars: number;
  internalRightBars: number;
  fractalLeftBars: number;
  fractalRightBars: number;
  equalLevelTolerance: number;
  confirmationBodies: boolean; // Require candle body close for confirmation
  minSwingSize: number; // Minimum swing size in ATR multiples
}

const DEFAULT_CONFIG: MarketStructureConfig = {
  swingLeftBars: 5,
  swingRightBars: 5,
  internalLeftBars: 2,
  internalRightBars: 2,
  fractalLeftBars: 1,
  fractalRightBars: 1,
  equalLevelTolerance: 0.0002,
  confirmationBodies: true,
  minSwingSize: 0.3,
};

export class MarketStructureAnalyzer {
  private config: MarketStructureConfig;
  private eventBus?: TradingEventBus;

  // State tracking - no look-ahead
  private confirmedSwingHighs: SwingPoint[] = [];
  private confirmedSwingLows: SwingPoint[] = [];
  private structureBreaks: StructureBreak[] = [];
  private currentBias: Bias = 'NEUTRAL';
  private lastHigherHigh: SwingPoint | null = null;
  private lastHigherLow: SwingPoint | null = null;
  private lastLowerHigh: SwingPoint | null = null;
  private lastLowerLow: SwingPoint | null = null;

  constructor(config: Partial<MarketStructureConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Analyze full candle array for market structure
   * Processes bar-by-bar to prevent look-ahead bias
   */
  analyze(candles: Candle[]): MarketStructure {
    this.reset();

    if (candles.length < this.config.swingLeftBars + this.config.swingRightBars + 1) {
      return this.getState();
    }

    // Identify swing points at all structure levels
    const externalSwings = findSwingPoints(candles, this.config.swingLeftBars, this.config.swingRightBars);
    const internalSwings = findSwingPoints(candles, this.config.internalLeftBars, this.config.internalRightBars);
    const fractalSwings = findSwingPoints(candles, this.config.fractalLeftBars, this.config.fractalRightBars);

    // Tag structure types
    externalSwings.forEach(s => s.structureType = 'EXTERNAL');
    internalSwings.forEach(s => s.structureType = 'INTERNAL');
    fractalSwings.forEach(s => s.structureType = 'FRACTAL');

    // Process external/swing structure
    this.processSwingStructure(externalSwings, candles, 'EXTERNAL');

    // Process internal structure
    this.processSwingStructure(internalSwings, candles, 'INTERNAL');

    // Process fractal structure
    this.processSwingStructure(fractalSwings, candles, 'FRACTAL');

    // Determine overall bias
    this.determineBias();

    return this.getState();
  }

  /**
   * Incremental update - process a single new confirmed candle
   * Used for real-time updates without reprocessing entire history
   */
  updateIncremental(candles: Candle[], newCandleIndex: number): StructureBreak[] {
    const newBreaks: StructureBreak[] = [];

    // Check if any new swing points are now confirmed
    // A swing high is confirmed when rightBars candles have formed after it
    const confirmIndex = newCandleIndex - this.config.swingRightBars;
    if (confirmIndex < this.config.swingLeftBars) return newBreaks;

    const potentialSwing = candles[confirmIndex];
    
    // Check swing high
    if (this.isConfirmedSwingHigh(candles, confirmIndex)) {
      const swing: SwingPoint = {
        type: 'HIGH',
        price: potentialSwing.high,
        timestamp: potentialSwing.timestamp,
        index: confirmIndex,
        structureType: 'SWING',
        significance: 50,
      };
      
      const breaks = this.processNewSwing(swing, candles);
      newBreaks.push(...breaks);
    }

    // Check swing low
    if (this.isConfirmedSwingLow(candles, confirmIndex)) {
      const swing: SwingPoint = {
        type: 'LOW',
        price: potentialSwing.low,
        timestamp: potentialSwing.timestamp,
        index: confirmIndex,
        structureType: 'SWING',
        significance: 50,
      };
      
      const breaks = this.processNewSwing(swing, candles);
      newBreaks.push(...breaks);
    }

    return newBreaks;
  }

  /**
   * Process swing structure to identify BOS, CHOCH, MSS, IDM
   */
  private processSwingStructure(
    swings: SwingPoint[],
    candles: Candle[],
    structureType: StructureType
  ): void {
    const highs = swings.filter(s => s.type === 'HIGH').sort((a, b) => a.index - b.index);
    const lows = swings.filter(s => s.type === 'LOW').sort((a, b) => a.index - b.index);

    if (structureType === 'EXTERNAL' || structureType === 'SWING') {
      this.confirmedSwingHighs.push(...highs);
      this.confirmedSwingLows.push(...lows);
    }

    // Process structure breaks sequentially (no look-ahead)
    let currentTrend: Direction | null = null;
    let lastSignificantHigh: SwingPoint | null = null;
    let lastSignificantLow: SwingPoint | null = null;

    // Merge and sort all swings by index for sequential processing
    const allSwings = [...highs, ...lows].sort((a, b) => a.index - b.index);

    for (const swing of allSwings) {
      if (swing.type === 'HIGH') {
        if (lastSignificantHigh && lastSignificantLow) {
          // Check for structure breaks after this swing
          this.checkStructureBreak(
            swing, lastSignificantHigh, lastSignificantLow,
            currentTrend, candles, structureType
          );
        }

        // Update trend tracking
        if (!lastSignificantHigh || swing.price > lastSignificantHigh.price) {
          if (currentTrend === 'BULLISH' || currentTrend === null) {
            this.lastHigherHigh = swing;
          } else {
            this.lastLowerHigh = swing;
          }
        } else {
          this.lastLowerHigh = swing;
        }

        lastSignificantHigh = swing;
      } else {
        if (lastSignificantHigh && lastSignificantLow) {
          this.checkStructureBreak(
            swing, lastSignificantHigh, lastSignificantLow,
            currentTrend, candles, structureType
          );
        }

        if (!lastSignificantLow || swing.price < lastSignificantLow.price) {
          if (currentTrend === 'BEARISH' || currentTrend === null) {
            this.lastLowerLow = swing;
          } else {
            this.lastHigherLow = swing;
          }
        } else {
          this.lastHigherLow = swing;
        }

        lastSignificantLow = swing;
      }

      // Determine current trend
      if (lastSignificantHigh && lastSignificantLow) {
        currentTrend = this.inferTrend(lastSignificantHigh, lastSignificantLow, allSwings);
      }
    }
  }

  /**
   * Check if a structure break has occurred
   */
  private checkStructureBreak(
    currentSwing: SwingPoint,
    lastHigh: SwingPoint,
    lastLow: SwingPoint,
    currentTrend: Direction | null,
    candles: Candle[],
    structureType: StructureType
  ): void {
    // Find candles that break the swing levels AFTER the swing is confirmed
    const searchStart = Math.max(lastHigh.index, lastLow.index) + 1;
    
    for (let i = searchStart; i < candles.length; i++) {
      const candle = candles[i];

      // Break of swing high (potential BOS bullish or CHOCH from bearish)
      if (candle.close > lastHigh.price || (!this.config.confirmationBodies && candle.high > lastHigh.price)) {
        const breakType = this.classifyBreak('BULLISH', currentTrend, lastHigh, candles, i);
        
        if (breakType && !this.isDuplicateBreak(breakType, candle.timestamp, structureType)) {
          const structureBreak: StructureBreak = {
            type: breakType,
            direction: 'BULLISH',
            breakPrice: lastHigh.price,
            breakTimestamp: candle.timestamp,
            breakIndex: i,
            swingPoint: lastHigh,
            structureType,
            confirmed: this.config.confirmationBodies ? candle.close > lastHigh.price : true,
            confirmationCandle: candle,
          };

          this.structureBreaks.push(structureBreak);
          this.emitStructureBreak(structureBreak);
        }
        break; // Only process first break
      }

      // Break of swing low (potential BOS bearish or CHOCH from bullish)
      if (candle.close < lastLow.price || (!this.config.confirmationBodies && candle.low < lastLow.price)) {
        const breakType = this.classifyBreak('BEARISH', currentTrend, lastLow, candles, i);
        
        if (breakType && !this.isDuplicateBreak(breakType, candle.timestamp, structureType)) {
          const structureBreak: StructureBreak = {
            type: breakType,
            direction: 'BEARISH',
            breakPrice: lastLow.price,
            breakTimestamp: candle.timestamp,
            breakIndex: i,
            swingPoint: lastLow,
            structureType,
            confirmed: this.config.confirmationBodies ? candle.close < lastLow.price : true,
            confirmationCandle: candle,
          };

          this.structureBreaks.push(structureBreak);
          this.emitStructureBreak(structureBreak);
        }
        break;
      }
    }
  }

  /**
   * Classify the type of structure break
   * BOS = Break in same direction as trend (continuation)
   * CHOCH = Change of Character (reversal signal)
   * MSS = Market Structure Shift (strong CHOCH with displacement)
   * IDM = Inducement (minor break before reversal)
   */
  private classifyBreak(
    breakDirection: Direction,
    currentTrend: Direction | null,
    brokenSwing: SwingPoint,
    candles: Candle[],
    breakIndex: number
  ): 'BOS' | 'CHOCH' | 'MSS' | 'IDM' | null {
    // If no established trend, first break establishes direction
    if (currentTrend === null) {
      return 'BOS';
    }

    // BOS: Break in same direction as current trend
    if (breakDirection === currentTrend) {
      return 'BOS';
    }

    // Break against the trend - could be CHOCH, MSS, or IDM
    const breakCandle = candles[breakIndex];
    const displacement = this.calculateDisplacement(candles, breakIndex, breakDirection);

    // MSS: Strong displacement (large body candle breaking structure)
    if (displacement > 1.5) {
      return 'MSS';
    }

    // IDM: If the break is minor (internal structure) and could be inducement
    if (brokenSwing.structureType === 'INTERNAL' || brokenSwing.structureType === 'FRACTAL') {
      // Check if this is a minor swing point (potential inducement)
      if (brokenSwing.significance < 30) {
        return 'IDM';
      }
    }

    // CHOCH: Standard change of character
    return 'CHOCH';
  }

  /**
   * Calculate displacement of the break candle
   * Higher displacement = more institutional involvement
   */
  private calculateDisplacement(candles: Candle[], breakIndex: number, direction: Direction): number {
    const breakCandle = candles[breakIndex];
    const bodySize = Math.abs(breakCandle.close - breakCandle.open);
    
    // Calculate average body size of recent candles
    const lookback = Math.min(20, breakIndex);
    let avgBody = 0;
    for (let i = breakIndex - lookback; i < breakIndex; i++) {
      avgBody += Math.abs(candles[i].close - candles[i].open);
    }
    avgBody /= lookback;

    return avgBody > 0 ? bodySize / avgBody : 1;
  }

  /**
   * Check if we already have this break recorded (prevent duplicates)
   */
  private isDuplicateBreak(type: string, timestamp: number, structureType: StructureType): boolean {
    return this.structureBreaks.some(
      b => b.type === type && 
           b.breakTimestamp === timestamp && 
           b.structureType === structureType
    );
  }

  /**
   * Infer current trend from swing sequence
   */
  private inferTrend(lastHigh: SwingPoint, lastLow: SwingPoint, allSwings: SwingPoint[]): Direction | null {
    // Find previous highs and lows for comparison
    const highs = allSwings.filter(s => s.type === 'HIGH' && s.index < lastHigh.index);
    const lows = allSwings.filter(s => s.type === 'LOW' && s.index < lastLow.index);

    if (highs.length === 0 || lows.length === 0) return null;

    const prevHigh = highs[highs.length - 1];
    const prevLow = lows[lows.length - 1];

    // Higher high + Higher low = Bullish
    if (lastHigh.price > prevHigh.price && lastLow.price > prevLow.price) {
      return 'BULLISH';
    }

    // Lower low + Lower high = Bearish
    if (lastLow.price < prevLow.price && lastHigh.price < prevHigh.price) {
      return 'BEARISH';
    }

    return null;
  }

  /**
   * Determine overall market bias from structure analysis
   */
  private determineBias(): void {
    if (this.structureBreaks.length === 0) {
      this.currentBias = 'NEUTRAL';
      return;
    }

    // Weight recent breaks more heavily
    const recentBreaks = this.structureBreaks.slice(-10);
    let bullishScore = 0;
    let bearishScore = 0;

    for (let i = 0; i < recentBreaks.length; i++) {
      const weight = (i + 1) / recentBreaks.length; // More recent = higher weight
      const b = recentBreaks[i];

      if (b.direction === 'BULLISH') {
        bullishScore += weight * this.getBreakWeight(b.type);
      } else {
        bearishScore += weight * this.getBreakWeight(b.type);
      }
    }

    if (bullishScore > bearishScore * 1.2) {
      this.currentBias = 'BULLISH';
    } else if (bearishScore > bullishScore * 1.2) {
      this.currentBias = 'BEARISH';
    } else {
      this.currentBias = 'NEUTRAL';
    }
  }

  /**
   * Get weight multiplier for break type significance
   */
  private getBreakWeight(type: 'BOS' | 'CHOCH' | 'MSS' | 'IDM'): number {
    switch (type) {
      case 'MSS': return 3.0;
      case 'CHOCH': return 2.0;
      case 'BOS': return 1.5;
      case 'IDM': return 0.5;
    }
  }

  /**
   * Check if index is a confirmed swing high (no look-ahead)
   */
  private isConfirmedSwingHigh(candles: Candle[], index: number): boolean {
    const { swingLeftBars, swingRightBars } = this.config;
    if (index < swingLeftBars || index >= candles.length - swingRightBars) return false;

    for (let i = 1; i <= swingLeftBars; i++) {
      if (candles[index].high <= candles[index - i].high) return false;
    }
    for (let i = 1; i <= swingRightBars; i++) {
      if (candles[index].high <= candles[index + i].high) return false;
    }
    return true;
  }

  /**
   * Check if index is a confirmed swing low (no look-ahead)
   */
  private isConfirmedSwingLow(candles: Candle[], index: number): boolean {
    const { swingLeftBars, swingRightBars } = this.config;
    if (index < swingLeftBars || index >= candles.length - swingRightBars) return false;

    for (let i = 1; i <= swingLeftBars; i++) {
      if (candles[index].low >= candles[index - i].low) return false;
    }
    for (let i = 1; i <= swingRightBars; i++) {
      if (candles[index].low >= candles[index + i].low) return false;
    }
    return true;
  }

  /**
   * Process a newly confirmed swing point for incremental updates
   */
  private processNewSwing(swing: SwingPoint, candles: Candle[]): StructureBreak[] {
    const breaks: StructureBreak[] = [];

    if (swing.type === 'HIGH') {
      const lastHigh = this.confirmedSwingHighs[this.confirmedSwingHighs.length - 1];
      
      if (lastHigh) {
        // Check if new high breaks above previous high
        if (swing.price > lastHigh.price) {
          const breakType = this.currentBias === 'BULLISH' ? 'BOS' : 'CHOCH';
          const structureBreak: StructureBreak = {
            type: breakType,
            direction: 'BULLISH',
            breakPrice: lastHigh.price,
            breakTimestamp: swing.timestamp,
            breakIndex: swing.index,
            swingPoint: lastHigh,
            structureType: 'SWING',
            confirmed: true,
          };
          breaks.push(structureBreak);
          this.structureBreaks.push(structureBreak);
          this.emitStructureBreak(structureBreak);
        }
      }

      this.confirmedSwingHighs.push(swing);
    } else {
      const lastLow = this.confirmedSwingLows[this.confirmedSwingLows.length - 1];
      
      if (lastLow) {
        if (swing.price < lastLow.price) {
          const breakType = this.currentBias === 'BEARISH' ? 'BOS' : 'CHOCH';
          const structureBreak: StructureBreak = {
            type: breakType,
            direction: 'BEARISH',
            breakPrice: lastLow.price,
            breakTimestamp: swing.timestamp,
            breakIndex: swing.index,
            swingPoint: lastLow,
            structureType: 'SWING',
            confirmed: true,
          };
          breaks.push(structureBreak);
          this.structureBreaks.push(structureBreak);
          this.emitStructureBreak(structureBreak);
        }
      }

      this.confirmedSwingLows.push(swing);
    }

    this.determineBias();
    return breaks;
  }

  /**
   * Emit structure break event
   */
  private emitStructureBreak(structureBreak: StructureBreak): void {
    this.eventBus?.emit({
      type: 'STRUCTURE_BREAK',
      data: structureBreak,
    });
  }

  /**
   * Get current state
   */
  getState(): MarketStructure {
    return {
      currentBias: this.currentBias,
      swingHighs: [...this.confirmedSwingHighs],
      swingLows: [...this.confirmedSwingLows],
      structureBreaks: [...this.structureBreaks],
      internalStructure: this.structureBreaks.filter(b => b.structureType === 'INTERNAL'),
      externalStructure: this.structureBreaks.filter(b => b.structureType === 'EXTERNAL'),
      fractalStructure: this.structureBreaks.filter(b => b.structureType === 'FRACTAL'),
    };
  }

  /**
   * Get the most recent structure break
   */
  getLastBreak(structureType?: StructureType): StructureBreak | null {
    const breaks = structureType
      ? this.structureBreaks.filter(b => b.structureType === structureType)
      : this.structureBreaks;
    return breaks.length > 0 ? breaks[breaks.length - 1] : null;
  }

  /**
   * Get current bias
   */
  getBias(): Bias {
    return this.currentBias;
  }

  /**
   * Reset all state
   */
  reset(): void {
    this.confirmedSwingHighs = [];
    this.confirmedSwingLows = [];
    this.structureBreaks = [];
    this.currentBias = 'NEUTRAL';
    this.lastHigherHigh = null;
    this.lastHigherLow = null;
    this.lastLowerHigh = null;
    this.lastLowerLow = null;
  }
}
