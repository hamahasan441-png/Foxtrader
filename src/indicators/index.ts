// ============================================================================
// INDICATORS — Unified barrel export
// All indicator math is accessed from this single module.
// ATR, swing detection, and Fibonacci remain in core/utils (zero-dependency)
// but are re-exported here for convenience.
// ============================================================================

// Technical indicators (EMA, SMA, VWAP, ADX, RSI, MACD, Momentum, RelVol)
export {
  calculateEMA,
  calculateSMA,
  calculateVWAP,
  calculateADX,
  calculateRSI,
  calculateMACD,
  calculateRelativeVolume,
  calculateMomentum,
} from './technical';

// Core math (ATR, swings, Fibonacci — no external deps)
export {
  calculateATR,
  findSwingPoints,
  getFibLevels,
  areLevelsEqual,
  isPriceInZone,
  zoneMidpoint,
  timeframeToMs,
  getPipSize,
  findCandleByTimestamp,
  CandleBuffer,
  ObjectPool,
} from '../core/utils';
