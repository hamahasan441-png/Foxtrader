// ============================================================================
// HARMONIC PATTERN RECOGNITION
// ABCD, Gartley, Bat, Butterfly, Crab, Cypher, Shark
// Uses Fibonacci ratio validation on swing points X-A-B-C-D
// ============================================================================

import { Candle, Direction, SwingPoint } from '../core/types';
import { findSwingPoints } from '../core/utils';
import { DetectedPattern, HarmonicPatternType, HARMONIC_RATIOS } from './pattern-types';

let seq = 0;
function pid(): string { return `harm_${++seq}`; }

export class HarmonicPatternDetector {
  private tolerance = 0.05; // 5% ratio tolerance

  /**
   * Scan for harmonic patterns in the price data.
   * Requires 5 swing points (X, A, B, C, D) with Fibonacci ratios.
   */
  detect(candles: Candle[]): DetectedPattern[] {
    if (candles.length < 50) return [];
    const patterns: DetectedPattern[] = [];
    const swings = findSwingPoints(candles, 3, 3);
    if (swings.length < 5) return [];

    // Try all combinations of 5 consecutive swings
    for (let i = 4; i < swings.length; i++) {
      const X = swings[i - 4];
      const A = swings[i - 3];
      const B = swings[i - 2];
      const C = swings[i - 1];
      const D = swings[i];

      // Validate alternating H/L structure (XABCD must zigzag)
      if (!this.isValidZigzag(X, A, B, C, D)) continue;

      // Calculate Fibonacci ratios
      const XA = Math.abs(A.price - X.price);
      const AB = Math.abs(B.price - A.price);
      const BC = Math.abs(C.price - B.price);
      const CD = Math.abs(D.price - C.price);
      const AD = Math.abs(D.price - A.price);

      if (XA === 0) continue;
      const ratios = {
        AB_XA: AB / XA,
        BC_AB: AB > 0 ? BC / AB : 0,
        CD_BC: BC > 0 ? CD / BC : 0,
        AD_XA: AD / XA,
      };

      // Test each harmonic pattern
      for (const [patternType, requiredRatios] of Object.entries(HARMONIC_RATIOS)) {
        if (this.matchesRatios(ratios, requiredRatios)) {
          const direction: Direction = D.type === 'LOW' ? 'BULLISH' : 'BEARISH';
          const target = this.calculateTarget(X, A, D, direction);

          patterns.push({
            id: pid(),
            type: patternType as HarmonicPatternType,
            category: 'HARMONIC',
            direction,
            bias: 'REVERSAL',
            confidence: this.calculateHarmonicConfidence(ratios, requiredRatios),
            probability: this.getHarmonicProbability(patternType as HarmonicPatternType),
            startIndex: X.index,
            endIndex: D.index,
            keyPoints: [
              { label: 'X', index: X.index, price: X.price },
              { label: 'A', index: A.index, price: A.price },
              { label: 'B', index: B.index, price: B.price },
              { label: 'C', index: C.index, price: C.price },
              { label: 'D', index: D.index, price: D.price },
            ],
            target,
            invalidation: direction === 'BULLISH' ? D.price - XA * 0.1 : D.price + XA * 0.1,
            meaning: this.getHarmonicMeaning(patternType as HarmonicPatternType, direction),
            context: `Completed at point D. Enter at D with stop beyond X. Target is the A-D retracement zone.`,
            timestamp: candles[D.index]?.timestamp ?? Date.now(),
          });
          break; // Don't detect multiple patterns from same 5 swings
        }
      }
    }
    return patterns;
  }

  private isValidZigzag(X: SwingPoint, A: SwingPoint, B: SwingPoint, C: SwingPoint, D: SwingPoint): boolean {
    // Must alternate high/low
    const types = [X.type, A.type, B.type, C.type, D.type];
    for (let i = 1; i < types.length; i++) {
      if (types[i] === types[i - 1]) return false;
    }
    return true;
  }

  private matchesRatios(actual: { AB_XA: number; BC_AB: number; CD_BC: number; AD_XA: number }, required: any): boolean {
    const t = this.tolerance;
    return this.inRange(actual.AB_XA, required.XA_AB.min, required.XA_AB.max, t) &&
           this.inRange(actual.BC_AB, required.AB_BC.min, required.AB_BC.max, t) &&
           this.inRange(actual.CD_BC, required.BC_CD.min, required.BC_CD.max, t) &&
           this.inRange(actual.AD_XA, required.XA_AD.min, required.XA_AD.max, t);
  }

  private inRange(value: number, min: number, max: number, tolerance: number): boolean {
    return value >= min * (1 - tolerance) && value <= max * (1 + tolerance);
  }

  private calculateHarmonicConfidence(actual: any, required: any): number {
    // Closer to ideal ratios = higher confidence
    let deviation = 0;
    deviation += Math.abs(actual.AB_XA - (required.XA_AB.min + required.XA_AB.max) / 2);
    deviation += Math.abs(actual.AD_XA - (required.XA_AD.min + required.XA_AD.max) / 2);
    const normalized = Math.max(0, 1 - deviation / 2);
    return Math.round(55 + normalized * 35);
  }

  private getHarmonicProbability(type: HarmonicPatternType): number {
    const probs: Record<HarmonicPatternType, number> = {
      GARTLEY: 70, BAT: 68, BUTTERFLY: 65, CRAB: 63, CYPHER: 62, SHARK: 58, ABCD: 66,
    };
    return probs[type] || 60;
  }

  private calculateTarget(X: SwingPoint, A: SwingPoint, D: SwingPoint, direction: Direction): number {
    const AD = Math.abs(D.price - A.price);
    // Target = 61.8% retracement of AD
    return direction === 'BULLISH' ? D.price + AD * 0.618 : D.price - AD * 0.618;
  }

  private getHarmonicMeaning(type: HarmonicPatternType, direction: Direction): string {
    const meanings: Record<HarmonicPatternType, string> = {
      GARTLEY: 'Gartley pattern — retraces to 78.6% of XA. Reliable reversal at D with tight stop.',
      BAT: 'Bat pattern — deep retracement (88.6% of XA). High probability with small risk.',
      BUTTERFLY: 'Butterfly — extends beyond X (127-161.8% of XA). Aggressive reversal at D.',
      CRAB: 'Crab — extreme extension (161.8% of XA). Very precise PRZ with excellent R:R.',
      CYPHER: 'Cypher — C exceeds A, D retraces to 78.6% of XC. Newer pattern with good accuracy.',
      SHARK: 'Shark — aggressive pattern that trades into a 5-0 continuation. Fast-moving.',
      ABCD: 'ABCD — simplest harmonic. CD leg equals AB in time/price. Clean measured move.',
    };
    return `${meanings[type]} Direction: ${direction.toLowerCase()}.`;
  }

  setTolerance(t: number): void { this.tolerance = Math.max(0.01, Math.min(0.15, t)); }
}
