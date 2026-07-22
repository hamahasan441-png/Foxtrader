// ============================================================================
// AGENT 2: SMART MONEY AGENT
// Order Blocks, Mitigation, Breakers, Liquidity, FVG, IFVG, BPR, Voids
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction } from '../../core/types';
import { OrderBlockAnalyzer } from '../../modules/order-blocks';
import { LiquidityAnalyzer } from '../../modules/liquidity';
import { FairValueGapAnalyzer } from '../../modules/fair-value-gaps';

let seq = 0;

export class SmartMoneyAgent implements TradingAgent {
  readonly name: AgentName = 'SMART_MONEY';
  readonly description = 'Order Blocks, Mitigation, Breakers, Liquidity, FVG, IFVG, BPR, Liquidity Voids';
  readonly version = '1.0.0';
  private obAnalyzer = new OrderBlockAnalyzer();
  private liqAnalyzer = new LiquidityAnalyzer();
  private fvgAnalyzer = new FairValueGapAnalyzer();

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles, currentPrice } = context;
    const insights: AgentInsight[] = [];

    if (candles.length < 30) return this.empty(start);

    // Get structure breaks from previous agent if available
    const structBreaks = context.previousOutputs?.get('MARKET_STRUCTURE')?.insights
      .filter(i => ['BOS', 'CHOCH', 'MSS'].includes(i.type)) || [];

    const blocks = this.obAnalyzer.analyze(candles);
    const liquidity = this.liqAnalyzer.analyze(candles);
    const fvgs = this.fvgAnalyzer.analyze(candles);

    // Active Order Blocks near current price
    const activeOBs = blocks.filter(ob => !ob.mitigated);
    const nearbyOBs = activeOBs.filter(ob =>
      Math.abs(currentPrice - (ob.zone.high + ob.zone.low) / 2) < (ob.zone.high - ob.zone.low) * 3
    ).slice(0, 5);

    for (const ob of nearbyOBs) {
      const atPrice = currentPrice >= ob.zone.low && currentPrice <= ob.zone.high;
      insights.push({
        id: `sm_${++seq}`, agentName: this.name, type: ob.type,
        direction: ob.direction, confidence: ob.strength,
        price: (ob.zone.high + ob.zone.low) / 2, timestamp: ob.timestamp,
        zone: { high: ob.zone.high, low: ob.zone.low },
        detail: `${ob.type} (strength ${ob.strength}) ${atPrice ? '⚡ PRICE AT ZONE' : ''}`,
        weight: atPrice ? 3 : 2, tags: atPrice ? ['AT_ZONE', ob.type] : [ob.type],
      });
    }

    // Sweeps
    const recentSweeps = liquidity.sweeps.filter(s => s.recovered).slice(-3);
    for (const sweep of recentSweeps) {
      const isBSL = sweep.level.type === 'BSL' || sweep.level.type === 'EQH';
      insights.push({
        id: `sm_${++seq}`, agentName: this.name, type: 'LIQUIDITY_SWEEP',
        direction: isBSL ? 'BEARISH' : 'BULLISH', confidence: 80,
        price: sweep.level.price, timestamp: sweep.sweepTimestamp,
        detail: `${sweep.level.type} swept at ${sweep.level.price.toFixed(5)} and recovered`,
        weight: 2.5, tags: ['SWEEP', sweep.recovered ? 'RECOVERED' : 'OPEN'],
      });
    }

    // Active FVGs near price
    const activeFVGs = fvgs.filter(f => !f.filled && f.type === 'FVG');
    const nearbyFVGs = activeFVGs.filter(f =>
      Math.abs(currentPrice - (f.zone.high + f.zone.low) / 2) < (f.zone.high - f.zone.low) * 5
    ).slice(0, 3);

    for (const fvg of nearbyFVGs) {
      const atFVG = currentPrice >= fvg.zone.low && currentPrice <= fvg.zone.high;
      insights.push({
        id: `sm_${++seq}`, agentName: this.name, type: fvg.type,
        direction: fvg.direction, confidence: 70,
        zone: { high: fvg.zone.high, low: fvg.zone.low },
        timestamp: fvg.timestamp, price: (fvg.zone.high + fvg.zone.low) / 2,
        detail: `${fvg.type} ${fvg.direction} ${atFVG ? '⚡ PRICE IN GAP' : ''}`,
        weight: atFVG ? 2.5 : 1.5, tags: [fvg.type, atFVG ? 'AT_ZONE' : 'NEARBY'],
      });
    }

    // Determine bias from smart money evidence
    const bullishEvidence = insights.filter(i => i.direction === 'BULLISH').length;
    const bearishEvidence = insights.filter(i => i.direction === 'BEARISH').length;
    const bias: Bias = bullishEvidence > bearishEvidence + 1 ? 'BULLISH'
      : bearishEvidence > bullishEvidence + 1 ? 'BEARISH' : 'NEUTRAL';

    const confidence = Math.min(95, 40 + insights.length * 5 +
      nearbyOBs.filter(ob => currentPrice >= ob.zone.low && currentPrice <= ob.zone.high).length * 10);

    const narrative = `Smart Money: ${activeOBs.length} active OBs, ${activeFVGs.length} unfilled FVGs, ` +
      `${recentSweeps.length} recovered sweeps. Bias: ${bias} (${confidence}%).`;

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative, processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private empty(start: number): AgentOutput {
    return { agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 30,
      insights: [], narrative: 'Insufficient data.', processingTimeMs: performance.now() - start, timestamp: Date.now() };
  }

  reset(): void { this.obAnalyzer.reset(); this.liqAnalyzer.reset(); this.fvgAnalyzer.reset(); }
}
