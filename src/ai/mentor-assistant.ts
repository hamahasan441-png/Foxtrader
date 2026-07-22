// ============================================================================
// AI MENTOR ASSISTANT
// Answers trader questions like a mentor:
// Why did price reverse? Why was liquidity taken? Why SMT? Why this OB/FVG?
// Should I enter? Where SL? Where TP? What is HTF bias?
// ============================================================================

import { Candle, Direction, Bias } from '../core/types';
import {
  StructureBreak, OrderBlock, FairValueGap, LiquidityLevel, LiquiditySweep,
  SMTDivergence, LITSetup, PremiumDiscount, SwingPoint, TradingSession,
} from '../core/types';
import { ProbabilityEngine } from './probability-engine';
import { ConfluenceEngine } from './confluence-engine';
import { TradePlanner } from './trade-planner';
import { calculateRSI, calculateEMA, calculateADX } from '../indicators/technical';
import { calculateATR } from '../core/utils';

export type MentorQuestion =
  | 'WHY_REVERSED' | 'WHY_LIQUIDITY_TAKEN' | 'WHY_SMT' | 'WHY_THIS_OB'
  | 'WHY_THIS_FVG' | 'SHOULD_I_ENTER' | 'WHERE_SL' | 'WHERE_TP'
  | 'WHAT_HTF_BIAS' | 'GENERAL';

export interface MentorContext {
  symbol: string;
  candles: Candle[];
  currentPrice: number;
  bias: Bias;
  htfBias: Bias;
  structureBreaks: StructureBreak[];
  orderBlocks: OrderBlock[];
  fvgs: FairValueGap[];
  liquidityLevels: LiquidityLevel[];
  sweeps: LiquiditySweep[];
  smtDivergences: SMTDivergence[];
  litSetups: LITSetup[];
  swings: SwingPoint[];
  premiumDiscount?: PremiumDiscount;
  sessions: TradingSession[];
}

export interface MentorResponse {
  question: MentorQuestion;
  answer: string;
  keyPoints: string[];
  confidence: number; // How confident the mentor is in this answer
  relatedConcepts: string[];
  followUp: string[];
}

export class MentorAssistant {
  private probabilityEngine = new ProbabilityEngine();
  private confluenceEngine = new ConfluenceEngine();
  private tradePlanner = new TradePlanner();

  /**
   * Answer a natural-language or categorized question
   */
  ask(question: string, context: MentorContext): MentorResponse {
    const category = this.classifyQuestion(question);
    switch (category) {
      case 'WHY_REVERSED': return this.explainReversal(context);
      case 'WHY_LIQUIDITY_TAKEN': return this.explainLiquidityGrab(context);
      case 'WHY_SMT': return this.explainSMT(context);
      case 'WHY_THIS_OB': return this.explainOrderBlock(context);
      case 'WHY_THIS_FVG': return this.explainFVG(context);
      case 'SHOULD_I_ENTER': return this.shouldEnter(context);
      case 'WHERE_SL': return this.whereStopLoss(context);
      case 'WHERE_TP': return this.whereTakeProfit(context);
      case 'WHAT_HTF_BIAS': return this.explainHTFBias(context);
      default: return this.generalAnswer(question, context);
    }
  }

  private classifyQuestion(q: string): MentorQuestion {
    const lower = q.toLowerCase();
    if (/revers|why.*drop|why.*fall|why.*rally|why.*pump|why.*dump|turn/.test(lower)) return 'WHY_REVERSED';
    if (/liquidity|sweep|grab|stop.*hunt|took.*stop/.test(lower)) return 'WHY_LIQUIDITY_TAKEN';
    if (/smt|divergen|correlat/.test(lower)) return 'WHY_SMT';
    if (/order.*block|\bob\b|why.*this.*block/.test(lower)) return 'WHY_THIS_OB';
    if (/fvg|fair.*value|gap|imbalance/.test(lower)) return 'WHY_THIS_FVG';
    if (/should.*i.*enter|good.*entry|take.*trade|enter.*now|is.*this.*buy|is.*this.*sell/.test(lower)) return 'SHOULD_I_ENTER';
    if (/where.*sl|stop.*loss|where.*stop|place.*stop/.test(lower)) return 'WHERE_SL';
    if (/where.*tp|take.*profit|target|where.*exit/.test(lower)) return 'WHERE_TP';
    if (/htf|bias|higher.*time|direction|trend.*overall/.test(lower)) return 'WHAT_HTF_BIAS';
    return 'GENERAL';
  }

  // =========================================================================
  // EXPLANATIONS
  // =========================================================================

  private explainReversal(ctx: MentorContext): MentorResponse {
    const points: string[] = [];
    const last = ctx.candles.length - 1;

    // Find recent sweep that preceded reversal
    const recentSweep = ctx.sweeps.filter(s => s.recovered).slice(-1)[0];
    if (recentSweep) {
      const side = recentSweep.level.type === 'BSL' || recentSweep.level.type === 'EQH' ? 'buy-side' : 'sell-side';
      points.push(`Price swept ${side} liquidity at ${recentSweep.level.price.toFixed(5)} — smart money grabbed stops before reversing. This is a classic liquidity grab.`);
    }

    // CHOCH detection
    const choch = ctx.structureBreaks.filter(b => b.type === 'CHOCH' || b.type === 'MSS').slice(-1)[0];
    if (choch) {
      points.push(`A ${choch.type} (${choch.direction}) at ${choch.breakPrice.toFixed(5)} confirmed the change of character — the previous trend structure was broken.`);
    }

    // RSI extreme
    const rsi = calculateRSI(ctx.candles);
    if (rsi[last] > 70) points.push(`RSI was overbought (${rsi[last].toFixed(0)}) — momentum was overextended, inviting a pullback.`);
    else if (rsi[last] < 30) points.push(`RSI was oversold (${rsi[last].toFixed(0)}) — selling was exhausted.`);

    // Premium/Discount
    if (ctx.premiumDiscount) {
      points.push(`Price reversed from the ${ctx.premiumDiscount.currentPosition} zone — institutions sell premium and buy discount relative to the dealing range.`);
    }

    const answer = points.length > 0
      ? `Here's why price reversed: ${points[0]} ${points.length > 1 ? 'Combined with other factors, this created a high-probability turning point.' : ''}`
      : `Price likely reversed due to a shift in order flow. Look for a liquidity sweep followed by a change of character (CHOCH) — that's the institutional reversal footprint.`;

    return {
      question: 'WHY_REVERSED',
      answer,
      keyPoints: points,
      confidence: points.length >= 2 ? 85 : 60,
      relatedConcepts: ['Liquidity Sweep', 'CHOCH', 'Premium/Discount', 'Order Flow'],
      followUp: ['Should I enter on this reversal?', 'Where should my stop loss go?'],
    };
  }

  private explainLiquidityGrab(ctx: MentorContext): MentorResponse {
    const points: string[] = [];
    const recentSweep = ctx.sweeps.slice(-1)[0];

    if (recentSweep) {
      const side = recentSweep.level.type === 'BSL' || recentSweep.level.type === 'EQH' ? 'above a high' : 'below a low';
      points.push(`Liquidity was taken ${side} at ${recentSweep.level.price.toFixed(5)}. Retail traders place stop losses just beyond obvious highs/lows.`);
      points.push(`Institutions need liquidity to fill large orders. They drive price into these resting stop clusters to get counterparties, then reverse.`);
      if (recentSweep.recovered) {
        points.push(`Price quickly recovered back through the level — a strong sign the sweep was a stop hunt, not a genuine breakout.`);
      }
    } else {
      points.push(`Liquidity resides where stops cluster: above equal highs (buy-side) and below equal lows (sell-side). When price spikes into these zones and rejects, that's a grab.`);
    }

    return {
      question: 'WHY_LIQUIDITY_TAKEN',
      answer: `Liquidity gets taken because institutions need it to fill orders. ${points[0]}`,
      keyPoints: points,
      confidence: recentSweep ? 88 : 65,
      relatedConcepts: ['Buy-side/Sell-side Liquidity', 'Stop Hunt', 'Equal Highs/Lows', 'Inducement'],
      followUp: ['Is this a reversal signal?', 'Where do I enter after a sweep?'],
    };
  }

  private explainSMT(ctx: MentorContext): MentorResponse {
    const points: string[] = [];
    const smt = ctx.smtDivergences.slice(-1)[0];

    if (smt) {
      points.push(`${smt.symbol1} and ${smt.symbol2} are correlated, but they diverged: one made a new extreme while the other failed to. This is SMT divergence.`);
      points.push(`SMT (Smart Money Technique) divergence signals that the move lacks broad participation — smart money is not supporting it, hinting at a ${smt.direction.toLowerCase()} reversal.`);
      points.push(`Strength: ${smt.strength}/100. The stronger the divergence, the more reliable the signal.`);
    } else {
      points.push(`SMT divergence occurs when correlated instruments (e.g., EURUSD & GBPUSD, or ES & NQ) disagree at key levels — one sweeps liquidity while the other doesn't.`);
      points.push(`It reveals when a move is "fake" — if only one pair makes a new high, the breakout is suspect.`);
    }

    return {
      question: 'WHY_SMT',
      answer: `SMT divergence is a leading reversal signal. ${points[0]}`,
      keyPoints: points,
      confidence: smt ? 85 : 60,
      relatedConcepts: ['Correlation', 'Smart Money', 'Divergence', 'Confirmation'],
      followUp: ['How do I trade SMT divergence?', 'What is HTF bias here?'],
    };
  }

  private explainOrderBlock(ctx: MentorContext): MentorResponse {
    const ob = ctx.orderBlocks.filter(o => !o.mitigated).sort((a, b) => b.strength - a.strength)[0];
    const points: string[] = [];

    if (ob) {
      points.push(`This ${ob.type.replace(/_/g, ' ')} at ${ob.zone.low.toFixed(5)}-${ob.zone.high.toFixed(5)} is the last ${ob.direction === 'BULLISH' ? 'down' : 'up'}-candle before a strong displacement move.`);
      points.push(`It marks where institutions placed large orders. Price often returns to "mitigate" (fill remaining orders) before continuing.`);
      points.push(`Strength ${ob.strength}/100 — based on displacement, imbalance, and volume. ${ob.strength > 70 ? 'This is a high-quality block.' : 'Moderate quality — wait for confirmation.'}`);
    } else {
      points.push(`An order block is the last opposing candle before a strong institutional move. It represents unfilled institutional orders.`);
      points.push(`Valid OBs create displacement (a strong move away) and often an FVG. Price returning to the OB is an entry opportunity.`);
    }

    return {
      question: 'WHY_THIS_OB',
      answer: `Order blocks mark institutional footprints. ${points[0]}`,
      keyPoints: points,
      confidence: ob ? 85 : 65,
      relatedConcepts: ['Displacement', 'Mitigation', 'Imbalance', 'Institutional Orders'],
      followUp: ['Should I enter at this order block?', 'Where is my invalidation?'],
    };
  }

  private explainFVG(ctx: MentorContext): MentorResponse {
    const fvg = ctx.fvgs.filter(f => !f.filled).slice(-1)[0];
    const points: string[] = [];

    if (fvg) {
      points.push(`This ${fvg.type} (${fvg.direction}) between ${fvg.zone.low.toFixed(5)}-${fvg.zone.high.toFixed(5)} is a 3-candle imbalance where price moved so fast it left a gap.`);
      points.push(`FVGs represent inefficiency. Markets tend to return to "rebalance" these gaps, offering entries in the direction of the original impulse.`);
      points.push(`Fill status: ${fvg.fillPercentage.toFixed(0)}%. ${fvg.consequentialEncroachment ? 'Price has entered the consequential zone (50%) — a common entry trigger.' : 'Still unfilled — watch for a reaction.'}`);
    } else {
      points.push(`A Fair Value Gap is a price inefficiency — a gap between candle 1's wick and candle 3's wick, created by a fast move (candle 2).`);
      points.push(`Price is drawn back to fill FVGs. They act as magnets and support/resistance zones.`);
    }

    return {
      question: 'WHY_THIS_FVG',
      answer: `Fair Value Gaps mark market inefficiency. ${points[0]}`,
      keyPoints: points,
      confidence: fvg ? 85 : 65,
      relatedConcepts: ['Imbalance', 'Inefficiency', 'Consequential Encroachment', 'Displacement'],
      followUp: ['Is this FVG a good entry?', 'How do I combine FVG with an order block?'],
    };
  }

  private shouldEnter(ctx: MentorContext): MentorResponse {
    // Determine likely direction from bias
    const direction: Direction = ctx.bias === 'BEARISH' ? 'BEARISH' : 'BULLISH';

    const probability = this.probabilityEngine.calculate({
      candles: ctx.candles, direction, currentPrice: ctx.currentPrice,
      structureBreaks: ctx.structureBreaks, orderBlocks: ctx.orderBlocks,
      fvgs: ctx.fvgs, sweeps: ctx.sweeps, liquidityLevels: ctx.liquidityLevels,
      smtDivergences: ctx.smtDivergences, litSetups: ctx.litSetups, htfBias: ctx.htfBias,
    });

    const confluence = this.confluenceEngine.analyze({
      candles: ctx.candles, direction, currentPrice: ctx.currentPrice,
      structureBreaks: ctx.structureBreaks, orderBlocks: ctx.orderBlocks, fvgs: ctx.fvgs,
      sweeps: ctx.sweeps, smtDivergences: ctx.smtDivergences, litSetups: ctx.litSetups,
      killZones: [], sessions: ctx.sessions, premiumDiscount: ctx.premiumDiscount, htfBias: ctx.htfBias,
    });

    const points: string[] = [];
    points.push(`Setup confidence: ${probability.confidence}% (${probability.grade}).`);
    points.push(`Confluence: ${confluence.confluenceCount} aligned factors — ${confluence.summary}`);

    let verdict: string;
    if (probability.confidence >= 70 && confluence.confluenceCount >= 8) {
      verdict = `YES — this is a high-quality ${direction.toLowerCase()} setup. Confidence and confluence both support entry. Manage risk with a defined stop.`;
    } else if (probability.confidence >= 55) {
      verdict = `MAYBE — a moderate ${direction.toLowerCase()} setup. Wait for one more confirmation (e.g., CHOCH on lower timeframe or a rejection at your zone) before committing.`;
    } else {
      verdict = `NO — confluence is weak (${probability.confidence}%). Patience is a position. Wait for a cleaner setup with sweep + CHOCH + OB/FVG confluence.`;
    }

    // Warnings
    if (ctx.htfBias !== 'NEUTRAL' && ctx.htfBias !== direction) {
      points.push(`⚠️ This would be counter to HTF bias (${ctx.htfBias}) — higher risk.`);
    }

    return {
      question: 'SHOULD_I_ENTER',
      answer: verdict,
      keyPoints: points,
      confidence: probability.confidence,
      relatedConcepts: ['Confluence', 'Probability', 'Risk Management', 'HTF Bias'],
      followUp: ['Where should my stop loss be?', 'Where do I take profit?'],
    };
  }

  private whereStopLoss(ctx: MentorContext): MentorResponse {
    const direction: Direction = ctx.bias === 'BEARISH' ? 'BEARISH' : 'BULLISH';
    const atr = calculateATR(ctx.candles, 14);
    const currentATR = atr[atr.length - 1];

    const swings = ctx.swings.filter(s =>
      direction === 'BULLISH' ? s.type === 'LOW' : s.type === 'HIGH'
    ).sort((a, b) => b.index - a.index);

    const points: string[] = [];
    let slPrice: number;

    if (swings.length > 0) {
      const swing = swings[0];
      const buffer = currentATR * 0.25;
      slPrice = direction === 'BULLISH' ? swing.price - buffer : swing.price + buffer;
      points.push(`Place your stop beyond the protective swing ${direction === 'BULLISH' ? 'low' : 'high'} at ${swing.price.toFixed(5)}, with a small ATR buffer to avoid wick stop-outs.`);
      points.push(`Recommended SL: ${slPrice.toFixed(5)} (${(currentATR * 0.25).toFixed(5)} buffer beyond the swing).`);
    } else {
      slPrice = direction === 'BULLISH' ? ctx.currentPrice - currentATR * 1.5 : ctx.currentPrice + currentATR * 1.5;
      points.push(`No clear swing nearby — use an ATR-based stop at 1.5x ATR: ${slPrice.toFixed(5)}.`);
    }

    points.push(`Never place your stop at an "obvious" level where everyone else's stops are — that's exactly where liquidity gets swept. Give it room beyond structure.`);

    return {
      question: 'WHERE_SL',
      answer: `Your stop loss should go beyond the structure that invalidates your idea, not at a round number. ${points[0]}`,
      keyPoints: points,
      confidence: swings.length > 0 ? 85 : 65,
      relatedConcepts: ['Structure', 'ATR', 'Invalidation', 'Stop Hunt Avoidance'],
      followUp: ['Where should I take profit?', 'What position size should I use?'],
    };
  }

  private whereTakeProfit(ctx: MentorContext): MentorResponse {
    const direction: Direction = ctx.bias === 'BEARISH' ? 'BEARISH' : 'BULLISH';
    const points: string[] = [];

    // Target opposing liquidity
    const targets = ctx.liquidityLevels
      .filter(l => !l.swept && (direction === 'BULLISH' ? l.price > ctx.currentPrice : l.price < ctx.currentPrice))
      .sort((a, b) => direction === 'BULLISH' ? a.price - b.price : b.price - a.price)
      .slice(0, 3);

    if (targets.length > 0) {
      points.push(`Target resting liquidity — price is drawn to it. Nearest targets: ${targets.map(t => `${t.price.toFixed(5)} (${t.type})`).join(', ')}.`);
      points.push(`Take partials at TP1 (${targets[0].price.toFixed(5)}), move stop to break-even, then let the rest run to deeper liquidity.`);
    } else {
      points.push(`Target the next opposing liquidity pool or a fixed R-multiple (TP1 at 2R, TP2 at 3R, TP3 at 5R).`);
    }

    points.push(`Scale out: bank profit at TP1 to reduce risk, then trail the remainder. Greed at targets is a common mistake.`);

    return {
      question: 'WHERE_TP',
      answer: `Take profit at the next pool of opposing liquidity — that's where price is headed. ${points[0]}`,
      keyPoints: points,
      confidence: targets.length > 0 ? 85 : 65,
      relatedConcepts: ['Liquidity Targets', 'Partial Profits', 'Break-even', 'R-Multiple'],
      followUp: ['Should I trail my stop?', 'When do I move to break-even?'],
    };
  }

  private explainHTFBias(ctx: MentorContext): MentorResponse {
    const points: string[] = [];
    points.push(`Current HTF bias: ${ctx.htfBias}. This is the dominant directional lean derived from higher-timeframe structure.`);

    const recentBreaks = ctx.structureBreaks.slice(-3);
    if (recentBreaks.length > 0) {
      const bullish = recentBreaks.filter(b => b.direction === 'BULLISH').length;
      const bearish = recentBreaks.length - bullish;
      points.push(`Recent structure: ${bullish} bullish breaks vs ${bearish} bearish — ${bullish > bearish ? 'buyers in control' : bearish > bullish ? 'sellers in control' : 'balanced'}.`);
    }

    if (ctx.premiumDiscount) {
      points.push(`Price is in the ${ctx.premiumDiscount.currentPosition} zone. In a ${ctx.htfBias.toLowerCase()} bias, ${ctx.htfBias === 'BULLISH' ? 'look for longs in discount' : 'look for shorts in premium'}.`);
    }

    points.push(`Trade WITH the HTF bias for the highest probability. Counter-trend trades need exceptional confluence.`);

    return {
      question: 'WHAT_HTF_BIAS',
      answer: `The HTF bias is ${ctx.htfBias}. ${points[1] || points[0]} Align your trades with this direction.`,
      keyPoints: points,
      confidence: 80,
      relatedConcepts: ['Higher Timeframe', 'Market Structure', 'Premium/Discount', 'Trend Alignment'],
      followUp: ['Should I enter now?', 'Why did price reverse?'],
    };
  }

  private generalAnswer(question: string, ctx: MentorContext): MentorResponse {
    return {
      question: 'GENERAL',
      answer: `I'm your trading mentor. I can explain why price reversed, why liquidity was taken, SMT divergence, order blocks, FVGs, whether to enter, where to place stops and targets, and the HTF bias. Ask me about any of these and I'll break it down using the current ${ctx.symbol} chart.`,
      keyPoints: [
        `Current bias: ${ctx.htfBias}`,
        `Active order blocks: ${ctx.orderBlocks.filter(o => !o.mitigated).length}`,
        `Unfilled FVGs: ${ctx.fvgs.filter(f => !f.filled).length}`,
        `Recent sweeps: ${ctx.sweeps.length}`,
      ],
      confidence: 70,
      relatedConcepts: ['Smart Money Concepts', 'ICT', 'Price Action'],
      followUp: ['What is the HTF bias?', 'Should I enter now?', 'Why did price reverse?'],
    };
  }
}
