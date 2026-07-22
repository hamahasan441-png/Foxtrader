// ============================================================================
// AI PROBABILITY ENGINE
// For every setup calculates: Setup Quality, Market Structure Strength,
// Liquidity Quality, SMT Strength, LIT Confirmation, Trend Strength,
// Momentum Score, Volume Score, News Impact Score, Probability %
// Generates 0-100 confidence score with grade.
// ============================================================================

import { Candle, Direction, Bias } from '../core/types';
import {
  StructureBreak, OrderBlock, FairValueGap, LiquiditySweep,
  LiquidityLevel, SMTDivergence, LITSetup,
} from '../core/types';
import {
  calculateEMA, calculateADX, calculateRSI, calculateRelativeVolume, calculateMomentum,
} from '../indicators/technical';
import { calculateATR } from '../core/utils';
import { ConfluenceResult } from './confluence-engine';

export type ProbabilityGrade = 'WEAK' | 'MEDIUM' | 'STRONG' | 'VERY_STRONG' | 'INSTITUTIONAL';

export interface ProbabilityScores {
  setupQuality: number;          // 0-100
  marketStructureStrength: number; // 0-100
  liquidityQuality: number;      // 0-100
  smtStrength: number;           // 0-100
  litConfirmation: number;       // 0-100
  trendStrength: number;         // 0-100
  momentumScore: number;         // 0-100
  volumeScore: number;           // 0-100
  newsImpactScore: number;       // 0-100 (100 = clear, low = high-impact news near)
  probability: number;           // 0-100 final win probability estimate
}

export interface ProbabilityResult {
  direction: Direction;
  scores: ProbabilityScores;
  confidence: number;            // 0-100 overall confidence
  grade: ProbabilityGrade;
  breakdown: { label: string; value: number; weight: number }[];
  summary: string;
}

export interface NewsContext {
  /** Minutes until next high-impact news (Infinity if none nearby) */
  minutesToHighImpact: number;
  /** Whether currently in a news blackout window */
  inBlackout: boolean;
}

export interface ProbabilityInput {
  candles: Candle[];
  direction: Direction;
  currentPrice: number;
  structureBreaks: StructureBreak[];
  orderBlocks: OrderBlock[];
  fvgs: FairValueGap[];
  sweeps: LiquiditySweep[];
  liquidityLevels: LiquidityLevel[];
  smtDivergences: SMTDivergence[];
  litSetups: LITSetup[];
  htfBias: Bias;
  news?: NewsContext;
  confluence?: ConfluenceResult;
}

// Weights for the final confidence blend
const SCORE_WEIGHTS = {
  setupQuality: 0.15,
  marketStructureStrength: 0.15,
  liquidityQuality: 0.12,
  smtStrength: 0.10,
  litConfirmation: 0.12,
  trendStrength: 0.12,
  momentumScore: 0.09,
  volumeScore: 0.06,
  newsImpactScore: 0.09,
};

export class ProbabilityEngine {
  /**
   * Calculate all probability scores for a setup
   */
  calculate(input: ProbabilityInput): ProbabilityResult {
    const { candles, direction } = input;
    const last = candles.length - 1;

    // Precompute indicators
    const ema20 = calculateEMA(candles, 20);
    const ema50 = calculateEMA(candles, 50);
    const ema200 = calculateEMA(candles, 200);
    const { adx, plusDI, minusDI } = calculateADX(candles);
    const rsi = calculateRSI(candles);
    const relVol = calculateRelativeVolume(candles);
    const momentum = calculateMomentum(candles);
    const atr = calculateATR(candles, 14);

    const scores: ProbabilityScores = {
      setupQuality: this.scoreSetupQuality(input),
      marketStructureStrength: this.scoreMarketStructure(input),
      liquidityQuality: this.scoreLiquidity(input),
      smtStrength: this.scoreSMT(input),
      litConfirmation: this.scoreLIT(input),
      trendStrength: this.scoreTrend(direction, ema20, ema50, ema200, adx, plusDI, minusDI, last),
      momentumScore: this.scoreMomentum(direction, rsi, momentum, last),
      volumeScore: this.scoreVolume(relVol, last),
      newsImpactScore: this.scoreNews(input.news),
      probability: 0,
    };

    // Weighted blend for probability
    let weightedSum = 0;
    const breakdown: { label: string; value: number; weight: number }[] = [];
    for (const [key, weight] of Object.entries(SCORE_WEIGHTS)) {
      const value = scores[key as keyof ProbabilityScores];
      weightedSum += value * weight;
      breakdown.push({ label: this.humanize(key), value, weight });
    }

    scores.probability = Math.round(weightedSum);

    // Confidence can be boosted slightly by confluence result if provided
    let confidence = scores.probability;
    if (input.confluence) {
      confidence = Math.round(confidence * 0.7 + input.confluence.totalScore * 0.3);
    }
    confidence = Math.max(0, Math.min(100, confidence));

    return {
      direction,
      scores,
      confidence,
      grade: this.grade(confidence),
      breakdown,
      summary: this.buildSummary(scores, confidence, direction),
    };
  }

  // =========================================================================
  // INDIVIDUAL SCORE CALCULATIONS
  // =========================================================================

  private scoreSetupQuality(input: ProbabilityInput): number {
    // Base on best LIT setup confidence + OB/FVG confluence at price
    let score = 40;
    const bestLit = input.litSetups
      .filter(l => l.direction === input.direction)
      .sort((a, b) => b.confidence - a.confidence)[0];
    if (bestLit) score = Math.max(score, bestLit.confidence);

    // Aligned OB at price boosts quality
    const obAtPrice = input.orderBlocks.find(ob => !ob.mitigated && ob.direction === input.direction &&
      input.currentPrice >= ob.zone.low * 0.998 && input.currentPrice <= ob.zone.high * 1.002);
    if (obAtPrice) score += 10;

    const fvgAtPrice = input.fvgs.find(f => !f.filled && f.direction === input.direction &&
      input.currentPrice >= f.zone.low * 0.998 && input.currentPrice <= f.zone.high * 1.002);
    if (fvgAtPrice) score += 8;

    return Math.min(100, score);
  }

  private scoreMarketStructure(input: ProbabilityInput): number {
    let score = 30;
    const recent = input.structureBreaks.slice(-5);
    if (recent.length === 0) return score;

    // Aligned breaks add points, weighted by type
    for (const brk of recent) {
      if (brk.direction === input.direction) {
        if (brk.type === 'MSS') score += 18;
        else if (brk.type === 'CHOCH') score += 14;
        else if (brk.type === 'BOS') score += 10;
        else score += 4;
      } else {
        score -= 6; // Counter-directional breaks reduce confidence
      }
    }

    // HTF bias alignment
    if (input.htfBias === input.direction) score += 12;
    else if (input.htfBias !== 'NEUTRAL') score -= 8;

    return Math.max(0, Math.min(100, score));
  }

  private scoreLiquidity(input: ProbabilityInput): number {
    let score = 35;

    // Recovered sweeps in favorable direction
    const goodSweeps = input.sweeps.filter(s => {
      if (!s.recovered) return false;
      const buySide = s.level.type === 'BSL' || s.level.type === 'EQH';
      return (buySide && input.direction === 'BEARISH') || (!buySide && input.direction === 'BULLISH');
    });
    score += Math.min(30, goodSweeps.length * 15);

    // Resting liquidity as target (unswept in trade direction)
    const targetLiquidity = input.liquidityLevels.filter(l => {
      if (l.swept) return false;
      return input.direction === 'BULLISH' ? l.price > input.currentPrice : l.price < input.currentPrice;
    });
    if (targetLiquidity.length > 0) {
      const avgStrength = targetLiquidity.reduce((s, l) => s + l.strength, 0) / targetLiquidity.length;
      score += Math.min(25, avgStrength * 0.25);
    }

    return Math.max(0, Math.min(100, score));
  }

  private scoreSMT(input: ProbabilityInput): number {
    const aligned = input.smtDivergences.filter(d => d.direction === input.direction);
    if (aligned.length === 0) return input.smtDivergences.length > 0 ? 20 : 45; // Neutral if no SMT data
    const avgStrength = aligned.reduce((s, d) => s + d.strength, 0) / aligned.length;
    return Math.min(100, 50 + avgStrength * 0.5);
  }

  private scoreLIT(input: ProbabilityInput): number {
    const aligned = input.litSetups.filter(l => l.direction === input.direction);
    if (aligned.length === 0) return 40;
    const best = aligned.sort((a, b) => b.confidence - a.confidence)[0];
    const confirmationCount = best.confirmations.filter(c => c.confirmed).length;
    return Math.min(100, best.confidence * 0.7 + confirmationCount * 6);
  }

  private scoreTrend(dir: Direction, ema20: number[], ema50: number[], ema200: number[],
    adx: number[], plusDI: number[], minusDI: number[], last: number): number {
    let score = 40;

    const trendUp = ema20[last] > ema50[last] && ema50[last] > ema200[last];
    const trendDown = ema20[last] < ema50[last] && ema50[last] < ema200[last];

    if ((dir === 'BULLISH' && trendUp) || (dir === 'BEARISH' && trendDown)) {
      score += 25;
    } else if ((dir === 'BULLISH' && trendDown) || (dir === 'BEARISH' && trendUp)) {
      score -= 15; // Counter-trend
    }

    // ADX strength
    const adxVal = adx[last];
    if (adxVal > 25) {
      const diAligned = (dir === 'BULLISH' && plusDI[last] > minusDI[last]) ||
                        (dir === 'BEARISH' && minusDI[last] > plusDI[last]);
      score += diAligned ? Math.min(25, adxVal * 0.5) : -10;
    }

    return Math.max(0, Math.min(100, score));
  }

  private scoreMomentum(dir: Direction, rsi: number[], momentum: number[], last: number): number {
    let score = 50;
    const rsiVal = rsi[last];
    const momVal = momentum[last];

    // Momentum alignment
    if ((dir === 'BULLISH' && momVal > 0) || (dir === 'BEARISH' && momVal < 0)) {
      score += Math.min(25, Math.abs(momVal) * 5);
    } else {
      score -= Math.min(20, Math.abs(momVal) * 4);
    }

    // RSI positioning (avoid buying overbought / selling oversold)
    if (dir === 'BULLISH') {
      if (rsiVal < 30) score += 15; // Oversold bounce potential
      else if (rsiVal > 70) score -= 15; // Overbought
    } else {
      if (rsiVal > 70) score += 15; // Overbought reversal
      else if (rsiVal < 30) score -= 15; // Oversold
    }

    return Math.max(0, Math.min(100, score));
  }

  private scoreVolume(relVol: number[], last: number): number {
    const rv = relVol[last];
    // Higher relative volume = more conviction
    if (rv >= 2.0) return 90;
    if (rv >= 1.5) return 75;
    if (rv >= 1.2) return 65;
    if (rv >= 1.0) return 55;
    if (rv >= 0.7) return 40;
    return 25;
  }

  private scoreNews(news?: NewsContext): number {
    if (!news) return 70; // Neutral - assume clear
    if (news.inBlackout) return 10; // High-impact news imminent - dangerous
    if (news.minutesToHighImpact < 15) return 25;
    if (news.minutesToHighImpact < 30) return 45;
    if (news.minutesToHighImpact < 60) return 65;
    return 90; // Clear
  }

  // =========================================================================
  // GRADING & UTILITIES
  // =========================================================================

  grade(confidence: number): ProbabilityGrade {
    if (confidence >= 85) return 'INSTITUTIONAL';
    if (confidence >= 72) return 'VERY_STRONG';
    if (confidence >= 58) return 'STRONG';
    if (confidence >= 42) return 'MEDIUM';
    return 'WEAK';
  }

  private humanize(key: string): string {
    return key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase()).trim();
  }

  private buildSummary(scores: ProbabilityScores, confidence: number, direction: Direction): string {
    const grade = this.grade(confidence);
    const top = Object.entries(scores)
      .filter(([k]) => k !== 'probability')
      .sort(([, a], [, b]) => b - a)
      .slice(0, 3)
      .map(([k]) => this.humanize(k));
    return `${direction} setup: ${grade} (${confidence}%). Strongest: ${top.join(', ')}. Win probability: ${scores.probability}%`;
  }
}
