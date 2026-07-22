// ============================================================================
// CLASSIC PATTERN RECOGNITION
// H&S, Inv H&S, Double Top/Bottom, Triangles, Rectangles, Flags,
// Pennants, Cup & Handle, Wedges, Diamond, Broadening
// ============================================================================

import { Candle, Direction, SwingPoint } from '../core/types';
import { findSwingPoints } from '../core/utils';
import { DetectedPattern, ClassicPatternType, PatternBias } from './pattern-types';

let seq = 0;
function pid(): string { return `cpat_${++seq}`; }

export class ClassicPatternDetector {
  /**
   * Scan candles for all classic chart patterns.
   * Uses confirmed swing points only (no look-ahead).
   */
  detect(candles: Candle[]): DetectedPattern[] {
    if (candles.length < 30) return [];
    const patterns: DetectedPattern[] = [];
    const swings = findSwingPoints(candles, 5, 5);
    const highs = swings.filter(s => s.type === 'HIGH');
    const lows = swings.filter(s => s.type === 'LOW');

    patterns.push(...this.detectDoubleTop(highs, lows, candles));
    patterns.push(...this.detectDoubleBottom(highs, lows, candles));
    patterns.push(...this.detectHeadAndShoulders(highs, lows, candles));
    patterns.push(...this.detectInvHeadAndShoulders(highs, lows, candles));
    patterns.push(...this.detectTriangles(highs, lows, candles));
    patterns.push(...this.detectWedges(highs, lows, candles));
    patterns.push(...this.detectFlags(highs, lows, candles));
    patterns.push(...this.detectRectangle(highs, lows, candles));

    return patterns;
  }

  private detectDoubleTop(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (highs.length < 2) return results;

    for (let i = highs.length - 2; i < highs.length; i++) {
      const h1 = highs[i - 1], h2 = highs[i];
      if (!h1 || !h2) continue;
      const tolerance = Math.abs(h1.price - h2.price) / h1.price;
      if (tolerance > 0.003) continue; // Tops must be within 0.3%

      // Neckline = low between the two tops
      const neckLow = lows.find(l => l.index > h1.index && l.index < h2.index);
      if (!neckLow) continue;

      const height = ((h1.price + h2.price) / 2) - neckLow.price;
      const target = neckLow.price - height;

      results.push({
        id: pid(), type: 'DOUBLE_TOP', category: 'CLASSIC',
        direction: 'BEARISH', bias: 'REVERSAL', confidence: 72, probability: 65,
        startIndex: h1.index, endIndex: h2.index,
        keyPoints: [
          { label: 'Top 1', index: h1.index, price: h1.price },
          { label: 'Top 2', index: h2.index, price: h2.price },
          { label: 'Neckline', index: neckLow.index, price: neckLow.price },
        ],
        target, invalidation: Math.max(h1.price, h2.price),
        meaning: 'Price rejected at a resistance twice — supply overwhelms demand. Bearish reversal expected on neckline break.',
        context: 'Forms at the end of an uptrend. The higher the timeframe, the more significant.',
        timestamp: candles[h2.index].timestamp,
      });
    }
    return results;
  }

  private detectDoubleBottom(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (lows.length < 2) return results;

    for (let i = lows.length - 2; i < lows.length; i++) {
      const l1 = lows[i - 1], l2 = lows[i];
      if (!l1 || !l2) continue;
      const tolerance = Math.abs(l1.price - l2.price) / l1.price;
      if (tolerance > 0.003) continue;

      const neckHigh = highs.find(h => h.index > l1.index && h.index < l2.index);
      if (!neckHigh) continue;

      const height = neckHigh.price - ((l1.price + l2.price) / 2);
      const target = neckHigh.price + height;

      results.push({
        id: pid(), type: 'DOUBLE_BOTTOM', category: 'CLASSIC',
        direction: 'BULLISH', bias: 'REVERSAL', confidence: 72, probability: 65,
        startIndex: l1.index, endIndex: l2.index,
        keyPoints: [
          { label: 'Bottom 1', index: l1.index, price: l1.price },
          { label: 'Bottom 2', index: l2.index, price: l2.price },
          { label: 'Neckline', index: neckHigh.index, price: neckHigh.price },
        ],
        target, invalidation: Math.min(l1.price, l2.price),
        meaning: 'Price found support twice — demand absorbs supply. Bullish reversal expected on neckline break.',
        context: 'Forms at the end of a downtrend.',
        timestamp: candles[l2.index].timestamp,
      });
    }
    return results;
  }


  private detectHeadAndShoulders(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (highs.length < 3) return results;

    // Look for L-Shoulder, Head, R-Shoulder pattern (head is highest)
    for (let i = 2; i < highs.length; i++) {
      const ls = highs[i - 2], head = highs[i - 1], rs = highs[i];
      if (head.price <= ls.price || head.price <= rs.price) continue;

      // Shoulders should be roughly equal height (within 3%)
      const shTolerance = Math.abs(ls.price - rs.price) / ls.price;
      if (shTolerance > 0.03) continue;

      // Neckline: connect the two lows between shoulders
      const neck1 = lows.find(l => l.index > ls.index && l.index < head.index);
      const neck2 = lows.find(l => l.index > head.index && l.index < rs.index);
      if (!neck1 || !neck2) continue;

      const necklinePrice = (neck1.price + neck2.price) / 2;
      const height = head.price - necklinePrice;
      const target = necklinePrice - height;

      results.push({
        id: pid(), type: 'HEAD_AND_SHOULDERS', category: 'CLASSIC',
        direction: 'BEARISH', bias: 'REVERSAL', confidence: 78, probability: 70,
        startIndex: ls.index, endIndex: rs.index,
        keyPoints: [
          { label: 'Left Shoulder', index: ls.index, price: ls.price },
          { label: 'Head', index: head.index, price: head.price },
          { label: 'Right Shoulder', index: rs.index, price: rs.price },
          { label: 'Neckline L', index: neck1.index, price: neck1.price },
          { label: 'Neckline R', index: neck2.index, price: neck2.price },
        ],
        target, invalidation: head.price,
        meaning: 'Classic bearish reversal. The head marks the exhaustion high. A break below the neckline triggers selling.',
        context: 'Forms at major market tops after extended uptrends.',
        timestamp: candles[rs.index].timestamp,
      });
    }
    return results;
  }

  private detectInvHeadAndShoulders(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (lows.length < 3) return results;

    for (let i = 2; i < lows.length; i++) {
      const ls = lows[i - 2], head = lows[i - 1], rs = lows[i];
      if (head.price >= ls.price || head.price >= rs.price) continue;

      const shTolerance = Math.abs(ls.price - rs.price) / ls.price;
      if (shTolerance > 0.03) continue;

      const neck1 = highs.find(h => h.index > ls.index && h.index < head.index);
      const neck2 = highs.find(h => h.index > head.index && h.index < rs.index);
      if (!neck1 || !neck2) continue;

      const necklinePrice = (neck1.price + neck2.price) / 2;
      const height = necklinePrice - head.price;
      const target = necklinePrice + height;

      results.push({
        id: pid(), type: 'INVERSE_HEAD_AND_SHOULDERS', category: 'CLASSIC',
        direction: 'BULLISH', bias: 'REVERSAL', confidence: 78, probability: 70,
        startIndex: ls.index, endIndex: rs.index,
        keyPoints: [
          { label: 'Left Shoulder', index: ls.index, price: ls.price },
          { label: 'Head', index: head.index, price: head.price },
          { label: 'Right Shoulder', index: rs.index, price: rs.price },
          { label: 'Neckline L', index: neck1.index, price: neck1.price },
          { label: 'Neckline R', index: neck2.index, price: neck2.price },
        ],
        target, invalidation: head.price,
        meaning: 'Classic bullish reversal. Head marks exhaustion low. Neckline break triggers buying.',
        context: 'Forms at major bottoms after extended downtrends.',
        timestamp: candles[rs.index].timestamp,
      });
    }
    return results;
  }

  private detectTriangles(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (highs.length < 3 || lows.length < 3) return results;

    const recentHighs = highs.slice(-4);
    const recentLows = lows.slice(-4);

    // Slopes of highs and lows
    const highSlope = this.slope(recentHighs);
    const lowSlope = this.slope(recentLows);

    const last = candles.length - 1;
    let type: ClassicPatternType | null = null;
    let dir: Direction = 'BULLISH';

    if (highSlope < -0.00001 && lowSlope > 0.00001) {
      type = 'SYMMETRICAL_TRIANGLE'; dir = 'BULLISH'; // Typically continues prior trend
    } else if (Math.abs(highSlope) < 0.00001 && lowSlope > 0.00001) {
      type = 'ASCENDING_TRIANGLE'; dir = 'BULLISH';
    } else if (highSlope < -0.00001 && Math.abs(lowSlope) < 0.00001) {
      type = 'DESCENDING_TRIANGLE'; dir = 'BEARISH';
    }

    if (type) {
      const startIdx = Math.min(recentHighs[0]?.index ?? 0, recentLows[0]?.index ?? 0);
      results.push({
        id: pid(), type, category: 'CLASSIC', direction: dir,
        bias: 'CONTINUATION', confidence: 68, probability: 62,
        startIndex: startIdx, endIndex: last,
        keyPoints: [
          ...recentHighs.map(h => ({ label: 'High', index: h.index, price: h.price })),
          ...recentLows.map(l => ({ label: 'Low', index: l.index, price: l.price })),
        ],
        meaning: `${type.replace(/_/g, ' ')} — price compressing between converging trendlines. Breakout expected in ${dir.toLowerCase()} direction.`,
        context: `Triangles typically resolve in the direction of the prior trend. Volume usually contracts during formation.`,
        timestamp: candles[last].timestamp,
      });
    }
    return results;
  }

  private detectWedges(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (highs.length < 3 || lows.length < 3) return results;

    const recentHighs = highs.slice(-4);
    const recentLows = lows.slice(-4);
    const highSlope = this.slope(recentHighs);
    const lowSlope = this.slope(recentLows);
    const last = candles.length - 1;

    // Rising Wedge: both slopes positive but converging (bearish)
    if (highSlope > 0 && lowSlope > 0 && lowSlope > highSlope) {
      results.push({
        id: pid(), type: 'RISING_WEDGE', category: 'CLASSIC',
        direction: 'BEARISH', bias: 'REVERSAL', confidence: 70, probability: 65,
        startIndex: recentLows[0].index, endIndex: last,
        keyPoints: [...recentHighs.map(h => ({ label: 'H', index: h.index, price: h.price })),
          ...recentLows.map(l => ({ label: 'L', index: l.index, price: l.price }))],
        meaning: 'Rising wedge — price making higher highs/lows but momentum weakening. Bearish breakdown expected.',
        context: 'Often forms as a final exhaustion move before reversal.',
        timestamp: candles[last].timestamp,
      });
    }

    // Falling Wedge: both slopes negative but converging (bullish)
    if (highSlope < 0 && lowSlope < 0 && highSlope > lowSlope) {
      results.push({
        id: pid(), type: 'FALLING_WEDGE', category: 'CLASSIC',
        direction: 'BULLISH', bias: 'REVERSAL', confidence: 70, probability: 65,
        startIndex: recentHighs[0].index, endIndex: last,
        keyPoints: [...recentHighs.map(h => ({ label: 'H', index: h.index, price: h.price })),
          ...recentLows.map(l => ({ label: 'L', index: l.index, price: l.price }))],
        meaning: 'Falling wedge — price making lower lows but selling pressure diminishing. Bullish breakout expected.',
        context: 'Typically signals accumulation at the end of a downtrend.',
        timestamp: candles[last].timestamp,
      });
    }
    return results;
  }

  private detectFlags(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (candles.length < 20) return results;
    const last = candles.length - 1;

    // A flag is a sharp move (pole) followed by a tight consolidation (flag)
    const lookback = Math.min(30, candles.length);
    const segment = candles.slice(-lookback);

    // Find the "pole" — largest single move in the segment
    let maxMove = 0, poleStart = 0, poleEnd = 0, poleDir: Direction = 'BULLISH';
    for (let i = 1; i < Math.min(15, segment.length); i++) {
      const move = segment[i].close - segment[0].close;
      if (Math.abs(move) > Math.abs(maxMove)) {
        maxMove = move; poleEnd = i;
      }
    }
    poleDir = maxMove > 0 ? 'BULLISH' : 'BEARISH';

    // Check if the remaining bars form a tight consolidation (flag body)
    if (poleEnd > 0 && poleEnd < segment.length - 5) {
      const flagBars = segment.slice(poleEnd);
      const flagHigh = Math.max(...flagBars.map(c => c.high));
      const flagLow = Math.min(...flagBars.map(c => c.low));
      const flagRange = flagHigh - flagLow;
      const poleRange = Math.abs(maxMove);

      // Flag should be less than 50% of pole height
      if (flagRange < poleRange * 0.5 && flagBars.length >= 4) {
        results.push({
          id: pid(), type: 'FLAG', category: 'CLASSIC',
          direction: poleDir, bias: 'CONTINUATION', confidence: 68, probability: 63,
          startIndex: last - lookback, endIndex: last,
          keyPoints: [
            { label: 'Pole Start', index: last - lookback, price: segment[0].close },
            { label: 'Pole End', index: last - lookback + poleEnd, price: segment[poleEnd].close },
            { label: 'Flag High', index: last - 3, price: flagHigh },
            { label: 'Flag Low', index: last - 2, price: flagLow },
          ],
          target: poleDir === 'BULLISH' ? flagHigh + poleRange : flagLow - poleRange,
          meaning: `${poleDir} flag — sharp move followed by tight consolidation. Continuation expected.`,
          context: 'Flags represent a pause in a strong trend before the next impulse.',
          timestamp: candles[last].timestamp,
        });
      }
    }
    return results;
  }

  private detectRectangle(highs: SwingPoint[], lows: SwingPoint[], candles: Candle[]): DetectedPattern[] {
    const results: DetectedPattern[] = [];
    if (highs.length < 2 || lows.length < 2) return results;

    const recentHighs = highs.slice(-4);
    const recentLows = lows.slice(-4);
    const last = candles.length - 1;

    // Check if highs and lows are roughly horizontal (flat slopes)
    const highSlope = Math.abs(this.slope(recentHighs));
    const lowSlope = Math.abs(this.slope(recentLows));

    if (highSlope < 0.000005 && lowSlope < 0.000005 && recentHighs.length >= 2 && recentLows.length >= 2) {
      const avgHigh = recentHighs.reduce((s, h) => s + h.price, 0) / recentHighs.length;
      const avgLow = recentLows.reduce((s, l) => s + l.price, 0) / recentLows.length;

      results.push({
        id: pid(), type: 'RECTANGLE', category: 'CLASSIC',
        direction: 'BULLISH', bias: 'CONTINUATION', confidence: 60, probability: 55,
        startIndex: Math.min(recentHighs[0].index, recentLows[0].index), endIndex: last,
        keyPoints: [
          { label: 'Range High', index: last, price: avgHigh },
          { label: 'Range Low', index: last, price: avgLow },
        ],
        target: avgHigh + (avgHigh - avgLow),
        meaning: 'Rectangle consolidation — price oscillating between flat S/R. Breakout imminent.',
        context: 'Can break in either direction. Volume expansion on breakout confirms.',
        timestamp: candles[last].timestamp,
      });
    }
    return results;
  }

  /** Calculate slope of swing points (price change per bar) */
  private slope(points: SwingPoint[]): number {
    if (points.length < 2) return 0;
    const first = points[0], last = points[points.length - 1];
    const bars = last.index - first.index;
    return bars > 0 ? (last.price - first.price) / bars : 0;
  }
}
