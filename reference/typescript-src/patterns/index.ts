// ============================================================================
// PATTERNS — Barrel export
// ============================================================================

export * from './pattern-types';
export { ClassicPatternDetector } from './classic-patterns';
export { HarmonicPatternDetector } from './harmonic-patterns';
export { CandlePatternDetector } from './candle-patterns';

import { Candle } from '../core/types';
import { DetectedPattern } from './pattern-types';
import { ClassicPatternDetector } from './classic-patterns';
import { HarmonicPatternDetector } from './harmonic-patterns';
import { CandlePatternDetector } from './candle-patterns';

/**
 * Unified pattern scanner — runs all three detectors and
 * returns combined results sorted by confidence.
 */
export class PatternScanner {
  private classic = new ClassicPatternDetector();
  private harmonic = new HarmonicPatternDetector();
  private candle = new CandlePatternDetector();

  scan(candles: Candle[]): DetectedPattern[] {
    const all: DetectedPattern[] = [
      ...this.classic.detect(candles),
      ...this.harmonic.detect(candles),
      ...this.candle.detect(candles),
    ];
    return all.sort((a, b) => b.confidence - a.confidence);
  }

  /** Scan only classic chart patterns */
  scanClassic(candles: Candle[]): DetectedPattern[] {
    return this.classic.detect(candles);
  }

  /** Scan only harmonic patterns */
  scanHarmonic(candles: Candle[]): DetectedPattern[] {
    return this.harmonic.detect(candles);
  }

  /** Scan only candlestick patterns */
  scanCandles(candles: Candle[], lookback?: number): DetectedPattern[] {
    return this.candle.detect(candles, lookback);
  }
}
