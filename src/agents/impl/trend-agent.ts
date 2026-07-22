// ============================================================================
// AGENT 6: TREND AGENT
// EMA, ADX, VWAP, ATR, Regression, Momentum
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction } from '../../core/types';
import { calculateEMA, calculateADX, calculateVWAP, calculateRSI, calculateMomentum } from '../../indicators/technical';
import { calculateATR } from '../../core/utils';

let seq = 0;

export class TrendAgent implements TradingAgent {
  readonly name: AgentName = 'TREND';
  readonly description = 'EMA, ADX, VWAP, ATR, Regression, Momentum';
  readonly version = '1.0.0';

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles, currentPrice } = context;
    const insights: AgentInsight[] = [];

    if (candles.length < 50) return this.empty(start);
    const last = candles.length - 1;

    const ema20 = calculateEMA(candles, 20);
    const ema50 = calculateEMA(candles, 50);
    const ema200 = calculateEMA(candles, 200);
    const { adx, plusDI, minusDI } = calculateADX(candles);
    const vwap = calculateVWAP(candles);
    const atr = calculateATR(candles, 14);
    const momentum = calculateMomentum(candles, 10);
    const rsi = calculateRSI(candles);

    // EMA Stack alignment
    const emaStackBull = ema20[last] > ema50[last] && ema50[last] > ema200[last];
    const emaStackBear = ema20[last] < ema50[last] && ema50[last] < ema200[last];
    const emaDir: Direction | null = emaStackBull ? 'BULLISH' : emaStackBear ? 'BEARISH' : null;

    insights.push({
      id: `tr_${++seq}`, agentName: this.name, type: 'EMA_STACK',
      direction: emaDir, confidence: emaDir ? 75 : 40, timestamp: Date.now(),
      detail: emaStackBull ? 'EMA 20>50>200 — bullish stack' : emaStackBear ? 'EMA 20<50<200 — bearish stack' : 'EMAs mixed — no clear trend',
      weight: 2.5, tags: emaDir ? [emaDir, 'EMA'] : ['MIXED'],
    });

    // ADX trend strength
    const adxVal = adx[last];
    const diDir: Direction = plusDI[last] > minusDI[last] ? 'BULLISH' : 'BEARISH';
    const strongTrend = adxVal > 25;
    insights.push({
      id: `tr_${++seq}`, agentName: this.name, type: 'ADX',
      direction: strongTrend ? diDir : null, confidence: strongTrend ? 70 : 40,
      timestamp: Date.now(),
      detail: `ADX ${adxVal.toFixed(1)} ${strongTrend ? `strong ${diDir.toLowerCase()} trend` : 'weak/ranging'}`,
      weight: 2, tags: strongTrend ? ['TRENDING', diDir] : ['RANGING'],
    });

    // VWAP position
    const aboveVWAP = currentPrice > vwap[last];
    insights.push({
      id: `tr_${++seq}`, agentName: this.name, type: 'VWAP',
      direction: aboveVWAP ? 'BULLISH' : 'BEARISH', confidence: 60, timestamp: Date.now(),
      price: vwap[last],
      detail: `Price ${aboveVWAP ? 'above' : 'below'} VWAP (${vwap[last].toFixed(5)})`,
      weight: 1.5, tags: ['VWAP'],
    });

    // Momentum
    const momVal = momentum[last];
    const momDir: Direction = momVal > 0 ? 'BULLISH' : 'BEARISH';
    insights.push({
      id: `tr_${++seq}`, agentName: this.name, type: 'MOMENTUM',
      direction: momDir, confidence: Math.min(80, 50 + Math.abs(momVal) * 3),
      timestamp: Date.now(),
      detail: `Momentum: ${momVal > 0 ? '+' : ''}${momVal.toFixed(2)}% — ${momDir.toLowerCase()} pressure`,
      weight: 1.5, tags: ['MOMENTUM', momDir],
    });

    // Linear Regression slope (simplified)
    const slope = this.linearRegressionSlope(candles.slice(-20));
    const slopeDir: Direction = slope > 0 ? 'BULLISH' : 'BEARISH';
    insights.push({
      id: `tr_${++seq}`, agentName: this.name, type: 'REGRESSION',
      direction: slopeDir, confidence: Math.min(75, 50 + Math.abs(slope) * 1000),
      timestamp: Date.now(),
      detail: `Linear regression slope: ${slope > 0 ? '+' : ''}${slope.toFixed(6)} — ${slopeDir.toLowerCase()}`,
      weight: 1.5, tags: ['REGRESSION', slopeDir],
    });

    // Aggregate
    const bullish = insights.filter(i => i.direction === 'BULLISH').reduce((s, i) => s + i.weight, 0);
    const bearish = insights.filter(i => i.direction === 'BEARISH').reduce((s, i) => s + i.weight, 0);
    const bias: Bias = bullish > bearish * 1.2 ? 'BULLISH' : bearish > bullish * 1.2 ? 'BEARISH' : 'NEUTRAL';
    const confidence = Math.min(90, 35 + (emaDir ? 15 : 0) + (strongTrend ? 15 : 0) +
      (aboveVWAP && emaStackBull ? 10 : 0) + Math.abs(momVal) * 2);

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative: `Trend: ${bias}, ADX ${adxVal.toFixed(1)}, EMA stack ${emaDir || 'mixed'}, VWAP ${aboveVWAP ? 'above' : 'below'}, momentum ${momVal.toFixed(2)}%.`,
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private linearRegressionSlope(candles: { close: number }[]): number {
    const n = candles.length;
    if (n < 2) return 0;
    let sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
    for (let i = 0; i < n; i++) {
      sumX += i; sumY += candles[i].close; sumXY += i * candles[i].close; sumX2 += i * i;
    }
    return (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
  }

  private empty(start: number): AgentOutput {
    return { agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 30,
      insights: [], narrative: 'Insufficient data for trend analysis.',
      processingTimeMs: performance.now() - start, timestamp: Date.now() };
  }

  reset(): void {}
}
