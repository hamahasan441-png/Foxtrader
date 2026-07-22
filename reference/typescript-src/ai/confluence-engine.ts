// ============================================================================
// CONFLUENCE ENGINE
// Detects confluence between: BOS, CHOCH, Order Block, FVG, Liquidity Sweep,
// SMT, LIT, OTE, Kill Zone, VWAP, EMA, Volume, HTF Bias, Trend, ADX, ATR, Session
// More confirmations => higher confidence
// ============================================================================

import { Candle, Direction, Bias } from '../core/types';
import {
  StructureBreak, OrderBlock, FairValueGap, LiquiditySweep,
  SMTDivergence, LITSetup, OTE, KillZone, TradingSession, PremiumDiscount,
} from '../core/types';
import {
  calculateEMA, calculateVWAP, calculateADX, calculateRelativeVolume, calculateMomentum,
} from '../indicators/technical';
import { calculateATR } from '../core/utils';

export type ConfluenceFactor =
  | 'BOS' | 'CHOCH' | 'ORDER_BLOCK' | 'FVG' | 'LIQUIDITY_SWEEP'
  | 'SMT' | 'LIT' | 'OTE' | 'KILL_ZONE' | 'VWAP' | 'EMA'
  | 'VOLUME' | 'HTF_BIAS' | 'TREND' | 'ADX' | 'ATR' | 'SESSION'
  | 'PREMIUM_DISCOUNT' | 'MOMENTUM';

export interface ConfluenceItem {
  factor: ConfluenceFactor;
  present: boolean;
  aligned: boolean; // Aligned with the trade direction
  weight: number; // Importance weight
  score: number; // Contribution to total (0-weight)
  detail: string;
}

export interface ConfluenceResult {
  direction: Direction;
  items: ConfluenceItem[];
  totalScore: number; // 0-100
  maxPossibleScore: number;
  confluenceCount: number; // Number of aligned factors
  grade: 'WEAK' | 'MEDIUM' | 'STRONG' | 'VERY_STRONG' | 'INSTITUTIONAL';
  summary: string;
}

// Factor weights (institutional priority)
const FACTOR_WEIGHTS: Record<ConfluenceFactor, number> = {
  HTF_BIAS: 12,
  CHOCH: 10,
  BOS: 9,
  LIQUIDITY_SWEEP: 9,
  ORDER_BLOCK: 8,
  LIT: 8,
  SMT: 8,
  FVG: 7,
  OTE: 7,
  PREMIUM_DISCOUNT: 6,
  TREND: 6,
  KILL_ZONE: 5,
  VWAP: 5,
  ADX: 5,
  EMA: 4,
  VOLUME: 4,
  MOMENTUM: 4,
  SESSION: 3,
  ATR: 2,
};

export interface ConfluenceInput {
  candles: Candle[];
  direction: Direction;
  currentPrice: number;
  structureBreaks: StructureBreak[];
  orderBlocks: OrderBlock[];
  fvgs: FairValueGap[];
  sweeps: LiquiditySweep[];
  smtDivergences: SMTDivergence[];
  litSetups: LITSetup[];
  ote?: OTE;
  killZones: KillZone[];
  sessions: TradingSession[];
  premiumDiscount?: PremiumDiscount;
  htfBias: Bias;
}

export class ConfluenceEngine {
  /**
   * Analyze confluence for a potential trade in a given direction
   */
  analyze(input: ConfluenceInput): ConfluenceResult {
    const { candles, direction, currentPrice } = input;
    const items: ConfluenceItem[] = [];

    // Precompute indicators
    const ema20 = calculateEMA(candles, 20);
    const ema50 = calculateEMA(candles, 50);
    const ema200 = calculateEMA(candles, 200);
    const vwap = calculateVWAP(candles);
    const { adx, plusDI, minusDI } = calculateADX(candles);
    const relVol = calculateRelativeVolume(candles);
    const momentum = calculateMomentum(candles);
    const atr = calculateATR(candles, 14);
    const last = candles.length - 1;

    // 1. HTF Bias
    items.push(this.evalFactor('HTF_BIAS',
      input.htfBias !== 'NEUTRAL',
      (direction === 'BULLISH' && input.htfBias === 'BULLISH') ||
      (direction === 'BEARISH' && input.htfBias === 'BEARISH'),
      `HTF bias: ${input.htfBias}`));

    // 2. CHOCH
    const recentChoch = input.structureBreaks.filter(b => b.type === 'CHOCH' || b.type === 'MSS').slice(-2);
    const chochAligned = recentChoch.some(b => b.direction === direction);
    items.push(this.evalFactor('CHOCH', recentChoch.length > 0, chochAligned,
      recentChoch.length ? `${recentChoch[recentChoch.length - 1].type} ${recentChoch[recentChoch.length - 1].direction}` : 'No CHOCH'));

    // 3. BOS
    const recentBos = input.structureBreaks.filter(b => b.type === 'BOS').slice(-2);
    const bosAligned = recentBos.some(b => b.direction === direction);
    items.push(this.evalFactor('BOS', recentBos.length > 0, bosAligned,
      recentBos.length ? `BOS ${recentBos[recentBos.length - 1].direction}` : 'No BOS'));

    // 4. Liquidity Sweep
    const recentSweeps = input.sweeps.filter(s => s.recovered).slice(-3);
    const sweepAligned = recentSweeps.some(s => {
      const buySide = s.level.type === 'BSL' || s.level.type === 'EQH';
      return (buySide && direction === 'BEARISH') || (!buySide && direction === 'BULLISH');
    });
    items.push(this.evalFactor('LIQUIDITY_SWEEP', recentSweeps.length > 0, sweepAligned,
      recentSweeps.length ? `${recentSweeps.length} recovered sweep(s)` : 'No sweep'));

    // 5. Order Block
    const alignedOB = input.orderBlocks.find(ob => !ob.mitigated && ob.direction === direction &&
      currentPrice >= ob.zone.low * 0.998 && currentPrice <= ob.zone.high * 1.002);
    items.push(this.evalFactor('ORDER_BLOCK', input.orderBlocks.some(ob => !ob.mitigated), !!alignedOB,
      alignedOB ? `Price at ${alignedOB.type}` : 'No OB at price'));

    // 6. LIT
    const alignedLIT = input.litSetups.find(l => l.direction === direction && l.confidence >= 60);
    items.push(this.evalFactor('LIT', input.litSetups.length > 0, !!alignedLIT,
      alignedLIT ? `${alignedLIT.type} (${alignedLIT.confidence}%)` : 'No LIT setup'));

    // 7. SMT
    const alignedSMT = input.smtDivergences.find(d => d.direction === direction);
    items.push(this.evalFactor('SMT', input.smtDivergences.length > 0, !!alignedSMT,
      alignedSMT ? `SMT ${alignedSMT.symbol1}/${alignedSMT.symbol2}` : 'No SMT'));

    // 8. FVG
    const alignedFVG = input.fvgs.find(f => !f.filled && f.direction === direction &&
      currentPrice >= f.zone.low * 0.998 && currentPrice <= f.zone.high * 1.002);
    items.push(this.evalFactor('FVG', input.fvgs.some(f => !f.filled), !!alignedFVG,
      alignedFVG ? `Price in ${alignedFVG.type}` : 'No FVG at price'));

    // 9. OTE
    const oteAligned = input.ote && input.ote.direction === direction &&
      currentPrice >= input.ote.zone.low && currentPrice <= input.ote.zone.high;
    items.push(this.evalFactor('OTE', !!input.ote, !!oteAligned,
      oteAligned ? 'Price in OTE zone' : 'Not in OTE'));

    // 10. Premium/Discount
    let pdAligned = false;
    let pdDetail = 'N/A';
    if (input.premiumDiscount) {
      pdAligned = (direction === 'BULLISH' && input.premiumDiscount.currentPosition === 'DISCOUNT') ||
                  (direction === 'BEARISH' && input.premiumDiscount.currentPosition === 'PREMIUM');
      pdDetail = `${input.premiumDiscount.currentPosition} zone`;
    }
    items.push(this.evalFactor('PREMIUM_DISCOUNT', !!input.premiumDiscount, pdAligned, pdDetail));

    // 11. Trend (EMA alignment)
    const trendUp = ema20[last] > ema50[last] && ema50[last] > ema200[last];
    const trendDown = ema20[last] < ema50[last] && ema50[last] < ema200[last];
    const trendAligned = (direction === 'BULLISH' && trendUp) || (direction === 'BEARISH' && trendDown);
    items.push(this.evalFactor('TREND', trendUp || trendDown, trendAligned,
      trendUp ? 'Uptrend (EMA stack)' : trendDown ? 'Downtrend (EMA stack)' : 'No clear trend'));

    // 12. Kill Zone
    const inKillZone = input.killZones.length > 0;
    items.push(this.evalFactor('KILL_ZONE', inKillZone, inKillZone,
      inKillZone ? `${input.killZones[0].type} active` : 'Outside kill zone'));

    // 13. VWAP
    const aboveVWAP = currentPrice > vwap[last];
    const vwapAligned = (direction === 'BULLISH' && aboveVWAP) || (direction === 'BEARISH' && !aboveVWAP);
    items.push(this.evalFactor('VWAP', true, vwapAligned,
      `Price ${aboveVWAP ? 'above' : 'below'} VWAP`));

    // 14. ADX (trend strength)
    const adxVal = adx[last];
    const strongTrend = adxVal > 25;
    const adxDirAligned = strongTrend &&
      ((direction === 'BULLISH' && plusDI[last] > minusDI[last]) ||
       (direction === 'BEARISH' && minusDI[last] > plusDI[last]));
    items.push(this.evalFactor('ADX', strongTrend, adxDirAligned,
      `ADX: ${adxVal.toFixed(1)} ${strongTrend ? '(strong)' : '(weak)'}`));

    // 15. EMA (price vs EMA20)
    const aboveEMA = currentPrice > ema20[last];
    const emaAligned = (direction === 'BULLISH' && aboveEMA) || (direction === 'BEARISH' && !aboveEMA);
    items.push(this.evalFactor('EMA', true, emaAligned, `Price ${aboveEMA ? 'above' : 'below'} EMA20`));

    // 16. Volume
    const highVolume = relVol[last] > 1.2;
    items.push(this.evalFactor('VOLUME', true, highVolume,
      `Rel. volume: ${relVol[last].toFixed(2)}x`));

    // 17. Momentum
    const momAligned = (direction === 'BULLISH' && momentum[last] > 0) ||
                       (direction === 'BEARISH' && momentum[last] < 0);
    items.push(this.evalFactor('MOMENTUM', Math.abs(momentum[last]) > 0.1, momAligned,
      `Momentum: ${momentum[last].toFixed(2)}%`));

    // 18. Session
    const activeSession = input.sessions.find(s => s.isActive);
    const goodSession = activeSession && (activeSession.type === 'LONDON' || activeSession.type === 'NEW_YORK');
    items.push(this.evalFactor('SESSION', !!activeSession, !!goodSession,
      activeSession ? `${activeSession.type} session` : 'No active session'));

    // 19. ATR (volatility context - present = tradeable volatility)
    const atrVal = atr[last];
    const goodVolatility = atrVal > 0 && atrVal < currentPrice * 0.02; // Not too volatile
    items.push(this.evalFactor('ATR', atrVal > 0, goodVolatility,
      `ATR: ${atrVal.toFixed(5)}`));

    // Aggregate
    const totalScore = items.reduce((s, item) => s + item.score, 0);
    const maxPossibleScore = items.reduce((s, item) => s + item.weight, 0);
    const normalizedScore = maxPossibleScore > 0 ? (totalScore / maxPossibleScore) * 100 : 0;
    const confluenceCount = items.filter(i => i.aligned).length;

    return {
      direction,
      items,
      totalScore: Math.round(normalizedScore),
      maxPossibleScore,
      confluenceCount,
      grade: this.gradeConfluence(normalizedScore, confluenceCount),
      summary: this.buildSummary(items, normalizedScore, confluenceCount),
    };
  }

  private evalFactor(factor: ConfluenceFactor, present: boolean, aligned: boolean, detail: string): ConfluenceItem {
    const weight = FACTOR_WEIGHTS[factor];
    const score = aligned ? weight : 0;
    return { factor, present, aligned, weight, score, detail };
  }

  private gradeConfluence(score: number, count: number): ConfluenceResult['grade'] {
    if (score >= 80 && count >= 12) return 'INSTITUTIONAL';
    if (score >= 65 && count >= 9) return 'VERY_STRONG';
    if (score >= 50 && count >= 6) return 'STRONG';
    if (score >= 35 && count >= 4) return 'MEDIUM';
    return 'WEAK';
  }

  private buildSummary(items: ConfluenceItem[], score: number, count: number): string {
    const aligned = items.filter(i => i.aligned).map(i => i.factor.replace(/_/g, ' '));
    return `${count} confluences (score ${Math.round(score)}/100): ${aligned.slice(0, 8).join(', ')}${aligned.length > 8 ? '...' : ''}`;
  }
}
