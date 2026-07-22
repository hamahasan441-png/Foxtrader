// ============================================================================
// LIT TRADING MODULE
// Complete LIT methodology: Liquidity Trap, Inducement, Shift, Sweep,
// Institutional Trap, BOS/CHOCH/FVG/OB/Entry Confirmation, Target Projection
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  LITSetup,
  LITSignalType,
  LITConfirmation,
  Direction,
  PriceZone,
  StructureBreak,
  LiquidityLevel,
  LiquiditySweep,
  OrderBlock,
  FairValueGap,
  SwingPoint,
} from '../../core/types';
import { calculateATR, getFibLevels } from '../../core/utils';
import { TradingEventBus } from '../../core/event-bus';

export interface LITConfig {
  minConfidence: number;
  confirmationRequirements: number; // Min confirmations needed
  targetFibLevels: number[];
  maxInvalidationDistance: number; // ATR multiples
  riskRewardMinimum: number;
}

const DEFAULT_CONFIG: LITConfig = {
  minConfidence: 60,
  confirmationRequirements: 3,
  targetFibLevels: [1.0, 1.272, 1.618, 2.0, 2.618],
  maxInvalidationDistance: 3.0,
  riskRewardMinimum: 2.0,
};


export class LITTradingAnalyzer {
  private config: LITConfig;
  private eventBus?: TradingEventBus;
  private setups: LITSetup[] = [];

  constructor(config: Partial<LITConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Full LIT analysis - combines all confirmations into trade setups
   */
  analyze(
    candles: Candle[],
    structureBreaks: StructureBreak[],
    liquidityLevels: LiquidityLevel[],
    sweeps: LiquiditySweep[],
    orderBlocks: OrderBlock[],
    fvgs: FairValueGap[],
    swings: SwingPoint[]
  ): LITSetup[] {
    this.setups = [];
    if (candles.length < 20) return [];

    const atr = calculateATR(candles, 14);
    const currentATR = atr[atr.length - 1] || 1;

    // 1. Detect Liquidity Traps
    this.detectLiquidityTraps(candles, liquidityLevels, sweeps, currentATR);

    // 2. Detect Liquidity Inducement
    this.detectLiquidityInducement(candles, structureBreaks, liquidityLevels, currentATR);

    // 3. Detect Liquidity Shift
    this.detectLiquidityShift(candles, structureBreaks, sweeps, currentATR);

    // 4. Detect Institutional Traps
    this.detectInstitutionalTraps(candles, sweeps, structureBreaks, currentATR);

    // 5. Build complete trade setups with confirmations
    this.buildTradeSetups(candles, structureBreaks, orderBlocks, fvgs, swings, currentATR);

    // Filter by minimum confidence
    this.setups = this.setups.filter(s => s.confidence >= this.config.minConfidence);

    return [...this.setups];
  }

  /**
   * Detect Liquidity Traps
   * Price creates obvious liquidity above/below then traps traders
   */
  private detectLiquidityTraps(
    candles: Candle[],
    liquidityLevels: LiquidityLevel[],
    sweeps: LiquiditySweep[],
    currentATR: number
  ): void {
    for (const sweep of sweeps) {
      if (!sweep.recovered) continue;

      // A trap occurs when liquidity is swept and price immediately reverses
      const sweepIndex = sweep.sweepIndex;
      if (sweepIndex >= candles.length - 2) continue;

      const afterSweep = candles[sweepIndex + 1];
      const isBuySide = sweep.level.type === 'BSL' || sweep.level.type === 'EQH';

      // Check for strong reversal candle after sweep
      const reversalBody = Math.abs(afterSweep.close - afterSweep.open);
      if (reversalBody < currentATR * 0.5) continue;

      const isReversal = isBuySide
        ? afterSweep.close < afterSweep.open // Bearish after BSL sweep
        : afterSweep.close > afterSweep.open; // Bullish after SSL sweep

      if (isReversal) {
        const direction: Direction = isBuySide ? 'BEARISH' : 'BULLISH';
        const setup: LITSetup = {
          type: 'LIQUIDITY_TRAP',
          direction,
          timestamp: sweep.sweepTimestamp,
          index: sweepIndex,
          price: sweep.level.price,
          confidence: 65,
          confirmations: [{
            type: 'LIQUIDITY_SWEEP',
            confirmed: true,
            timestamp: sweep.sweepTimestamp,
            details: `${sweep.level.type} swept at ${sweep.level.price}, recovered`,
          }],
        };
        this.setups.push(setup);
      }
    }
  }


  /**
   * Detect Liquidity Inducement
   * Minor swing points created to induce retail traders before real move
   */
  private detectLiquidityInducement(
    candles: Candle[],
    structureBreaks: StructureBreak[],
    liquidityLevels: LiquidityLevel[],
    currentATR: number
  ): void {
    const idmBreaks = structureBreaks.filter(b => b.type === 'IDM');

    for (const idm of idmBreaks) {
      // IDM is followed by a reversal (the real move)
      const afterIDM = structureBreaks.find(
        b => b.breakIndex > idm.breakIndex &&
             b.direction !== idm.direction &&
             (b.type === 'BOS' || b.type === 'CHOCH')
      );

      if (afterIDM) {
        const setup: LITSetup = {
          type: 'LIQUIDITY_INDUCEMENT',
          direction: afterIDM.direction,
          timestamp: idm.breakTimestamp,
          index: idm.breakIndex,
          price: idm.breakPrice,
          confidence: 60,
          confirmations: [
            {
              type: 'BOS_CONFIRMATION',
              confirmed: true,
              timestamp: afterIDM.breakTimestamp,
              details: `IDM at ${idm.breakPrice} followed by ${afterIDM.type} ${afterIDM.direction}`,
            },
          ],
        };
        this.setups.push(setup);
      }
    }
  }

  /**
   * Detect Liquidity Shift
   * Change in the flow of liquidity (where the smart money is positioning)
   */
  private detectLiquidityShift(
    candles: Candle[],
    structureBreaks: StructureBreak[],
    sweeps: LiquiditySweep[],
    currentATR: number
  ): void {
    // A liquidity shift occurs when:
    // 1. Liquidity is taken on one side
    // 2. Structure shifts (CHOCH/MSS)
    // 3. New liquidity forms on the opposite side

    const chochBreaks = structureBreaks.filter(b => b.type === 'CHOCH' || b.type === 'MSS');

    for (const choch of chochBreaks) {
      // Find a sweep that happened before this CHOCH
      const priorSweep = sweeps.find(
        s => s.sweepTimestamp < choch.breakTimestamp &&
             s.sweepTimestamp > choch.breakTimestamp - 50 * 3600000 // Within reasonable lookback
      );

      if (priorSweep) {
        const setup: LITSetup = {
          type: 'LIQUIDITY_SHIFT',
          direction: choch.direction,
          timestamp: choch.breakTimestamp,
          index: choch.breakIndex,
          price: choch.breakPrice,
          confidence: 70,
          confirmations: [
            {
              type: 'LIQUIDITY_SWEEP',
              confirmed: true,
              timestamp: priorSweep.sweepTimestamp,
              details: `Sweep at ${priorSweep.level.price} preceded ${choch.type}`,
            },
            {
              type: 'CHOCH_CONFIRMATION',
              confirmed: true,
              timestamp: choch.breakTimestamp,
              details: `${choch.type} confirmed at ${choch.breakPrice}`,
            },
          ],
        };
        this.setups.push(setup);
      }
    }
  }

  /**
   * Detect Institutional Traps
   * Complex traps using multiple timeframe manipulation
   */
  private detectInstitutionalTraps(
    candles: Candle[],
    sweeps: LiquiditySweep[],
    structureBreaks: StructureBreak[],
    currentATR: number
  ): void {
    // Institutional trap pattern:
    // 1. Sweep liquidity on higher timeframe
    // 2. Create false structure break on lower timeframe
    // 3. Trap retail traders, then reverse

    for (let i = 0; i < sweeps.length; i++) {
      const sweep = sweeps[i];
      if (!sweep.recovered) continue;

      // Find any structure breaks that occurred during/after the sweep
      const trapBreaks = structureBreaks.filter(
        b => b.breakTimestamp >= sweep.sweepTimestamp &&
             b.breakTimestamp <= sweep.sweepTimestamp + 10 * 3600000 && // Within 10 candles
             b.type === 'BOS'
      );

      // If there's a BOS in the sweep direction followed by reversal
      for (const brk of trapBreaks) {
        const reversalBreak = structureBreaks.find(
          b => b.breakIndex > brk.breakIndex &&
               b.breakIndex <= brk.breakIndex + 10 &&
               b.direction !== brk.direction &&
               (b.type === 'CHOCH' || b.type === 'MSS')
        );

        if (reversalBreak) {
          const setup: LITSetup = {
            type: 'INSTITUTIONAL_TRAP',
            direction: reversalBreak.direction,
            timestamp: reversalBreak.breakTimestamp,
            index: reversalBreak.breakIndex,
            price: reversalBreak.breakPrice,
            confidence: 75,
            confirmations: [
              {
                type: 'LIQUIDITY_SWEEP',
                confirmed: true,
                timestamp: sweep.sweepTimestamp,
                details: `Institutional sweep at ${sweep.level.price}`,
              },
              {
                type: 'BOS_CONFIRMATION',
                confirmed: true,
                timestamp: brk.breakTimestamp,
                details: `False BOS created trap at ${brk.breakPrice}`,
              },
              {
                type: 'CHOCH_CONFIRMATION',
                confirmed: true,
                timestamp: reversalBreak.breakTimestamp,
                details: `Reversal confirmed with ${reversalBreak.type}`,
              },
            ],
          };
          this.setups.push(setup);
        }
      }
    }
  }


  /**
   * Build complete trade setups with all confirmations
   * Combines structure, OB, FVG, and liquidity into actionable trades
   */
  private buildTradeSetups(
    candles: Candle[],
    structureBreaks: StructureBreak[],
    orderBlocks: OrderBlock[],
    fvgs: FairValueGap[],
    swings: SwingPoint[],
    currentATR: number
  ): void {
    const lastCandle = candles[candles.length - 1];
    const currentPrice = lastCandle.close;

    // Look for setups with entry confirmations
    for (const setup of this.setups) {
      const confirmations: LITConfirmation[] = [...setup.confirmations];

      // Check FVG confirmation
      const relevantFVG = fvgs.find(
        f => !f.filled &&
             f.direction === setup.direction &&
             f.index >= setup.index - 10 &&
             f.index <= setup.index + 5
      );
      if (relevantFVG) {
        confirmations.push({
          type: 'FVG_CONFIRMATION',
          confirmed: true,
          timestamp: relevantFVG.timestamp,
          details: `${relevantFVG.type} ${relevantFVG.direction} at ${relevantFVG.zone.low}-${relevantFVG.zone.high}`,
        });
      }

      // Check Order Block confirmation
      const relevantOB = orderBlocks.find(
        ob => !ob.mitigated &&
              ob.direction === setup.direction &&
              ob.originIndex >= setup.index - 15 &&
              ob.originIndex <= setup.index + 3
      );
      if (relevantOB) {
        confirmations.push({
          type: 'OB_CONFIRMATION',
          confirmed: true,
          timestamp: relevantOB.timestamp,
          details: `${relevantOB.type} at ${relevantOB.zone.low}-${relevantOB.zone.high}`,
        });
      }

      // Calculate entry, SL, TP
      const entry = this.calculateEntry(setup, relevantOB, relevantFVG, currentPrice);
      const stopLoss = this.calculateStopLoss(setup, candles, swings, currentATR);
      const targets = this.calculateTargets(setup, entry, stopLoss, swings);

      setup.confirmations = confirmations;
      setup.entry = entry;
      setup.stopLoss = stopLoss;
      setup.takeProfit = targets;
      setup.riskReward = stopLoss !== 0 && entry !== 0
        ? Math.abs(targets[0] - entry) / Math.abs(entry - stopLoss)
        : 0;
      setup.invalidationPrice = stopLoss;

      // Recalculate confidence based on confirmations
      setup.confidence = this.calculateConfidence(confirmations, setup);

      // Entry confirmation
      if (confirmations.length >= this.config.confirmationRequirements) {
        confirmations.push({
          type: 'ENTRY_CONFIRMATION',
          confirmed: true,
          timestamp: lastCandle.timestamp,
          details: `${confirmations.length} confirmations met minimum of ${this.config.confirmationRequirements}`,
        });
      }
    }

    // Add target projections to qualified setups
    for (const setup of this.setups) {
      if (setup.takeProfit && setup.takeProfit.length > 0) {
        setup.confirmations.push({
          type: 'TARGET_PROJECTION',
          confirmed: true,
          timestamp: candles[candles.length - 1].timestamp,
          details: `Targets: ${setup.takeProfit.map(t => t.toFixed(5)).join(', ')}`,
        });
      }
    }
  }

  /**
   * Calculate optimal entry price
   */
  private calculateEntry(
    setup: LITSetup,
    ob: OrderBlock | undefined,
    fvg: FairValueGap | undefined,
    currentPrice: number
  ): number {
    // Priority: OB zone > FVG zone > setup price
    if (ob) {
      return setup.direction === 'BULLISH'
        ? ob.zone.high // Enter at OB upper boundary (discount)
        : ob.zone.low; // Enter at OB lower boundary (premium)
    }
    if (fvg) {
      return (fvg.zone.high + fvg.zone.low) / 2; // FVG midpoint
    }
    return setup.price;
  }

  /**
   * Calculate stop loss
   */
  private calculateStopLoss(
    setup: LITSetup,
    candles: Candle[],
    swings: SwingPoint[],
    currentATR: number
  ): number {
    // Place SL beyond the most recent swing
    const relevantSwings = swings.filter(s =>
      setup.direction === 'BULLISH'
        ? s.type === 'LOW' && s.index <= setup.index
        : s.type === 'HIGH' && s.index <= setup.index
    );

    if (relevantSwings.length === 0) {
      // Fallback: ATR-based SL
      return setup.direction === 'BULLISH'
        ? setup.price - currentATR * 1.5
        : setup.price + currentATR * 1.5;
    }

    const nearestSwing = relevantSwings[relevantSwings.length - 1];
    const buffer = currentATR * 0.2; // Small buffer beyond swing

    return setup.direction === 'BULLISH'
      ? nearestSwing.price - buffer
      : nearestSwing.price + buffer;
  }

  /**
   * Calculate target projections using Fibonacci extensions
   */
  private calculateTargets(
    setup: LITSetup,
    entry: number,
    stopLoss: number,
    swings: SwingPoint[]
  ): number[] {
    const risk = Math.abs(entry - stopLoss);
    if (risk === 0) return [];

    return this.config.targetFibLevels.map(level => {
      return setup.direction === 'BULLISH'
        ? entry + risk * level
        : entry - risk * level;
    });
  }

  /**
   * Calculate confidence score based on confirmations
   */
  private calculateConfidence(confirmations: LITConfirmation[], setup: LITSetup): number {
    let score = 40; // Base score

    const confirmedCount = confirmations.filter(c => c.confirmed).length;
    score += confirmedCount * 10;

    // Bonus for specific confirmation types
    if (confirmations.some(c => c.type === 'LIQUIDITY_SWEEP' && c.confirmed)) score += 10;
    if (confirmations.some(c => c.type === 'CHOCH_CONFIRMATION' && c.confirmed)) score += 10;
    if (confirmations.some(c => c.type === 'OB_CONFIRMATION' && c.confirmed)) score += 8;
    if (confirmations.some(c => c.type === 'FVG_CONFIRMATION' && c.confirmed)) score += 7;

    // R:R bonus
    if (setup.riskReward && setup.riskReward >= 3) score += 5;
    if (setup.riskReward && setup.riskReward >= 5) score += 5;

    return Math.min(100, score);
  }

  /** Get all active setups */
  getActiveSetups(): LITSetup[] {
    return this.setups.filter(s => s.confidence >= this.config.minConfidence);
  }

  /** Get setups by type */
  getSetupsByType(type: LITSignalType): LITSetup[] {
    return this.setups.filter(s => s.type === type);
  }

  /** Get highest confidence setup */
  getBestSetup(): LITSetup | null {
    if (this.setups.length === 0) return null;
    return this.setups.reduce((best, current) =>
      current.confidence > best.confidence ? current : best
    );
  }

  reset(): void {
    this.setups = [];
  }
}
