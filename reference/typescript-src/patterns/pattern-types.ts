// ============================================================================
// PATTERN RECOGNITION — Type Definitions
// Classic patterns + Harmonic patterns + Candlestick patterns
// ============================================================================

import { Direction, Candle, SwingPoint } from '../core/types';

// --- Classic Chart Patterns ---

export type ClassicPatternType =
  | 'HEAD_AND_SHOULDERS' | 'INVERSE_HEAD_AND_SHOULDERS'
  | 'DOUBLE_TOP' | 'DOUBLE_BOTTOM'
  | 'TRIPLE_TOP' | 'TRIPLE_BOTTOM'
  | 'ASCENDING_TRIANGLE' | 'DESCENDING_TRIANGLE' | 'SYMMETRICAL_TRIANGLE'
  | 'RECTANGLE' | 'FLAG' | 'PENNANT'
  | 'CUP_AND_HANDLE' | 'INVERSE_CUP_AND_HANDLE'
  | 'RISING_WEDGE' | 'FALLING_WEDGE'
  | 'DIAMOND' | 'BROADENING';

export type HarmonicPatternType =
  | 'ABCD' | 'GARTLEY' | 'BAT' | 'BUTTERFLY'
  | 'CRAB' | 'CYPHER' | 'SHARK';

export type CandlePatternType =
  | 'DOJI' | 'HAMMER' | 'INVERTED_HAMMER'
  | 'SHOOTING_STAR' | 'HANGING_MAN'
  | 'ENGULFING_BULLISH' | 'ENGULFING_BEARISH'
  | 'MORNING_STAR' | 'EVENING_STAR'
  | 'THREE_WHITE_SOLDIERS' | 'THREE_BLACK_CROWS'
  | 'HARAMI_BULLISH' | 'HARAMI_BEARISH'
  | 'PIERCING_LINE' | 'DARK_CLOUD_COVER'
  | 'TWEEZER_TOP' | 'TWEEZER_BOTTOM'
  | 'SPINNING_TOP' | 'MARUBOZU_BULLISH' | 'MARUBOZU_BEARISH'
  | 'DRAGONFLY_DOJI' | 'GRAVESTONE_DOJI'
  | 'THREE_INSIDE_UP' | 'THREE_INSIDE_DOWN'
  | 'BULLISH_KICKER' | 'BEARISH_KICKER'
  | 'ABANDONED_BABY_BULL' | 'ABANDONED_BABY_BEAR';

export type PatternBias = 'REVERSAL' | 'CONTINUATION';

export interface DetectedPattern {
  id: string;
  type: ClassicPatternType | HarmonicPatternType | CandlePatternType;
  category: 'CLASSIC' | 'HARMONIC' | 'CANDLE';
  direction: Direction;
  bias: PatternBias;
  confidence: number;        // 0-100
  probability: number;       // Historical success rate 0-100
  startIndex: number;
  endIndex: number;
  /** Key price points defining the pattern */
  keyPoints: { label: string; index: number; price: number }[];
  /** Target projection */
  target?: number;
  /** Invalidation level */
  invalidation?: number;
  /** Human-readable explanation */
  meaning: string;
  /** Context: where in the trend this pattern appeared */
  context: string;
  timestamp: number;
}

// --- Harmonic Ratio Definitions ---

export interface HarmonicRatios {
  XA_AB: { min: number; max: number };
  AB_BC: { min: number; max: number };
  BC_CD: { min: number; max: number };
  XA_AD: { min: number; max: number };
}

export const HARMONIC_RATIOS: Record<HarmonicPatternType, HarmonicRatios> = {
  ABCD: {
    XA_AB: { min: 0, max: 1 },
    AB_BC: { min: 0.382, max: 0.886 },
    BC_CD: { min: 1.13, max: 2.618 },
    XA_AD: { min: 0, max: 1 },
  },
  GARTLEY: {
    XA_AB: { min: 0.618, max: 0.618 },
    AB_BC: { min: 0.382, max: 0.886 },
    BC_CD: { min: 1.13, max: 1.618 },
    XA_AD: { min: 0.786, max: 0.786 },
  },
  BAT: {
    XA_AB: { min: 0.382, max: 0.50 },
    AB_BC: { min: 0.382, max: 0.886 },
    BC_CD: { min: 1.618, max: 2.618 },
    XA_AD: { min: 0.886, max: 0.886 },
  },
  BUTTERFLY: {
    XA_AB: { min: 0.786, max: 0.786 },
    AB_BC: { min: 0.382, max: 0.886 },
    BC_CD: { min: 1.618, max: 2.618 },
    XA_AD: { min: 1.27, max: 1.618 },
  },
  CRAB: {
    XA_AB: { min: 0.382, max: 0.618 },
    AB_BC: { min: 0.382, max: 0.886 },
    BC_CD: { min: 2.24, max: 3.618 },
    XA_AD: { min: 1.618, max: 1.618 },
  },
  CYPHER: {
    XA_AB: { min: 0.382, max: 0.618 },
    AB_BC: { min: 1.13, max: 1.414 },
    BC_CD: { min: 1.272, max: 2.0 },
    XA_AD: { min: 0.786, max: 0.786 },
  },
  SHARK: {
    XA_AB: { min: 1.13, max: 1.618 },
    AB_BC: { min: 1.618, max: 2.24 },
    BC_CD: { min: 0.886, max: 1.13 },
    XA_AD: { min: 0.886, max: 1.13 },
  },
};
