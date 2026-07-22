// ============================================================================
// AGENT 4: LIT AGENT
// Liquidity Trap, Inducement, Institutional Entry, Confirmation, Target
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction } from '../../core/types';
import { LITTradingAnalyzer } from '../../modules/lit-trading';

let seq = 0;

export class LITAgent implements TradingAgent {
  readonly name: AgentName = 'LIT';
  readonly description = 'Liquidity Trap, Inducement, Institutional Entry, Confirmation Logic, Target Logic';
  readonly version = '1.0.0';
  private lit = new LITTradingAnalyzer();

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles } = context;
    const insights: AgentInsight[] = [];

    if (candles.length < 30) return this.empty(start);

    // Get structure/liquidity info from previous agents
    const prevStructure = context.previousOutputs?.get('MARKET_STRUCTURE');
    const prevSM = context.previousOutputs?.get('SMART_MONEY');

    // Extract breaks & sweeps from previous agent insights
    const structBreaks = (prevStructure?.insights || [])
      .filter(i => ['BOS', 'CHOCH', 'MSS', 'IDM'].includes(i.type))
      .map(i => ({ type: i.type as any, direction: i.direction!, breakPrice: i.price || 0, breakTimestamp: i.timestamp, breakIndex: i.barIndex || 0, swingPoint: {} as any, structureType: 'SWING' as any, confirmed: true }));

    const sweepInsights = (prevSM?.insights || []).filter(i => i.type === 'LIQUIDITY_SWEEP');

    // LIT Analysis using the module (simplified - builds from available context)
    const litSetups = this.lit.analyze(candles, structBreaks as any, [], [] as any, [], [], []);

    for (const setup of litSetups.slice(-5)) {
      insights.push({
        id: `lit_${++seq}`, agentName: this.name, type: setup.type,
        direction: setup.direction, confidence: setup.confidence,
        price: setup.price, timestamp: setup.timestamp, barIndex: setup.index,
        detail: `${setup.type.replace(/_/g, ' ')} — ${setup.confirmations.length} confirmations`,
        weight: 2.5, tags: [setup.type, ...setup.confirmations.filter(c => c.confirmed).map(c => c.type)],
      });
    }

    // Sweep + Structure break combo = institutional entry signal
    if (sweepInsights.length > 0 && structBreaks.length > 0) {
      const lastSweep = sweepInsights[sweepInsights.length - 1];
      const lastBreak = structBreaks[structBreaks.length - 1];
      if (lastSweep.direction && lastBreak.direction === lastSweep.direction) {
        insights.push({
          id: `lit_${++seq}`, agentName: this.name, type: 'INSTITUTIONAL_ENTRY_SIGNAL',
          direction: lastSweep.direction, confidence: 80, timestamp: Date.now(),
          detail: 'Sweep + Structure break confirmation = institutional entry',
          weight: 3, tags: ['INSTITUTIONAL', 'ENTRY_SIGNAL'],
        });
      }
    }

    const bullish = insights.filter(i => i.direction === 'BULLISH').reduce((s, i) => s + i.weight, 0);
    const bearish = insights.filter(i => i.direction === 'BEARISH').reduce((s, i) => s + i.weight, 0);
    const bias: Bias = bullish > bearish * 1.2 ? 'BULLISH' : bearish > bullish * 1.2 ? 'BEARISH' : 'NEUTRAL';
    const confidence = Math.min(90, litSetups.length > 0 ? litSetups[litSetups.length - 1].confidence : 35);

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative: `LIT: ${litSetups.length} setups detected. ${insights.length} total insights. Bias: ${bias}.`,
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private empty(start: number): AgentOutput {
    return { agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 30,
      insights: [], narrative: 'Insufficient data for LIT analysis.',
      processingTimeMs: performance.now() - start, timestamp: Date.now() };
  }

  reset(): void { this.lit.reset(); }
}
