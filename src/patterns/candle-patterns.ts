// ============================================================================
// CANDLESTICK PATTERN ANALYSIS
// Recognizes ALL major candlestick patterns.
// For each: meaning, probability, context, continuation/reversal.
// ============================================================================

import { Candle, Direction } from '../core/types';
import { DetectedPattern, CandlePatternType, PatternBias } from './pattern-types';

let seq = 0;
function pid(): string { return `cndl_${++seq}`; }

interface CandleInfo {
  body: number; range: number; upperWick: number; lowerWick: number;
  isBullish: boolean; bodyRatio: number; upperRatio: number; lowerRatio: number;
}

function info(c: Candle): CandleInfo {
  const body = Math.abs(c.close - c.open);
  const range = c.high - c.low || 0.00001;
  const top = Math.max(c.open, c.close);
  const bot = Math.min(c.open, c.close);
  return {
    body, range,
    upperWick: c.high - top,
    lowerWick: bot - c.low,
    isBullish: c.close >= c.open,
    bodyRatio: body / range,
    upperRatio: (c.high - top) / range,
    lowerRatio: (bot - c.low) / range,
  };
}

const PATTERN_META: Record<CandlePatternType, { dir: Direction; bias: PatternBias; prob: number; meaning: string }> = {
  DOJI: { dir: 'BULLISH', bias: 'REVERSAL', prob: 50, meaning: 'Indecision — buyers and sellers in equilibrium. Look for context and the next candle for direction.' },
  HAMMER: { dir: 'BULLISH', bias: 'REVERSAL', prob: 65, meaning: 'Long lower wick shows rejection of lower prices. Buyers stepped in aggressively.' },
  INVERTED_HAMMER: { dir: 'BULLISH', bias: 'REVERSAL', prob: 60, meaning: 'Upper shadow shows buying attempt. If followed by bullish confirmation, reversal likely.' },
  SHOOTING_STAR: { dir: 'BEARISH', bias: 'REVERSAL', prob: 65, meaning: 'Long upper wick at a high — sellers rejected higher prices aggressively.' },
  HANGING_MAN: { dir: 'BEARISH', bias: 'REVERSAL', prob: 60, meaning: 'Hammer-shaped candle at a top. Signals potential selling pressure entering.' },
  ENGULFING_BULLISH: { dir: 'BULLISH', bias: 'REVERSAL', prob: 72, meaning: 'Large bullish candle completely engulfs prior bearish. Strong demand takeover.' },
  ENGULFING_BEARISH: { dir: 'BEARISH', bias: 'REVERSAL', prob: 72, meaning: 'Large bearish candle engulfs prior bullish. Supply overwhelms demand.' },
  MORNING_STAR: { dir: 'BULLISH', bias: 'REVERSAL', prob: 75, meaning: '3-candle bottom reversal. Selling exhausts, indecision, then strong buying.' },
  EVENING_STAR: { dir: 'BEARISH', bias: 'REVERSAL', prob: 75, meaning: '3-candle top reversal. Buying exhausts, indecision, then strong selling.' },
  THREE_WHITE_SOLDIERS: { dir: 'BULLISH', bias: 'CONTINUATION', prob: 70, meaning: 'Three consecutive bullish candles with higher closes — strong buying momentum.' },
  THREE_BLACK_CROWS: { dir: 'BEARISH', bias: 'CONTINUATION', prob: 70, meaning: 'Three consecutive bearish candles with lower closes — strong selling pressure.' },
  HARAMI_BULLISH: { dir: 'BULLISH', bias: 'REVERSAL', prob: 58, meaning: 'Small body inside prior large bearish — selling momentum fading.' },
  HARAMI_BEARISH: { dir: 'BEARISH', bias: 'REVERSAL', prob: 58, meaning: 'Small body inside prior large bullish — buying momentum fading.' },
  PIERCING_LINE: { dir: 'BULLISH', bias: 'REVERSAL', prob: 64, meaning: 'Bullish candle opens below prior low but closes above 50% of prior body. Buyers reject the gap down.' },
  DARK_CLOUD_COVER: { dir: 'BEARISH', bias: 'REVERSAL', prob: 64, meaning: 'Bearish candle opens above prior high but closes below 50% of prior body. Sellers reject the gap up.' },
  TWEEZER_TOP: { dir: 'BEARISH', bias: 'REVERSAL', prob: 62, meaning: 'Two candles with matching highs — double rejection at resistance.' },
  TWEEZER_BOTTOM: { dir: 'BULLISH', bias: 'REVERSAL', prob: 62, meaning: 'Two candles with matching lows — double rejection at support.' },
  SPINNING_TOP: { dir: 'BULLISH', bias: 'REVERSAL', prob: 48, meaning: 'Small body with long wicks — indecision. Low reliability alone.' },
  MARUBOZU_BULLISH: { dir: 'BULLISH', bias: 'CONTINUATION', prob: 68, meaning: 'Full body no wicks — extreme bullish conviction. Momentum likely continues.' },
  MARUBOZU_BEARISH: { dir: 'BEARISH', bias: 'CONTINUATION', prob: 68, meaning: 'Full body no wicks — extreme bearish conviction.' },
  DRAGONFLY_DOJI: { dir: 'BULLISH', bias: 'REVERSAL', prob: 63, meaning: 'Long lower wick, no upper — strong rejection of low prices.' },
  GRAVESTONE_DOJI: { dir: 'BEARISH', bias: 'REVERSAL', prob: 63, meaning: 'Long upper wick, no lower — strong rejection of high prices.' },
  THREE_INSIDE_UP: { dir: 'BULLISH', bias: 'REVERSAL', prob: 67, meaning: 'Harami followed by bullish confirmation — reversal confirmed.' },
  THREE_INSIDE_DOWN: { dir: 'BEARISH', bias: 'REVERSAL', prob: 67, meaning: 'Harami followed by bearish confirmation — reversal confirmed.' },
  BULLISH_KICKER: { dir: 'BULLISH', bias: 'REVERSAL', prob: 78, meaning: 'Gap up open above prior bearish close — extremely bullish. One of the strongest signals.' },
  BEARISH_KICKER: { dir: 'BEARISH', bias: 'REVERSAL', prob: 78, meaning: 'Gap down open below prior bullish close — extremely bearish.' },
  ABANDONED_BABY_BULL: { dir: 'BULLISH', bias: 'REVERSAL', prob: 76, meaning: 'Doji gaps below prior and above next candle — rare and very bullish.' },
  ABANDONED_BABY_BEAR: { dir: 'BEARISH', bias: 'REVERSAL', prob: 76, meaning: 'Doji gaps above prior and below next — rare and very bearish.' },
};


export class CandlePatternDetector {
  /**
   * Scan the last N candles for candlestick patterns.
   * Each pattern includes meaning, probability, context.
   */
  detect(candles: Candle[], lookback: number = 50): DetectedPattern[] {
    const patterns: DetectedPattern[] = [];
    const start = Math.max(2, candles.length - lookback);

    for (let i = start; i < candles.length; i++) {
      const c = candles[i], prev = candles[i - 1], prev2 = candles[i - 2];
      const ci = info(c), pi = info(prev);

      // --- Single candle patterns ---
      if (ci.bodyRatio < 0.1 && ci.range > 0) this.addP(patterns, 'DOJI', i, c, candles);
      if (ci.lowerRatio > 0.6 && ci.bodyRatio < 0.3 && ci.upperRatio < 0.1 && this.isDowntrend(candles, i))
        this.addP(patterns, 'HAMMER', i, c, candles);
      if (ci.upperRatio > 0.6 && ci.bodyRatio < 0.3 && ci.lowerRatio < 0.1 && this.isUptrend(candles, i))
        this.addP(patterns, 'SHOOTING_STAR', i, c, candles);
      if (ci.upperRatio > 0.6 && ci.bodyRatio < 0.3 && ci.lowerRatio < 0.1 && this.isDowntrend(candles, i))
        this.addP(patterns, 'INVERTED_HAMMER', i, c, candles);
      if (ci.lowerRatio > 0.6 && ci.bodyRatio < 0.3 && ci.upperRatio < 0.1 && this.isUptrend(candles, i))
        this.addP(patterns, 'HANGING_MAN', i, c, candles);
      if (ci.bodyRatio > 0.9 && ci.isBullish) this.addP(patterns, 'MARUBOZU_BULLISH', i, c, candles);
      if (ci.bodyRatio > 0.9 && !ci.isBullish) this.addP(patterns, 'MARUBOZU_BEARISH', i, c, candles);
      if (ci.bodyRatio < 0.05 && ci.lowerRatio > 0.7) this.addP(patterns, 'DRAGONFLY_DOJI', i, c, candles);
      if (ci.bodyRatio < 0.05 && ci.upperRatio > 0.7) this.addP(patterns, 'GRAVESTONE_DOJI', i, c, candles);

      // --- Two candle patterns ---
      if (ci.isBullish && !pi.isBullish && ci.body > pi.body * 1.2 && c.close > prev.open && c.open < prev.close)
        this.addP(patterns, 'ENGULFING_BULLISH', i, c, candles);
      if (!ci.isBullish && pi.isBullish && ci.body > pi.body * 1.2 && c.close < prev.open && c.open > prev.close)
        this.addP(patterns, 'ENGULFING_BEARISH', i, c, candles);
      if (!pi.isBullish && ci.isBullish && ci.body < pi.body * 0.5 &&
          Math.min(c.open, c.close) > Math.min(prev.open, prev.close) &&
          Math.max(c.open, c.close) < Math.max(prev.open, prev.close))
        this.addP(patterns, 'HARAMI_BULLISH', i, c, candles);
      if (pi.isBullish && !ci.isBullish && ci.body < pi.body * 0.5 &&
          Math.min(c.open, c.close) > Math.min(prev.open, prev.close) &&
          Math.max(c.open, c.close) < Math.max(prev.open, prev.close))
        this.addP(patterns, 'HARAMI_BEARISH', i, c, candles);
      if (Math.abs(c.high - prev.high) < (c.high - c.low) * 0.02 && pi.isBullish && !ci.isBullish)
        this.addP(patterns, 'TWEEZER_TOP', i, c, candles);
      if (Math.abs(c.low - prev.low) < (c.high - c.low) * 0.02 && !pi.isBullish && ci.isBullish)
        this.addP(patterns, 'TWEEZER_BOTTOM', i, c, candles);
      // Piercing Line
      if (!pi.isBullish && ci.isBullish && c.open < prev.low && c.close > (prev.open + prev.close) / 2)
        this.addP(patterns, 'PIERCING_LINE', i, c, candles);
      // Dark Cloud
      if (pi.isBullish && !ci.isBullish && c.open > prev.high && c.close < (prev.open + prev.close) / 2)
        this.addP(patterns, 'DARK_CLOUD_COVER', i, c, candles);
      // Kickers
      if (!pi.isBullish && ci.isBullish && c.open > prev.open)
        this.addP(patterns, 'BULLISH_KICKER', i, c, candles);
      if (pi.isBullish && !ci.isBullish && c.open < prev.open)
        this.addP(patterns, 'BEARISH_KICKER', i, c, candles);

      // --- Three candle patterns ---
      if (i >= 2) {
        const p2i = info(prev2);
        // Morning Star
        if (!p2i.isBullish && p2i.bodyRatio > 0.5 && pi.bodyRatio < 0.2 && ci.isBullish && ci.bodyRatio > 0.5)
          this.addP(patterns, 'MORNING_STAR', i, c, candles);
        // Evening Star
        if (p2i.isBullish && p2i.bodyRatio > 0.5 && pi.bodyRatio < 0.2 && !ci.isBullish && ci.bodyRatio > 0.5)
          this.addP(patterns, 'EVENING_STAR', i, c, candles);
        // Three White Soldiers
        if (p2i.isBullish && pi.isBullish && ci.isBullish &&
            prev.close > prev2.close && c.close > prev.close &&
            p2i.bodyRatio > 0.5 && pi.bodyRatio > 0.5 && ci.bodyRatio > 0.5)
          this.addP(patterns, 'THREE_WHITE_SOLDIERS', i, c, candles);
        // Three Black Crows
        if (!p2i.isBullish && !pi.isBullish && !ci.isBullish &&
            prev.close < prev2.close && c.close < prev.close &&
            p2i.bodyRatio > 0.5 && pi.bodyRatio > 0.5 && ci.bodyRatio > 0.5)
          this.addP(patterns, 'THREE_BLACK_CROWS', i, c, candles);
      }
    }

    return patterns;
  }

  private addP(patterns: DetectedPattern[], type: CandlePatternType, idx: number, candle: Candle, candles: Candle[]): void {
    const meta = PATTERN_META[type];
    if (!meta) return;
    const context = this.getContext(candles, idx, meta.dir);
    patterns.push({
      id: pid(), type, category: 'CANDLE', direction: meta.dir, bias: meta.bias,
      confidence: meta.prob + (context.includes('confirms') ? 8 : 0),
      probability: meta.prob, startIndex: idx - 1, endIndex: idx,
      keyPoints: [{ label: type, index: idx, price: candle.close }],
      meaning: meta.meaning, context,
      timestamp: candle.timestamp,
    });
  }

  private isUptrend(candles: Candle[], idx: number): boolean {
    if (idx < 5) return false;
    return candles[idx - 1].close > candles[idx - 5].close;
  }

  private isDowntrend(candles: Candle[], idx: number): boolean {
    if (idx < 5) return false;
    return candles[idx - 1].close < candles[idx - 5].close;
  }

  private getContext(candles: Candle[], idx: number, dir: Direction): string {
    const uptrend = this.isUptrend(candles, idx);
    const downtrend = this.isDowntrend(candles, idx);
    if (dir === 'BULLISH' && downtrend) return 'Appears after downtrend — confirms reversal potential.';
    if (dir === 'BEARISH' && uptrend) return 'Appears after uptrend — confirms reversal potential.';
    if (dir === 'BULLISH' && uptrend) return 'In uptrend — may signal continuation.';
    if (dir === 'BEARISH' && downtrend) return 'In downtrend — may signal continuation.';
    return 'Appears in mixed/ranging conditions — wait for confirmation.';
  }
}
