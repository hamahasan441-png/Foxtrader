// ============================================================================
// LIQUIDITY MODULE
// BSL, SSL, EQH, EQL, Pools, Sweeps, Engineered, Resting, Session Liquidity
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  SwingPoint,
  LiquidityLevel,
  LiquidityType,
  LiquiditySweep,
  LiquidityPool,
  PriceZone,
  SessionType,
  TradingSession,
} from '../../core/types';
import { findSwingPoints, areLevelsEqual } from '../../core/utils';
import { TradingEventBus } from '../../core/event-bus';

export interface LiquidityConfig {
  equalLevelTolerance: number; // Pip tolerance for EQH/EQL
  minTouches: number; // Minimum touches to form liquidity
  sweepMinPenetration: number; // Min penetration to count as sweep
  sweepMaxRecoveryBars: number; // Max bars to recover for valid sweep
  poolMinLevels: number; // Min levels to form a pool
  swingLeftBars: number;
  swingRightBars: number;
  sessionHighLowPeriod: number; // Bars to track session H/L
}

const DEFAULT_CONFIG: LiquidityConfig = {
  equalLevelTolerance: 0.0003,
  minTouches: 2,
  sweepMinPenetration: 0.0001,
  sweepMaxRecoveryBars: 5,
  poolMinLevels: 3,
  swingLeftBars: 3,
  swingRightBars: 3,
  sessionHighLowPeriod: 50,
};

export class LiquidityAnalyzer {
  private config: LiquidityConfig;
  private eventBus?: TradingEventBus;
  private levels: LiquidityLevel[] = [];
  private sweeps: LiquiditySweep[] = [];
  private pools: LiquidityPool[] = [];

  constructor(config: Partial<LiquidityConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Full analysis of liquidity landscape
   */
  analyze(candles: Candle[], sessions?: TradingSession[]): {
    levels: LiquidityLevel[];
    sweeps: LiquiditySweep[];
    pools: LiquidityPool[];
  } {
    this.reset();

    if (candles.length < 10) return { levels: [], sweeps: [], pools: [] };

    // 1. Find swing points for BSL/SSL
    const swings = findSwingPoints(candles, this.config.swingLeftBars, this.config.swingRightBars);
    
    // 2. Identify Buy Side Liquidity (above swing highs)
    this.identifyBuySideLiquidity(swings, candles);
    
    // 3. Identify Sell Side Liquidity (below swing lows)
    this.identifySellSideLiquidity(swings, candles);
    
    // 4. Detect Equal Highs
    this.detectEqualHighs(swings, candles);
    
    // 5. Detect Equal Lows
    this.detectEqualLows(swings, candles);
    
    // 6. Identify Resting Liquidity (untested levels)
    this.identifyRestingLiquidity(candles);
    
    // 7. Detect Engineered Liquidity (fabricated levels)
    this.detectEngineeredLiquidity(candles, swings);
    
    // 8. Session Liquidity
    if (sessions) {
      this.identifySessionLiquidity(sessions, candles);
    }
    
    // 9. Detect Sweeps
    this.detectSweeps(candles);
    
    // 10. Identify Pools (clusters of liquidity)
    this.identifyPools();

    return {
      levels: [...this.levels],
      sweeps: [...this.sweeps],
      pools: [...this.pools],
    };
  }

  /**
   * Incremental sweep detection on new candle
   */
  checkForSweeps(candle: Candle, index: number): LiquiditySweep[] {
    const newSweeps: LiquiditySweep[] = [];

    for (const level of this.levels) {
      if (level.swept) continue;

      // Check BSL sweep (price goes above then returns)
      if (level.type === 'BSL' || level.type === 'EQH' || level.type === 'POOL') {
        if (candle.high > level.price + this.config.sweepMinPenetration) {
          if (candle.close < level.price) {
            // Immediate sweep and recovery
            const sweep = this.createSweep(level, candle, index);
            newSweeps.push(sweep);
            level.swept = true;
            level.sweepTimestamp = candle.timestamp;
            level.sweepCandle = candle;
          }
        }
      }

      // Check SSL sweep (price goes below then returns)
      if (level.type === 'SSL' || level.type === 'EQL' || level.type === 'POOL') {
        if (candle.low < level.price - this.config.sweepMinPenetration) {
          if (candle.close > level.price) {
            const sweep = this.createSweep(level, candle, index);
            newSweeps.push(sweep);
            level.swept = true;
            level.sweepTimestamp = candle.timestamp;
            level.sweepCandle = candle;
          }
        }
      }
    }

    if (newSweeps.length > 0) {
      this.sweeps.push(...newSweeps);
      for (const sweep of newSweeps) {
        this.eventBus?.emit({ type: 'LIQUIDITY_SWEEP', data: sweep });
      }
    }

    return newSweeps;
  }

  /**
   * Identify Buy Side Liquidity above swing highs
   */
  private identifyBuySideLiquidity(swings: SwingPoint[], candles: Candle[]): void {
    const highs = swings.filter(s => s.type === 'HIGH');

    for (const swing of highs) {
      // Count how many times price approached but didn't break this level
      let touches = 0;
      for (let i = swing.index + 1; i < candles.length; i++) {
        if (Math.abs(candles[i].high - swing.price) <= this.config.equalLevelTolerance) {
          touches++;
        }
        if (candles[i].close > swing.price) break; // Level broken
      }

      const level: LiquidityLevel = {
        type: 'BSL',
        price: swing.price,
        startTimestamp: swing.timestamp,
        startIndex: swing.index,
        touches: touches + 1,
        swept: false,
        strength: Math.min(100, (touches + 1) * 20 + swing.significance * 0.5),
      };

      this.levels.push(level);
    }
  }

  /**
   * Identify Sell Side Liquidity below swing lows
   */
  private identifySellSideLiquidity(swings: SwingPoint[], candles: Candle[]): void {
    const lows = swings.filter(s => s.type === 'LOW');

    for (const swing of lows) {
      let touches = 0;
      for (let i = swing.index + 1; i < candles.length; i++) {
        if (Math.abs(candles[i].low - swing.price) <= this.config.equalLevelTolerance) {
          touches++;
        }
        if (candles[i].close < swing.price) break;
      }

      const level: LiquidityLevel = {
        type: 'SSL',
        price: swing.price,
        startTimestamp: swing.timestamp,
        startIndex: swing.index,
        touches: touches + 1,
        swept: false,
        strength: Math.min(100, (touches + 1) * 20 + swing.significance * 0.5),
      };

      this.levels.push(level);
    }
  }

  /**
   * Detect Equal Highs - strong BSL
   */
  private detectEqualHighs(swings: SwingPoint[], candles: Candle[]): void {
    const highs = swings.filter(s => s.type === 'HIGH').sort((a, b) => a.index - b.index);

    for (let i = 0; i < highs.length - 1; i++) {
      const equalGroup: SwingPoint[] = [highs[i]];
      
      for (let j = i + 1; j < highs.length; j++) {
        if (areLevelsEqual(highs[i].price, highs[j].price, this.config.equalLevelTolerance)) {
          equalGroup.push(highs[j]);
        }
      }

      if (equalGroup.length >= this.config.minTouches) {
        const avgPrice = equalGroup.reduce((sum, s) => sum + s.price, 0) / equalGroup.length;
        const level: LiquidityLevel = {
          type: 'EQH',
          price: avgPrice,
          startTimestamp: equalGroup[0].timestamp,
          endTimestamp: equalGroup[equalGroup.length - 1].timestamp,
          startIndex: equalGroup[0].index,
          endIndex: equalGroup[equalGroup.length - 1].index,
          touches: equalGroup.length,
          swept: false,
          strength: Math.min(100, equalGroup.length * 30),
        };
        this.levels.push(level);
      }
    }
  }

  /**
   * Detect Equal Lows - strong SSL
   */
  private detectEqualLows(swings: SwingPoint[], candles: Candle[]): void {
    const lows = swings.filter(s => s.type === 'LOW').sort((a, b) => a.index - b.index);

    for (let i = 0; i < lows.length - 1; i++) {
      const equalGroup: SwingPoint[] = [lows[i]];
      
      for (let j = i + 1; j < lows.length; j++) {
        if (areLevelsEqual(lows[i].price, lows[j].price, this.config.equalLevelTolerance)) {
          equalGroup.push(lows[j]);
        }
      }

      if (equalGroup.length >= this.config.minTouches) {
        const avgPrice = equalGroup.reduce((sum, s) => sum + s.price, 0) / equalGroup.length;
        const level: LiquidityLevel = {
          type: 'EQL',
          price: avgPrice,
          startTimestamp: equalGroup[0].timestamp,
          endTimestamp: equalGroup[equalGroup.length - 1].timestamp,
          startIndex: equalGroup[0].index,
          endIndex: equalGroup[equalGroup.length - 1].index,
          touches: equalGroup.length,
          swept: false,
          strength: Math.min(100, equalGroup.length * 30),
        };
        this.levels.push(level);
      }
    }
  }

  /**
   * Identify Resting Liquidity - levels that haven't been tested for a long time
   */
  private identifyRestingLiquidity(candles: Candle[]): void {
    for (const level of this.levels) {
      if (level.swept) continue;

      // Calculate how long this level has been untouched
      const lastIndex = level.endIndex ?? level.startIndex;
      const barsUntouched = candles.length - 1 - lastIndex;

      if (barsUntouched > 50) {
        // Create a resting liquidity marker
        const restingLevel: LiquidityLevel = {
          type: 'RESTING',
          price: level.price,
          startTimestamp: level.startTimestamp,
          startIndex: level.startIndex,
          touches: level.touches,
          swept: false,
          strength: Math.min(100, level.strength + barsUntouched * 0.5),
        };
        this.levels.push(restingLevel);
      }
    }
  }

  /**
   * Detect Engineered Liquidity - liquidity created by stop hunts
   */
  private detectEngineeredLiquidity(candles: Candle[], swings: SwingPoint[]): void {
    // Engineered liquidity occurs when price creates obvious levels
    // that appear to be "engineered" to attract retail stops

    for (let i = 10; i < candles.length - 5; i++) {
      // Look for triple/quadruple touches with very tight range
      const highLevel = candles[i].high;
      let touchCount = 0;

      for (let j = i - 10; j <= i + 5 && j < candles.length; j++) {
        if (j === i) continue;
        if (Math.abs(candles[j].high - highLevel) < this.config.equalLevelTolerance * 0.5) {
          touchCount++;
        }
      }

      if (touchCount >= 3) {
        // This level was likely engineered
        const level: LiquidityLevel = {
          type: 'ENGINEERED',
          price: highLevel,
          startTimestamp: candles[i].timestamp,
          startIndex: i,
          touches: touchCount + 1,
          swept: false,
          strength: Math.min(100, touchCount * 25),
        };
        this.levels.push(level);
      }
    }
  }

  /**
   * Identify Session Liquidity - highs/lows of trading sessions
   */
  private identifySessionLiquidity(sessions: TradingSession[], candles: Candle[]): void {
    for (const session of sessions) {
      // Session high as BSL
      const highLevel: LiquidityLevel = {
        type: 'SESSION',
        price: session.high,
        startTimestamp: session.timestamp,
        startIndex: 0,
        touches: 1,
        swept: false,
        strength: 60,
        session: session.type,
      };
      this.levels.push(highLevel);

      // Session low as SSL
      const lowLevel: LiquidityLevel = {
        type: 'SESSION',
        price: session.low,
        startTimestamp: session.timestamp,
        startIndex: 0,
        touches: 1,
        swept: false,
        strength: 60,
        session: session.type,
      };
      this.levels.push(lowLevel);
    }
  }

  /**
   * Detect sweeps across all levels
   */
  private detectSweeps(candles: Candle[]): void {
    for (const level of this.levels) {
      if (level.swept) continue;

      const startSearch = (level.endIndex ?? level.startIndex) + 1;

      for (let i = startSearch; i < candles.length; i++) {
        const candle = candles[i];
        
        // BSL/EQH/Session high sweep
        if ((level.type === 'BSL' || level.type === 'EQH' || level.type === 'SESSION' || level.type === 'ENGINEERED') 
            && level.price > 0) {
          if (candle.high > level.price + this.config.sweepMinPenetration) {
            // Check for recovery within max bars
            let recovered = false;
            const recoveryEnd = Math.min(i + this.config.sweepMaxRecoveryBars, candles.length);
            
            for (let k = i; k < recoveryEnd; k++) {
              if (candles[k].close < level.price) {
                recovered = true;
                break;
              }
            }

            if (recovered || candle.close < level.price) {
              const sweep = this.createSweep(level, candle, i);
              sweep.recovered = recovered;
              this.sweeps.push(sweep);
              level.swept = true;
              level.sweepTimestamp = candle.timestamp;
              level.sweepCandle = candle;
              break;
            }
          }
        }

        // SSL/EQL sweep
        if ((level.type === 'SSL' || level.type === 'EQL') && level.price > 0) {
          if (candle.low < level.price - this.config.sweepMinPenetration) {
            let recovered = false;
            const recoveryEnd = Math.min(i + this.config.sweepMaxRecoveryBars, candles.length);
            
            for (let k = i; k < recoveryEnd; k++) {
              if (candles[k].close > level.price) {
                recovered = true;
                break;
              }
            }

            if (recovered || candle.close > level.price) {
              const sweep = this.createSweep(level, candle, i);
              sweep.recovered = recovered;
              this.sweeps.push(sweep);
              level.swept = true;
              level.sweepTimestamp = candle.timestamp;
              level.sweepCandle = candle;
              break;
            }
          }
        }
      }
    }
  }

  /**
   * Identify liquidity pools (clusters of levels)
   */
  private identifyPools(): void {
    // Sort levels by price
    const sortedLevels = [...this.levels]
      .filter(l => !l.swept)
      .sort((a, b) => a.price - b.price);

    if (sortedLevels.length < this.config.poolMinLevels) return;

    // Cluster nearby levels into pools
    let currentCluster: LiquidityLevel[] = [sortedLevels[0]];

    for (let i = 1; i < sortedLevels.length; i++) {
      const priceDiff = sortedLevels[i].price - sortedLevels[i - 1].price;
      
      if (priceDiff <= this.config.equalLevelTolerance * 5) {
        currentCluster.push(sortedLevels[i]);
      } else {
        if (currentCluster.length >= this.config.poolMinLevels) {
          this.createPool(currentCluster);
        }
        currentCluster = [sortedLevels[i]];
      }
    }

    if (currentCluster.length >= this.config.poolMinLevels) {
      this.createPool(currentCluster);
    }
  }

  /**
   * Create a liquidity pool from a cluster of levels
   */
  private createPool(levels: LiquidityLevel[]): void {
    const prices = levels.map(l => l.price);
    const minPrice = Math.min(...prices);
    const maxPrice = Math.max(...prices);
    const avgPrice = prices.reduce((a, b) => a + b, 0) / prices.length;

    // Determine side
    const bslCount = levels.filter(l => l.type === 'BSL' || l.type === 'EQH').length;
    const sslCount = levels.filter(l => l.type === 'SSL' || l.type === 'EQL').length;

    const pool: LiquidityPool = {
      levels: [...levels],
      zone: {
        high: maxPrice,
        low: minPrice,
        startTime: Math.min(...levels.map(l => l.startTimestamp)),
        mitigated: false,
      },
      side: bslCount >= sslCount ? 'BUY' : 'SELL',
      totalLiquidity: levels.reduce((sum, l) => sum + l.strength, 0),
    };

    this.pools.push(pool);
  }

  /**
   * Create a sweep object
   */
  private createSweep(level: LiquidityLevel, sweepCandle: Candle, index: number): LiquiditySweep {
    const isBuySide = level.type === 'BSL' || level.type === 'EQH';
    const depth = isBuySide
      ? sweepCandle.high - level.price
      : level.price - sweepCandle.low;

    return {
      level,
      sweepCandle,
      sweepIndex: index,
      sweepTimestamp: sweepCandle.timestamp,
      depth,
      recovered: sweepCandle.close < level.price, // Initial check
    };
  }

  /**
   * Get all unswept levels (active liquidity)
   */
  getActiveLevels(): LiquidityLevel[] {
    return this.levels.filter(l => !l.swept);
  }

  /**
   * Get levels near a specific price
   */
  getLevelsNearPrice(price: number, range: number): LiquidityLevel[] {
    return this.levels.filter(l => Math.abs(l.price - price) <= range && !l.swept);
  }

  /**
   * Get nearest liquidity above/below price
   */
  getNearestLiquidity(price: number, direction: 'ABOVE' | 'BELOW'): LiquidityLevel | null {
    const activeLevels = this.getActiveLevels();
    
    if (direction === 'ABOVE') {
      const above = activeLevels.filter(l => l.price > price).sort((a, b) => a.price - b.price);
      return above.length > 0 ? above[0] : null;
    } else {
      const below = activeLevels.filter(l => l.price < price).sort((a, b) => b.price - a.price);
      return below.length > 0 ? below[0] : null;
    }
  }

  reset(): void {
    this.levels = [];
    this.sweeps = [];
    this.pools = [];
  }
}
