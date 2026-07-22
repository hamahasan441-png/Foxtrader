// ============================================================================
// AGENT 1: MARKET STRUCTURE AGENT
// Detects BOS, CHOCH, MSS, IDM, Internal/External Structure
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction } from '../../core/types';
import { MarketStructureAnalyzer } from '../../modules/market-structure';

let insightSeq = 0;

export class MarketStructureAgent implements TradingAgent {
  readonly name: AgentName = 'MARKET_STRUCTURE';
  readonly description = 'Detects BOS, CHOCH, MSS, IDM, internal/external structure';
  readonly version = '1.0.0';
  private analyzer = new MarketStructureAnalyzer();

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles } = context;
    const insights: AgentInsight[] = [];

    if (candles.length < 20) return this.emptyOutput(start);

    const structure = this.analyzer.analyze(candles);

    // Generate insights from recent structure breaks (last 5)
    const recent = structure.structureBreaks.slice(-5);
    for (const brk of recent) {
      const weight = brk.type === 'MSS' ? 3 : brk.type === 'CHOCH' ? 2.5 : brk.type === 'BOS' ? 2 : 1;
      insights.push({
        id: `ms_${++insightSeq}`,
        agentName: this.name,
        type: brk.type,
        direction: brk.direction,
        confidence: brk.confirmed ? 85 : 60,
        price: brk.breakPrice,
        timestamp: brk.breakTimestamp,
        barIndex: brk.breakIndex,
        detail: `${brk.type} ${brk.direction} at ${brk.breakPrice.toFixed(5)} (${brk.structureType})`,
        weight,
        tags: [brk.structureType, brk.confirmed ? 'CONFIRMED' : 'UNCONFIRMED'],
      });
    }

    // Internal vs External structure insights
    const internalCount = structure.internalStructure.length;
    const externalCount = structure.externalStructure.length;
    if (externalCount > 0) {
      const lastExt = structure.externalStructure[structure.externalStructure.length - 1];
      insights.push({
        id: `ms_${++insightSeq}`, agentName: this.name, type: 'EXTERNAL_STRUCTURE',
        direction: lastExt.direction, confidence: 80, price: lastExt.breakPrice,
        timestamp: lastExt.breakTimestamp, detail: `External structure: ${lastExt.type} ${lastExt.direction}`,
        weight: 2.5, tags: ['EXTERNAL'],
      });
    }

    const bias = structure.currentBias;
    const confidence = this.calculateConfidence(structure.structureBreaks.slice(-5), bias);

    const narrative = `Structure bias: ${bias}. Recent breaks: ${recent.map(b => `${b.type} ${b.direction}`).join(', ')}.` +
      ` ${internalCount} internal, ${externalCount} external breaks total.`;

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative, processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private calculateConfidence(recent: any[], bias: Bias): number {
    if (bias === 'NEUTRAL') return 40;
    const aligned = recent.filter((b: any) => b.direction === bias).length;
    return Math.min(95, 50 + aligned * 10);
  }

  private emptyOutput(start: number): AgentOutput {
    return {
      agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 30,
      insights: [], narrative: 'Insufficient data for structure analysis.',
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  reset(): void { this.analyzer.reset(); }
}
