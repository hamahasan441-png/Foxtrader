// ============================================================================
// AGENT 5: VOLUME AGENT
// Delta, Footprint analysis, Volume Profile, POC, HVN, LVN
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction, Candle } from '../../core/types';
import { calculateRelativeVolume } from '../../indicators/technical';

let seq = 0;

export class VolumeAgent implements TradingAgent {
  readonly name: AgentName = 'VOLUME';
  readonly description = 'Delta, Footprint, Volume Profile, POC, HVN, LVN';
  readonly version = '1.0.0';

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles, currentPrice } = context;
    const insights: AgentInsight[] = [];

    if (candles.length < 30) return this.empty(start);

    const relVol = calculateRelativeVolume(candles);
    const last = candles.length - 1;

    // Volume Profile (simplified — cluster price levels by volume)
    const { poc, hvn, lvn } = this.buildVolumeProfile(candles, 50);

    insights.push({
      id: `vol_${++seq}`, agentName: this.name, type: 'POC',
      direction: currentPrice > poc ? 'BULLISH' : 'BEARISH',
      confidence: 65, price: poc, timestamp: Date.now(),
      detail: `Point of Control at ${poc.toFixed(5)} — highest volume node`,
      weight: 2, tags: ['POC', 'VOLUME_PROFILE'],
    });

    // HVN (price likely to consolidate) and LVN (price likely to move fast through)
    for (const h of hvn.slice(0, 2)) {
      insights.push({
        id: `vol_${++seq}`, agentName: this.name, type: 'HVN',
        direction: null, confidence: 55, price: h, timestamp: Date.now(),
        detail: `High Volume Node at ${h.toFixed(5)} — potential S/R`,
        weight: 1.5, tags: ['HVN'],
      });
    }
    for (const l of lvn.slice(0, 2)) {
      insights.push({
        id: `vol_${++seq}`, agentName: this.name, type: 'LVN',
        direction: null, confidence: 50, price: l, timestamp: Date.now(),
        detail: `Low Volume Node at ${l.toFixed(5)} — fast price travel zone`,
        weight: 1, tags: ['LVN'],
      });
    }

    // Delta analysis (approx from candle body direction * volume)
    const delta = this.calculateDelta(candles.slice(-20));
    const deltaDir: Direction = delta > 0 ? 'BULLISH' : 'BEARISH';
    insights.push({
      id: `vol_${++seq}`, agentName: this.name, type: 'DELTA',
      direction: deltaDir, confidence: Math.min(80, 50 + Math.abs(delta) * 2),
      timestamp: Date.now(),
      detail: `Cumulative delta: ${delta > 0 ? '+' : ''}${delta.toFixed(0)} — ${deltaDir.toLowerCase()} pressure`,
      weight: 2, tags: ['DELTA', deltaDir],
    });

    // Relative volume spike
    if (relVol[last] >= 2.0) {
      insights.push({
        id: `vol_${++seq}`, agentName: this.name, type: 'VOLUME_SPIKE',
        direction: candles[last].close > candles[last].open ? 'BULLISH' : 'BEARISH',
        confidence: 75, timestamp: candles[last].timestamp,
        detail: `Volume spike: ${relVol[last].toFixed(1)}x average — institutional participation`,
        weight: 2.5, tags: ['SPIKE'],
      });
    }

    // Determine bias
    const bullish = insights.filter(i => i.direction === 'BULLISH').reduce((s, i) => s + i.weight, 0);
    const bearish = insights.filter(i => i.direction === 'BEARISH').reduce((s, i) => s + i.weight, 0);
    const bias: Bias = bullish > bearish * 1.2 ? 'BULLISH' : bearish > bullish * 1.2 ? 'BEARISH' : 'NEUTRAL';
    const confidence = Math.min(85, 40 + Math.abs(delta) + (relVol[last] > 1.5 ? 15 : 0));

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative: `Volume: Delta ${delta > 0 ? '+' : ''}${delta.toFixed(0)}, POC ${poc.toFixed(5)}, RelVol ${relVol[last].toFixed(1)}x. Bias: ${bias}.`,
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  /**
   * Build a simplified volume profile from candles
   */
  private buildVolumeProfile(candles: Candle[], lookback: number): { poc: number; hvn: number[]; lvn: number[] } {
    const recent = candles.slice(-lookback);
    const high = Math.max(...recent.map(c => c.high));
    const low = Math.min(...recent.map(c => c.low));
    const range = high - low;
    const bins = 20;
    const binSize = range / bins;
    const volumeByBin: number[] = new Array(bins).fill(0);

    for (const candle of recent) {
      const midPrice = (candle.high + candle.low) / 2;
      const binIndex = Math.min(bins - 1, Math.floor((midPrice - low) / binSize));
      volumeByBin[binIndex] += candle.volume || 1;
    }

    // POC = bin with highest volume
    const maxVol = Math.max(...volumeByBin);
    const pocIndex = volumeByBin.indexOf(maxVol);
    const poc = low + (pocIndex + 0.5) * binSize;

    // HVN = top 25% volume nodes; LVN = bottom 25%
    const sorted = [...volumeByBin].sort((a, b) => b - a);
    const hvnThreshold = sorted[Math.floor(bins * 0.25)];
    const lvnThreshold = sorted[Math.floor(bins * 0.75)];

    const hvn: number[] = [];
    const lvn: number[] = [];
    for (let i = 0; i < bins; i++) {
      const price = low + (i + 0.5) * binSize;
      if (volumeByBin[i] >= hvnThreshold) hvn.push(price);
      if (volumeByBin[i] <= lvnThreshold) lvn.push(price);
    }

    return { poc, hvn, lvn };
  }

  /**
   * Approximate delta from candle bodies (close > open = buying, else selling) * volume
   */
  private calculateDelta(candles: Candle[]): number {
    let delta = 0;
    for (const c of candles) {
      const dir = c.close > c.open ? 1 : -1;
      const body = Math.abs(c.close - c.open);
      const range = c.high - c.low || 1;
      delta += dir * (body / range) * (c.volume || 1);
    }
    return delta;
  }

  private empty(start: number): AgentOutput {
    return { agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 30,
      insights: [], narrative: 'Insufficient volume data.',
      processingTimeMs: performance.now() - start, timestamp: Date.now() };
  }

  reset(): void {}
}
