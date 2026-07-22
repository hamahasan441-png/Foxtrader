// ============================================================================
// AGENT 10: STRATEGY AGENT
// Combines outputs from ALL other agents.
// Generates final trade setup with consensus scoring.
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction } from '../../core/types';

let seq = 0;

export class StrategyAgent implements TradingAgent {
  readonly name: AgentName = 'STRATEGY';
  readonly description = 'Combines all agent outputs into final trade decision';
  readonly version = '1.0.0';

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const insights: AgentInsight[] = [];
    const prev = context.previousOutputs;
    if (!prev || prev.size < 4) return this.empty(start);

    // Collect all agent biases and confidences
    let bullishVotes = 0, bearishVotes = 0;
    let bullishWeightedConf = 0, bearishWeightedConf = 0;
    let totalWeight = 0;
    const blockers: string[] = [];
    const agentSummaries: string[] = [];

    for (const [name, output] of prev) {
      if (name === 'STRATEGY') continue; // Don't self-reference

      const weight = this.getAgentWeight(name);
      totalWeight += weight;

      if (output.bias === 'BULLISH') {
        bullishVotes++;
        bullishWeightedConf += output.confidence * weight;
      } else if (output.bias === 'BEARISH') {
        bearishVotes++;
        bearishWeightedConf += output.confidence * weight;
      }

      // Check for blockers (Risk/Psychology agents)
      if (name === 'RISK' || name === 'PSYCHOLOGY') {
        const hasBlock = output.insights.some(i => i.tags.includes('BLOCK'));
        if (hasBlock) blockers.push(`${name}: ${output.insights.filter(i => i.tags.includes('BLOCK')).map(i => i.type).join(', ')}`);
      }

      agentSummaries.push(`${name}: ${output.bias} (${output.confidence}%)`);
    }

    // Strategy decision
    const bullishScore = totalWeight > 0 ? bullishWeightedConf / totalWeight : 0;
    const bearishScore = totalWeight > 0 ? bearishWeightedConf / totalWeight : 0;

    let bias: Bias;
    let signalDirection: Direction | null = null;
    let confidence: number;

    if (blockers.length > 0) {
      // BLOCKED — don't trade regardless of analysis
      bias = 'NEUTRAL';
      confidence = 10;
      insights.push({
        id: `strat_${++seq}`, agentName: this.name, type: 'BLOCKED',
        direction: null, confidence: 95, timestamp: Date.now(),
        detail: `Trade BLOCKED: ${blockers.join(' | ')}`,
        weight: 5, tags: ['BLOCKED'],
      });
    } else if (bullishScore > bearishScore * 1.3 && bullishVotes >= 4) {
      bias = 'BULLISH';
      signalDirection = 'BULLISH';
      confidence = Math.round(bullishScore);
    } else if (bearishScore > bullishScore * 1.3 && bearishVotes >= 4) {
      bias = 'BEARISH';
      signalDirection = 'BEARISH';
      confidence = Math.round(bearishScore);
    } else {
      bias = 'NEUTRAL';
      confidence = 35;
    }

    if (signalDirection) {
      // Build the final signal insight
      const aligned = Array.from(prev.entries())
        .filter(([, o]) => o.bias === signalDirection)
        .map(([n]) => n);

      insights.push({
        id: `strat_${++seq}`, agentName: this.name, type: 'CONSENSUS_SIGNAL',
        direction: signalDirection, confidence, timestamp: Date.now(),
        detail: `${signalDirection} signal — ${aligned.length} agents aligned: ${aligned.join(', ')}`,
        weight: 5, tags: ['SIGNAL', signalDirection, ...aligned],
      });

      // Count specific confluence from all agents' insights
      const allInsights = Array.from(prev.values()).flatMap(o => o.insights);
      const dirInsights = allInsights.filter(i => i.direction === signalDirection);
      const confluenceTypes = [...new Set(dirInsights.map(i => i.type))];

      insights.push({
        id: `strat_${++seq}`, agentName: this.name, type: 'CONFLUENCE_COUNT',
        direction: signalDirection, confidence, timestamp: Date.now(),
        detail: `${confluenceTypes.length} unique confluences: ${confluenceTypes.slice(0, 8).join(', ')}`,
        weight: 3, tags: ['CONFLUENCE', ...confluenceTypes.slice(0, 5)],
      });
    }

    const narrative = blockers.length > 0
      ? `STRATEGY: BLOCKED — ${blockers.join('. ')}. No trade.`
      : signalDirection
        ? `STRATEGY: ${signalDirection} SIGNAL (${confidence}%) — ${bullishVotes}B/${bearishVotes}S votes.`
        : `STRATEGY: No consensus. Bull ${bullishScore.toFixed(0)} vs Bear ${bearishScore.toFixed(0)}. Stand aside.`;

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative, processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private getAgentWeight(name: AgentName): number {
    const weights: Record<AgentName, number> = {
      MARKET_STRUCTURE: 1.5, SMART_MONEY: 1.4, ICT: 1.3, LIT: 1.3,
      VOLUME: 1.0, TREND: 1.2, RISK: 1.1, NEWS: 0.8, PSYCHOLOGY: 0.7, STRATEGY: 0,
    };
    return weights[name] || 1;
  }

  private empty(start: number): AgentOutput {
    return { agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 20,
      insights: [], narrative: 'Insufficient agent data for strategy decision.',
      processingTimeMs: performance.now() - start, timestamp: Date.now() };
  }

  reset(): void {}
}
