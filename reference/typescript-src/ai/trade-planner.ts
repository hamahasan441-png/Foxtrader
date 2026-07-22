// ============================================================================
// AI TRADE PLANNER
// Generates a complete trade plan: Bias, Entry, Confirmation, SL, TP1/2/3, RR,
// Probability, Expected Holding Time, Reasoning, Invalidation
// ============================================================================

import { Candle, Direction, Bias, Timeframe } from '../core/types';
import {
  StructureBreak, OrderBlock, FairValueGap, LiquidityLevel, LiquiditySweep,
  SMTDivergence, LITSetup, OTE, SwingPoint, PremiumDiscount,
} from '../core/types';
import { calculateATR, timeframeToMs } from '../core/utils';
import { ProbabilityEngine, ProbabilityResult } from './probability-engine';
import { ConfluenceEngine, ConfluenceResult } from './confluence-engine';

export interface TradePlan {
  symbol: string;
  timeframe: Timeframe;
  bias: Bias;
  direction: Direction;
  entry: number;
  entryType: 'MARKET' | 'LIMIT' | 'STOP';
  confirmation: string[];
  stopLoss: number;
  takeProfits: { level: number; price: number; rr: number; reasoning: string }[];
  riskReward: number;
  probability: number;
  confidence: number;
  grade: string;
  expectedHoldingBars: number;
  expectedHoldingTime: string;
  reasoning: string[];
  invalidation: string;
  invalidationPrice: number;
  warnings: string[];
  generatedAt: number;
}

export interface TradePlannerInput {
  symbol: string;
  timeframe: Timeframe;
  candles: Candle[];
  currentPrice: number;
  bias: Bias;
  structureBreaks: StructureBreak[];
  orderBlocks: OrderBlock[];
  fvgs: FairValueGap[];
  liquidityLevels: LiquidityLevel[];
  sweeps: LiquiditySweep[];
  smtDivergences: SMTDivergence[];
  litSetups: LITSetup[];
  swings: SwingPoint[];
  ote?: OTE;
  premiumDiscount?: PremiumDiscount;
  probabilityResult?: ProbabilityResult;
  confluenceResult?: ConfluenceResult;
}

export class TradePlanner {
  private probabilityEngine: ProbabilityEngine;
  private confluenceEngine: ConfluenceEngine;

  constructor() {
    this.probabilityEngine = new ProbabilityEngine();
    this.confluenceEngine = new ConfluenceEngine();
  }

  /**
   * Generate a complete trade plan
   */
  generatePlan(input: TradePlannerInput): TradePlan | null {
    const { candles, currentPrice, symbol, timeframe } = input;
    if (candles.length < 30) return null;

    // Determine direction from bias (or strongest LIT setup)
    const direction = this.determineDirection(input);
    if (!direction) return null;

    const atr = calculateATR(candles, 14);
    const currentATR = atr[atr.length - 1] || currentPrice * 0.001;

    // Probability & confluence
    const probability = input.probabilityResult || this.probabilityEngine.calculate({
      candles, direction, currentPrice,
      structureBreaks: input.structureBreaks, orderBlocks: input.orderBlocks,
      fvgs: input.fvgs, sweeps: input.sweeps, liquidityLevels: input.liquidityLevels,
      smtDivergences: input.smtDivergences, litSetups: input.litSetups, htfBias: input.bias,
    });

    // Entry
    const { entry, entryType } = this.determineEntry(input, direction, currentPrice);

    // Stop loss
    const stopLoss = this.determineStopLoss(input, direction, entry, currentATR);

    // Take profits (TP1/TP2/TP3)
    const takeProfits = this.determineTakeProfits(input, direction, entry, stopLoss);

    // Risk:Reward (based on TP1)
    const risk = Math.abs(entry - stopLoss);
    const riskReward = risk > 0 && takeProfits.length > 0
      ? Math.abs(takeProfits[takeProfits.length - 1].price - entry) / risk
      : 0;

    // Holding time estimate
    const { bars, timeStr } = this.estimateHoldingTime(input, direction, entry, takeProfits, timeframe, currentATR);

    return {
      symbol,
      timeframe,
      bias: input.bias,
      direction,
      entry,
      entryType,
      confirmation: this.buildConfirmations(input, direction),
      stopLoss,
      takeProfits,
      riskReward,
      probability: probability.scores.probability,
      confidence: probability.confidence,
      grade: probability.grade,
      expectedHoldingBars: bars,
      expectedHoldingTime: timeStr,
      reasoning: this.buildReasoning(input, direction, probability),
      invalidation: this.buildInvalidation(direction, stopLoss),
      invalidationPrice: stopLoss,
      warnings: this.buildWarnings(input, direction, probability, riskReward),
      generatedAt: Date.now(),
    };
  }

  private determineDirection(input: TradePlannerInput): Direction | null {
    // Priority: best LIT setup, then bias
    const bestLit = input.litSetups.sort((a, b) => b.confidence - a.confidence)[0];
    if (bestLit && bestLit.confidence >= 60) return bestLit.direction;
    if (input.bias === 'BULLISH') return 'BULLISH';
    if (input.bias === 'BEARISH') return 'BEARISH';

    // Fall back to most recent structure break
    const lastBreak = input.structureBreaks[input.structureBreaks.length - 1];
    return lastBreak ? lastBreak.direction : null;
  }

  private determineEntry(input: TradePlannerInput, direction: Direction, currentPrice: number): { entry: number; entryType: 'MARKET' | 'LIMIT' | 'STOP' } {
    // Prefer OB or FVG zone entry (limit order into the zone)
    const alignedOB = input.orderBlocks.find(ob => !ob.mitigated && ob.direction === direction);
    const alignedFVG = input.fvgs.find(f => !f.filled && f.direction === direction);

    if (alignedOB && alignedFVG) {
      // OB+FVG confluence - enter at overlap midpoint
      const obMid = (alignedOB.zone.high + alignedOB.zone.low) / 2;
      const fvgMid = (alignedFVG.zone.high + alignedFVG.zone.low) / 2;
      return { entry: (obMid + fvgMid) / 2, entryType: 'LIMIT' };
    }
    if (alignedOB) {
      const entry = direction === 'BULLISH' ? alignedOB.zone.high : alignedOB.zone.low;
      return { entry, entryType: 'LIMIT' };
    }
    if (input.ote && input.ote.direction === direction) {
      return { entry: (input.ote.zone.high + input.ote.zone.low) / 2, entryType: 'LIMIT' };
    }
    if (alignedFVG) {
      return { entry: (alignedFVG.zone.high + alignedFVG.zone.low) / 2, entryType: 'LIMIT' };
    }
    // Default - market entry at current price
    return { entry: currentPrice, entryType: 'MARKET' };
  }

  private determineStopLoss(input: TradePlannerInput, direction: Direction, entry: number, atr: number): number {
    // Place SL beyond most recent protective swing
    const swings = input.swings.filter(s =>
      direction === 'BULLISH' ? s.type === 'LOW' : s.type === 'HIGH'
    ).sort((a, b) => b.index - a.index);

    const buffer = atr * 0.25;
    if (swings.length > 0) {
      const swing = swings[0];
      return direction === 'BULLISH' ? swing.price - buffer : swing.price + buffer;
    }
    // ATR-based fallback
    return direction === 'BULLISH' ? entry - atr * 1.5 : entry + atr * 1.5;
  }

  private determineTakeProfits(input: TradePlannerInput, direction: Direction, entry: number, stopLoss: number): { level: number; price: number; rr: number; reasoning: string }[] {
    const risk = Math.abs(entry - stopLoss);
    const tps: { level: number; price: number; rr: number; reasoning: string }[] = [];

    // TP1 = nearest liquidity or 1.5R
    // TP2 = 3R or next liquidity pool
    // TP3 = 5R or major structural target
    const targetLiquidity = input.liquidityLevels
      .filter(l => !l.swept && (direction === 'BULLISH' ? l.price > entry : l.price < entry))
      .sort((a, b) => direction === 'BULLISH' ? a.price - b.price : b.price - a.price);

    const rrLevels = [1.5, 3, 5];
    for (let i = 0; i < 3; i++) {
      let price: number;
      let reasoning: string;

      if (targetLiquidity[i]) {
        price = targetLiquidity[i].price;
        const rr = Math.abs(price - entry) / risk;
        reasoning = `${targetLiquidity[i].type} liquidity target (${rr.toFixed(1)}R)`;
        tps.push({ level: i + 1, price, rr, reasoning });
      } else {
        price = direction === 'BULLISH' ? entry + risk * rrLevels[i] : entry - risk * rrLevels[i];
        reasoning = `${rrLevels[i]}R fixed target`;
        tps.push({ level: i + 1, price, rr: rrLevels[i], reasoning });
      }
    }

    return tps;
  }

  private estimateHoldingTime(
    input: TradePlannerInput, direction: Direction, entry: number,
    tps: { price: number }[], timeframe: Timeframe, atr: number
  ): { bars: number; timeStr: string } {
    // Estimate bars-to-target based on ATR (average distance covered per bar)
    if (tps.length === 0 || atr === 0) return { bars: 0, timeStr: 'N/A' };

    const finalTarget = tps[tps.length - 1].price;
    const distance = Math.abs(finalTarget - entry);
    // Assume price covers ~0.7 ATR of directional progress per bar on average
    const bars = Math.ceil(distance / (atr * 0.7));

    const tfMs = timeframeToMs(timeframe);
    const totalMs = bars * tfMs;
    return { bars, timeStr: this.formatDuration(totalMs) };
  }

  private formatDuration(ms: number): string {
    const minutes = Math.floor(ms / 60000);
    if (minutes < 60) return `~${minutes} minutes`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `~${hours}h ${minutes % 60}m`;
    const days = Math.floor(hours / 24);
    return `~${days}d ${hours % 24}h`;
  }

  private buildConfirmations(input: TradePlannerInput, direction: Direction): string[] {
    const confirmations: string[] = [];

    const choch = input.structureBreaks.find(b => (b.type === 'CHOCH' || b.type === 'MSS') && b.direction === direction);
    if (choch) confirmations.push(`Wait for ${choch.type} confirmation at ${choch.breakPrice.toFixed(5)}`);

    const ob = input.orderBlocks.find(o => !o.mitigated && o.direction === direction);
    if (ob) confirmations.push(`Price reaction at ${ob.type} zone (${ob.zone.low.toFixed(5)}-${ob.zone.high.toFixed(5)})`);

    const sweep = input.sweeps.find(s => s.recovered);
    if (sweep) confirmations.push(`Liquidity sweep confirmed at ${sweep.level.price.toFixed(5)}`);

    if (input.ote && input.ote.direction === direction) {
      confirmations.push('Price entering OTE zone (0.618-0.786 retracement)');
    }

    confirmations.push('Rejection candle / displacement in trade direction');
    return confirmations;
  }

  private buildReasoning(input: TradePlannerInput, direction: Direction, prob: ProbabilityResult): string[] {
    const reasons: string[] = [];
    reasons.push(`Directional bias: ${direction} (HTF: ${input.bias})`);
    reasons.push(`Probability: ${prob.scores.probability}% | Confidence: ${prob.confidence}% (${prob.grade})`);

    // Top scoring factors
    const topScores = Object.entries(prob.scores)
      .filter(([k]) => k !== 'probability')
      .sort(([, a], [, b]) => b - a)
      .slice(0, 3);
    for (const [key, val] of topScores) {
      reasons.push(`${this.humanize(key)}: ${val}/100`);
    }

    if (input.premiumDiscount) {
      reasons.push(`Price in ${input.premiumDiscount.currentPosition} zone`);
    }

    const bestLit = input.litSetups.filter(l => l.direction === direction).sort((a, b) => b.confidence - a.confidence)[0];
    if (bestLit) reasons.push(`LIT setup: ${bestLit.type.replace(/_/g, ' ')} (${bestLit.confidence}%)`);

    return reasons;
  }

  private buildInvalidation(direction: Direction, stopLoss: number): string {
    return `Plan invalidated if price ${direction === 'BULLISH' ? 'closes below' : 'closes above'} ${stopLoss.toFixed(5)} with displacement, signaling the opposing side has taken control.`;
  }

  private buildWarnings(input: TradePlannerInput, direction: Direction, prob: ProbabilityResult, rr: number): string[] {
    const warnings: string[] = [];
    if (prob.confidence < 55) warnings.push('Below-average confidence - consider reduced size or skip');
    if (input.bias !== 'NEUTRAL' && input.bias !== direction) warnings.push('Trade is COUNTER to HTF bias');
    if (rr < 2) warnings.push(`Risk:Reward below 2.0 (${rr.toFixed(1)})`);
    if (input.premiumDiscount) {
      const pd = input.premiumDiscount.currentPosition;
      if ((direction === 'BULLISH' && pd === 'PREMIUM') || (direction === 'BEARISH' && pd === 'DISCOUNT')) {
        warnings.push('Entering from unfavorable premium/discount zone');
      }
    }
    return warnings;
  }

  private humanize(key: string): string {
    return key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase()).trim();
  }
}
