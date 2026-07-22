// ============================================================================
// ICT CONCEPTS MODULE
// OTE, Judas Swing, Kill Zones, Silver Bullet, Power of Three (AMD),
// SMT Divergence, Turtle Soup, NDOG, WOG, Daily Bias, Premium/Discount
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  OTE,
  JudasSwing,
  KillZone,
  KillZoneType,
  SilverBullet,
  PowerOfThree,
  TurtleSoup,
  OpeningGap,
  PremiumDiscount,
  PDAType,
  Direction,
  Bias,
  SwingPoint,
  LiquidityLevel,
  FairValueGap,
  PriceZone,
  SessionType,
  TradingSession,
} from '../../core/types';
import { getFibLevels, findSwingPoints } from '../../core/utils';
import { TradingEventBus } from '../../core/event-bus';

export interface ICTConfig {
  oteFibLevels: number[];
  killZones: Record<KillZoneType, { start: string; end: string }>;
  silverBulletWindows: Record<string, { start: string; end: string }>;
  turtleSoupLookback: number;
  ndogLookback: number;
  premiumDiscountPeriod: number;
}

const DEFAULT_CONFIG: ICTConfig = {
  oteFibLevels: [0.618, 0.705, 0.786],
  killZones: {
    ASIAN: { start: '20:00', end: '00:00' },
    LONDON_OPEN: { start: '02:00', end: '05:00' },
    NY_OPEN: { start: '07:00', end: '10:00' },
    LONDON_CLOSE: { start: '10:00', end: '12:00' },
    NY_CLOSE: { start: '15:00', end: '16:00' },
  },
  silverBulletWindows: {
    LONDON: { start: '03:00', end: '04:00' },
    NY_AM: { start: '10:00', end: '11:00' },
    NY_PM: { start: '14:00', end: '15:00' },
  },
  turtleSoupLookback: 20,
  ndogLookback: 1,
  premiumDiscountPeriod: 50,
};


export class ICTConceptsAnalyzer {
  private config: ICTConfig;
  private eventBus?: TradingEventBus;

  constructor(config: Partial<ICTConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Identify Optimal Trade Entry (OTE) zone
   * The 61.8%-78.6% Fibonacci retracement of a swing
   */
  findOTE(swingHigh: SwingPoint, swingLow: SwingPoint, direction: Direction): OTE {
    const fibLevels = getFibLevels(swingHigh.price, swingLow.price);
    const oteLevels = this.config.oteFibLevels.map(level => ({
      level,
      price: direction === 'BULLISH'
        ? swingLow.price + (swingHigh.price - swingLow.price) * (1 - level)
        : swingHigh.price - (swingHigh.price - swingLow.price) * (1 - level),
    }));

    const zonePrices = oteLevels.map(l => l.price);
    const zone: PriceZone = {
      high: Math.max(...zonePrices),
      low: Math.min(...zonePrices),
      startTime: swingLow.timestamp,
      mitigated: false,
    };

    return {
      direction,
      fibLevels: oteLevels,
      swingHigh,
      swingLow,
      zone,
      timestamp: Math.max(swingHigh.timestamp, swingLow.timestamp),
      confluences: [],
    };
  }

  /**
   * Detect Judas Swing
   * A fake-out move designed to sweep liquidity before the true move
   */
  detectJudasSwing(
    candles: Candle[],
    sessions: TradingSession[],
    liquidityLevels: LiquidityLevel[]
  ): JudasSwing[] {
    const judasSwings: JudasSwing[] = [];

    for (const session of sessions) {
      if (!session.isActive) continue;

      // Find candles within this session's kill zone
      const sessionCandles = this.getCandlesInTimeWindow(
        candles,
        session.timestamp,
        session.timestamp + 3600000 // First hour
      );

      if (sessionCandles.length < 3) continue;

      // Check for a sweep at session open followed by reversal
      const firstMove = sessionCandles[0];
      const isBullishFake = firstMove.close > firstMove.open;

      // Check if this move swept liquidity
      for (const level of liquidityLevels) {
        if (level.swept) continue;

        const swept = isBullishFake
          ? firstMove.high > level.price && level.type === 'BSL'
          : firstMove.low < level.price && level.type === 'SSL';

        if (swept) {
          // Check for reversal in subsequent candles
          const reversalFound = sessionCandles.slice(1).some(c =>
            isBullishFake ? c.close < firstMove.open : c.close > firstMove.open
          );

          if (reversalFound) {
            judasSwings.push({
              direction: isBullishFake ? 'BULLISH' : 'BEARISH',
              trueDirection: isBullishFake ? 'BEARISH' : 'BULLISH',
              sweepLevel: level,
              timestamp: firstMove.timestamp,
              session: session.type,
              magnitude: Math.abs(firstMove.high - firstMove.low),
            });
          }
        }
      }
    }

    return judasSwings;
  }

  /**
   * Identify active Kill Zones based on current time
   */
  getActiveKillZones(currentTimestamp: number): KillZone[] {
    const activeZones: KillZone[] = [];
    const date = new Date(currentTimestamp);
    const currentHour = date.getUTCHours();
    const currentMinute = date.getUTCMinutes();
    const currentTime = currentHour * 60 + currentMinute;

    for (const [type, window] of Object.entries(this.config.killZones)) {
      const [startH, startM] = window.start.split(':').map(Number);
      const [endH, endM] = window.end.split(':').map(Number);
      const startTime = startH * 60 + startM;
      const endTime = endH * 60 + endM;

      let isActive = false;
      if (startTime <= endTime) {
        isActive = currentTime >= startTime && currentTime <= endTime;
      } else {
        // Wraps midnight
        isActive = currentTime >= startTime || currentTime <= endTime;
      }

      if (isActive) {
        activeZones.push({
          type: type as KillZoneType,
          startTime: window.start,
          endTime: window.end,
          high: 0,
          low: 0,
          bias: 'NEUTRAL',
          timestamp: currentTimestamp,
        });
      }
    }

    return activeZones;
  }


  /**
   * Detect Silver Bullet setup
   * FVG formed during specific time windows (10-11 NY, 14-15 NY, 3-4 London)
   */
  detectSilverBullet(candles: Candle[], fvgs: FairValueGap[]): SilverBullet[] {
    const bullets: SilverBullet[] = [];

    for (const fvg of fvgs) {
      if (fvg.filled) continue;

      const fvgTime = new Date(fvg.timestamp);
      const utcHour = fvgTime.getUTCHours();
      const utcMinute = fvgTime.getUTCMinutes();
      const timeMinutes = utcHour * 60 + utcMinute;

      for (const [session, window] of Object.entries(this.config.silverBulletWindows)) {
        const [startH, startM] = window.start.split(':').map(Number);
        const [endH, endM] = window.end.split(':').map(Number);
        const startTime = startH * 60 + startM;
        const endTime = endH * 60 + endM;

        if (timeMinutes >= startTime && timeMinutes <= endTime) {
          bullets.push({
            session: session as 'LONDON' | 'NY_AM' | 'NY_PM',
            direction: fvg.direction,
            fvg,
            timestamp: fvg.timestamp,
            window,
          });
        }
      }
    }

    return bullets;
  }

  /**
   * Detect Power of Three (AMD) pattern
   * Accumulation → Manipulation → Distribution
   */
  detectPowerOfThree(candles: Candle[], sessions: TradingSession[]): PowerOfThree[] {
    const patterns: PowerOfThree[] = [];

    for (const session of sessions) {
      const sessionCandles = this.getCandlesInTimeWindow(
        candles,
        session.timestamp,
        session.timestamp + (session.endHourUTC - session.startHourUTC) * 3600000
      );

      if (sessionCandles.length < 10) continue;

      // Phase 1: Accumulation (tight range, first 1/3 of session)
      const thirdLen = Math.floor(sessionCandles.length / 3);
      const accumCandles = sessionCandles.slice(0, thirdLen);
      const manipCandles = sessionCandles.slice(thirdLen, thirdLen * 2);
      const distCandles = sessionCandles.slice(thirdLen * 2);

      if (accumCandles.length === 0 || manipCandles.length === 0 || distCandles.length === 0) continue;

      const accumHigh = Math.max(...accumCandles.map(c => c.high));
      const accumLow = Math.min(...accumCandles.map(c => c.low));
      const accumRange = accumHigh - accumLow;

      // Phase 2: Manipulation (fake breakout of accumulation range)
      const manipHigh = Math.max(...manipCandles.map(c => c.high));
      const manipLow = Math.min(...manipCandles.map(c => c.low));

      // Phase 3: Distribution (true move)
      const distHigh = Math.max(...distCandles.map(c => c.high));
      const distLow = Math.min(...distCandles.map(c => c.low));
      const distClose = distCandles[distCandles.length - 1].close;

      // Determine direction
      let direction: Direction;
      if (manipLow < accumLow && distClose > accumHigh) {
        direction = 'BULLISH'; // Swept lows, distributed higher
      } else if (manipHigh > accumHigh && distClose < accumLow) {
        direction = 'BEARISH'; // Swept highs, distributed lower
      } else {
        continue;
      }

      patterns.push({
        phase: 'DISTRIBUTION', // We detect after full pattern
        direction,
        accumRange: {
          high: accumHigh,
          low: accumLow,
          startTime: accumCandles[0].timestamp,
          mitigated: false,
        },
        manipLevel: {
          price: direction === 'BULLISH' ? manipLow : manipHigh,
          timestamp: manipCandles[0].timestamp,
          strength: 80,
        },
        distTarget: {
          price: distClose,
          timestamp: distCandles[distCandles.length - 1].timestamp,
          strength: 70,
        },
        timestamp: accumCandles[0].timestamp,
        session: session.type,
      });
    }

    return patterns;
  }


  /**
   * Detect Turtle Soup setup
   * False breakout of a previous high/low (20-bar default)
   */
  detectTurtleSoup(candles: Candle[], liquidityLevels: LiquidityLevel[]): TurtleSoup[] {
    const setups: TurtleSoup[] = [];
    const lookback = this.config.turtleSoupLookback;

    for (let i = lookback; i < candles.length; i++) {
      const candle = candles[i];

      // Find highest high in lookback
      let periodHigh = -Infinity;
      let periodLow = Infinity;
      for (let j = i - lookback; j < i; j++) {
        periodHigh = Math.max(periodHigh, candles[j].high);
        periodLow = Math.min(periodLow, candles[j].low);
      }

      // Bearish Turtle Soup: breaks above period high then closes below
      if (candle.high > periodHigh && candle.close < periodHigh) {
        setups.push({
          direction: 'BEARISH',
          level: {
            type: 'BSL',
            price: periodHigh,
            startTimestamp: candles[i - lookback].timestamp,
            startIndex: i - lookback,
            touches: 1,
            swept: true,
            sweepTimestamp: candle.timestamp,
            strength: 70,
          },
          sweepCandle: candle,
          entryPrice: periodHigh, // Enter at the broken level
          stopLoss: candle.high, // Stop above the wick
          timestamp: candle.timestamp,
        });
      }

      // Bullish Turtle Soup: breaks below period low then closes above
      if (candle.low < periodLow && candle.close > periodLow) {
        setups.push({
          direction: 'BULLISH',
          level: {
            type: 'SSL',
            price: periodLow,
            startTimestamp: candles[i - lookback].timestamp,
            startIndex: i - lookback,
            touches: 1,
            swept: true,
            sweepTimestamp: candle.timestamp,
            strength: 70,
          },
          sweepCandle: candle,
          entryPrice: periodLow,
          stopLoss: candle.low,
          timestamp: candle.timestamp,
        });
      }
    }

    return setups;
  }

  /**
   * Detect New Day Opening Gap (NDOG) and Weekly Opening Gap (WOG)
   */
  detectOpeningGaps(candles: Candle[]): OpeningGap[] {
    const gaps: OpeningGap[] = [];

    for (let i = 1; i < candles.length; i++) {
      const prev = candles[i - 1];
      const curr = candles[i];

      const prevDate = new Date(prev.timestamp);
      const currDate = new Date(curr.timestamp);

      // New Day Opening Gap
      if (prevDate.getUTCDate() !== currDate.getUTCDate()) {
        if (curr.open !== prev.close) {
          const high = Math.max(curr.open, prev.close);
          const low = Math.min(curr.open, prev.close);
          
          gaps.push({
            type: 'NDOG',
            high,
            low,
            midpoint: (high + low) / 2,
            filled: false,
            fillPercentage: 0,
            timestamp: curr.timestamp,
          });
        }
      }

      // Weekly Opening Gap
      if (prevDate.getUTCDay() === 5 && currDate.getUTCDay() === 1) {
        if (curr.open !== prev.close) {
          const high = Math.max(curr.open, prev.close);
          const low = Math.min(curr.open, prev.close);
          
          gaps.push({
            type: 'WOG',
            high,
            low,
            midpoint: (high + low) / 2,
            filled: false,
            fillPercentage: 0,
            timestamp: curr.timestamp,
          });
        }
      }
    }

    // Track fill status
    for (const gap of gaps) {
      const gapIndex = candles.findIndex(c => c.timestamp >= gap.timestamp);
      if (gapIndex === -1) continue;

      for (let i = gapIndex; i < candles.length; i++) {
        if (candles[i].low <= gap.low && candles[i].high >= gap.high) {
          gap.filled = true;
          gap.fillPercentage = 100;
          break;
        }
      }
    }

    return gaps;
  }


  /**
   * Calculate Daily Bias using multi-factor analysis
   */
  calculateDailyBias(candles: Candle[], swings: SwingPoint[]): Bias {
    if (candles.length < 20) return 'NEUTRAL';

    let bullishFactors = 0;
    let bearishFactors = 0;

    // Factor 1: Recent structure (HH/HL vs LL/LH)
    const recentHighs = swings.filter(s => s.type === 'HIGH').slice(-3);
    const recentLows = swings.filter(s => s.type === 'LOW').slice(-3);

    if (recentHighs.length >= 2) {
      if (recentHighs[recentHighs.length - 1].price > recentHighs[recentHighs.length - 2].price) {
        bullishFactors++;
      } else {
        bearishFactors++;
      }
    }

    if (recentLows.length >= 2) {
      if (recentLows[recentLows.length - 1].price > recentLows[recentLows.length - 2].price) {
        bullishFactors++;
      } else {
        bearishFactors++;
      }
    }

    // Factor 2: Position relative to previous day range
    const lastCandle = candles[candles.length - 1];
    const prevDayHigh = Math.max(...candles.slice(-20, -1).map(c => c.high));
    const prevDayLow = Math.min(...candles.slice(-20, -1).map(c => c.low));
    const midpoint = (prevDayHigh + prevDayLow) / 2;

    if (lastCandle.close > midpoint) bullishFactors++;
    else bearishFactors++;

    // Factor 3: Close position relative to open
    const dayOpen = candles[candles.length - 20]?.open ?? lastCandle.open;
    if (lastCandle.close > dayOpen) bullishFactors++;
    else bearishFactors++;

    if (bullishFactors > bearishFactors + 1) return 'BULLISH';
    if (bearishFactors > bullishFactors + 1) return 'BEARISH';
    return 'NEUTRAL';
  }

  /**
   * Calculate Premium/Discount zones
   * Premium = above 50% (equilibrium), Discount = below 50%
   */
  calculatePremiumDiscount(candles: Candle[]): PremiumDiscount {
    const period = Math.min(this.config.premiumDiscountPeriod, candles.length);
    const recentCandles = candles.slice(-period);

    const highest = Math.max(...recentCandles.map(c => c.high));
    const lowest = Math.min(...recentCandles.map(c => c.low));
    const equilibrium = (highest + lowest) / 2;
    const currentPrice = candles[candles.length - 1].close;

    const range = highest - lowest;
    const premiumThreshold = equilibrium + range * 0.0; // Above equilibrium
    const discountThreshold = equilibrium - range * 0.0; // Below equilibrium

    return {
      equilibrium,
      premiumZone: {
        high: highest,
        low: equilibrium,
        startTime: recentCandles[0].timestamp,
        mitigated: false,
      },
      discountZone: {
        high: equilibrium,
        low: lowest,
        startTime: recentCandles[0].timestamp,
        mitigated: false,
      },
      currentPosition: currentPrice >= equilibrium ? 'PREMIUM' : 'DISCOUNT',
      percentageFromEquilibrium: range > 0
        ? ((currentPrice - equilibrium) / (range / 2)) * 100
        : 0,
    };
  }

  /**
   * Get candles within a time window
   */
  private getCandlesInTimeWindow(candles: Candle[], startTime: number, endTime: number): Candle[] {
    return candles.filter(c => c.timestamp >= startTime && c.timestamp <= endTime);
  }
}
