// ============================================================================
// MULTI-TIMEFRAME ENGINE
// Analyzes simultaneously: MN, W1, D1, H4, H1, M15, M5, M1
// Creates HTF Bias automatically by aggregating structure across timeframes.
// No look-ahead bias — each TF uses only its own confirmed data.
// ============================================================================

import { Candle, Timeframe, Bias, Direction } from '../core/types';
import { MarketStructureAnalyzer } from '../modules/market-structure';
import { calculateEMA, calculateADX } from '../indicators/technical';
import { TradingEventBus } from '../core/event-bus';

export const ALL_TIMEFRAMES: Timeframe[] = ['MN', 'W1', 'D1', 'H4', 'H1', 'M15', 'M5', 'M1'];

export interface TimeframeBias {
  timeframe: Timeframe;
  bias: Bias;
  confidence: number;
  lastBreakType?: string;
  lastBreakDirection?: Direction;
  emaAlignment: 'BULLISH' | 'BEARISH' | 'MIXED';
  adxStrength: number;
}

export interface MTFResult {
  symbol: string;
  /** Bias for each timeframe analyzed */
  biases: Map<Timeframe, TimeframeBias>;
  /** The final HTF bias derived from higher timeframes */
  htfBias: Bias;
  /** Confidence in the HTF bias (0-100) */
  htfConfidence: number;
  /** Which timeframes agree with the HTF bias */
  alignedTimeframes: Timeframe[];
  /** Which disagree */
  conflictingTimeframes: Timeframe[];
  /** Narrative summary */
  narrative: string;
  /** Timestamp of analysis */
  timestamp: number;
}

export interface MTFEngineConfig {
  /** Timeframes considered "higher" for HTF bias (default: MN, W1, D1, H4) */
  htfTimeframes: Timeframe[];
  /** Timeframes considered "lower" for entry (H1, M15, M5, M1) */
  ltfTimeframes: Timeframe[];
  /** Minimum data bars required per timeframe */
  minBarsRequired: number;
  /** Weight multiplier per timeframe (higher TF = more weight) */
  weights: Record<Timeframe, number>;
}

const DEFAULT_CONFIG: MTFEngineConfig = {
  htfTimeframes: ['MN', 'W1', 'D1', 'H4'],
  ltfTimeframes: ['H1', 'M15', 'M5', 'M1'],
  minBarsRequired: 50,
  weights: {
    'MN': 5.0, 'W1': 4.0, 'D1': 3.0, 'H4': 2.5,
    'H1': 2.0, 'M15': 1.5, 'M5': 1.0, 'M1': 0.5,
    'M3': 0.7, 'M30': 1.8, 'TICK': 0.1,
  },
};


export class MultiTimeframeEngine {
  private config: MTFEngineConfig;
  private eventBus?: TradingEventBus;
  private analyzers: Map<Timeframe, MarketStructureAnalyzer> = new Map();
  private lastResult: MTFResult | null = null;

  constructor(config: Partial<MTFEngineConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;

    // Create a structure analyzer per timeframe
    for (const tf of ALL_TIMEFRAMES) {
      this.analyzers.set(tf, new MarketStructureAnalyzer());
    }
  }

  /**
   * Analyze all timeframes simultaneously and determine HTF bias.
   * @param dataMap — Map of Timeframe → Candle[] (provide whatever TFs are available)
   */
  analyze(symbol: string, dataMap: Map<Timeframe, Candle[]>): MTFResult {
    const biases = new Map<Timeframe, TimeframeBias>();

    for (const [tf, candles] of dataMap) {
      if (candles.length < this.config.minBarsRequired) continue;

      const analyzer = this.analyzers.get(tf);
      if (!analyzer) continue;

      // Run structure analysis on this timeframe
      const structure = analyzer.analyze(candles);
      const last = candles.length - 1;

      // EMA alignment check (20 > 50 > 200)
      const ema20 = calculateEMA(candles, 20);
      const ema50 = calculateEMA(candles, 50);
      const ema200 = candles.length >= 200 ? calculateEMA(candles, 200) : ema50;
      const bullEMA = ema20[last] > ema50[last] && ema50[last] > ema200[last];
      const bearEMA = ema20[last] < ema50[last] && ema50[last] < ema200[last];

      // ADX strength
      const { adx } = calculateADX(candles);
      const adxVal = adx[last] || 0;

      // Last break
      const lastBreak = structure.structureBreaks[structure.structureBreaks.length - 1];

      // Determine this TF's bias
      const structureBias = structure.currentBias;
      const emaBias: Bias = bullEMA ? 'BULLISH' : bearEMA ? 'BEARISH' : 'NEUTRAL';

      // Weighted blend: structure 60% + EMA 30% + ADX direction 10%
      const bias = this.blendBias(structureBias, emaBias, adxVal, candles, last);

      // Confidence = how strong is the evidence
      let confidence = 40;
      if (structureBias === emaBias && structureBias !== 'NEUTRAL') confidence += 25;
      if (adxVal > 25) confidence += 15;
      if (lastBreak?.confirmed) confidence += 10;
      confidence = Math.min(95, confidence);

      biases.set(tf, {
        timeframe: tf,
        bias,
        confidence,
        lastBreakType: lastBreak?.type,
        lastBreakDirection: lastBreak?.direction,
        emaAlignment: bullEMA ? 'BULLISH' : bearEMA ? 'BEARISH' : 'MIXED',
        adxStrength: adxVal,
      });
    }

    // Determine HTF Bias from higher timeframes
    const { htfBias, htfConfidence } = this.calculateHTFBias(biases);

    // Find alignment/conflict
    const aligned: Timeframe[] = [];
    const conflicting: Timeframe[] = [];
    for (const [tf, tfBias] of biases) {
      if (htfBias === 'NEUTRAL') continue;
      if (tfBias.bias === htfBias) aligned.push(tf);
      else if (tfBias.bias !== 'NEUTRAL') conflicting.push(tf);
    }

    const narrative = this.buildNarrative(htfBias, htfConfidence, aligned, conflicting, biases);

    const result: MTFResult = {
      symbol,
      biases,
      htfBias,
      htfConfidence,
      alignedTimeframes: aligned,
      conflictingTimeframes: conflicting,
      narrative,
      timestamp: Date.now(),
    };

    this.lastResult = result;
    this.eventBus?.emit({ type: 'MTF_ANALYSIS' as any, data: result });
    return result;
  }

  /**
   * Calculate HTF bias by weighting higher timeframe biases more heavily
   */
  private calculateHTFBias(biases: Map<Timeframe, TimeframeBias>): { htfBias: Bias; htfConfidence: number } {
    let bullishScore = 0;
    let bearishScore = 0;
    let totalWeight = 0;

    for (const tf of this.config.htfTimeframes) {
      const tfBias = biases.get(tf);
      if (!tfBias) continue;

      const weight = this.config.weights[tf] || 1;
      totalWeight += weight;

      const contribution = (tfBias.confidence / 100) * weight;
      if (tfBias.bias === 'BULLISH') bullishScore += contribution;
      else if (tfBias.bias === 'BEARISH') bearishScore += contribution;
    }

    if (totalWeight === 0) return { htfBias: 'NEUTRAL', htfConfidence: 30 };

    const normalizedBull = (bullishScore / totalWeight) * 100;
    const normalizedBear = (bearishScore / totalWeight) * 100;

    let htfBias: Bias;
    let htfConfidence: number;

    if (normalizedBull > normalizedBear * 1.2) {
      htfBias = 'BULLISH';
      htfConfidence = Math.round(normalizedBull);
    } else if (normalizedBear > normalizedBull * 1.2) {
      htfBias = 'BEARISH';
      htfConfidence = Math.round(normalizedBear);
    } else {
      htfBias = 'NEUTRAL';
      htfConfidence = Math.round(Math.max(normalizedBull, normalizedBear));
    }

    return { htfBias, htfConfidence: Math.min(95, htfConfidence) };
  }

  /**
   * Blend structure bias with EMA bias and ADX
   */
  private blendBias(structureBias: Bias, emaBias: Bias, adxVal: number, candles: Candle[], lastIdx: number): Bias {
    // If both agree, strong signal
    if (structureBias === emaBias && structureBias !== 'NEUTRAL') return structureBias;

    // Structure takes priority (60% weight)
    if (structureBias !== 'NEUTRAL') return structureBias;

    // EMA as fallback
    if (emaBias !== 'NEUTRAL' && adxVal > 20) return emaBias;

    return 'NEUTRAL';
  }

  private buildNarrative(
    htfBias: Bias, confidence: number,
    aligned: Timeframe[], conflicting: Timeframe[],
    biases: Map<Timeframe, TimeframeBias>
  ): string {
    const parts: string[] = [];
    parts.push(`HTF Bias: ${htfBias} (${confidence}%).`);

    if (aligned.length > 0) {
      parts.push(`Aligned TFs (${aligned.length}): ${aligned.join(', ')}.`);
    }
    if (conflicting.length > 0) {
      parts.push(`Conflicting TFs (${conflicting.length}): ${conflicting.join(', ')}.`);
    }

    // Highlight top-down flow
    const top3: string[] = [];
    for (const tf of ['MN', 'W1', 'D1'] as Timeframe[]) {
      const b = biases.get(tf);
      if (b) top3.push(`${tf}=${b.bias}`);
    }
    parts.push(`Top-down: ${top3.join(' → ')}.`);

    return parts.join(' ');
  }

  // =========================================================================
  // PUBLIC API
  // =========================================================================

  /** Get the last MTF result */
  getLastResult(): MTFResult | null { return this.lastResult; }

  /** Get bias for a specific timeframe from last analysis */
  getTimeframeBias(tf: Timeframe): TimeframeBias | undefined {
    return this.lastResult?.biases.get(tf);
  }

  /** Get the automatic HTF bias */
  getHTFBias(): Bias { return this.lastResult?.htfBias ?? 'NEUTRAL'; }

  /** Check if a direction is aligned with HTF bias */
  isAlignedWithHTF(direction: Direction): boolean {
    const htf = this.getHTFBias();
    return htf === direction;
  }

  /** Get alignment score: how many TFs agree with a direction (0-100) */
  getAlignmentScore(direction: Direction): number {
    if (!this.lastResult) return 0;
    let aligned = 0;
    let total = 0;
    for (const [tf, bias] of this.lastResult.biases) {
      total++;
      if (bias.bias === direction) aligned++;
    }
    return total > 0 ? (aligned / total) * 100 : 0;
  }

  /** Reset all analyzers */
  reset(): void {
    for (const analyzer of this.analyzers.values()) analyzer.reset();
    this.lastResult = null;
  }

  updateConfig(config: Partial<MTFEngineConfig>): void {
    this.config = { ...this.config, ...config };
  }
}
