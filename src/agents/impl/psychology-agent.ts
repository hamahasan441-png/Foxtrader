// ============================================================================
// AGENT 9: PSYCHOLOGY AGENT
// Detects FOMO, Revenge Trading, Overtrading, Fear, Greed from user behavior
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias } from '../../core/types';

let seq = 0;

export class PsychologyAgent implements TradingAgent {
  readonly name: AgentName = 'PSYCHOLOGY';
  readonly description = 'Analyzes user mistakes: FOMO, Revenge Trading, Overtrading, Fear, Greed';
  readonly version = '1.0.0';

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const insights: AgentInsight[] = [];
    const { recentTradeResults, tradeCountToday, dailyPnL } = context;

    // Overtrading detection
    const trades = tradeCountToday || 0;
    if (trades >= 5) {
      insights.push({
        id: `psy_${++seq}`, agentName: this.name, type: 'OVERTRADING',
        direction: null, confidence: 85, timestamp: Date.now(),
        detail: `${trades} trades today — overtrading detected. Quality over quantity.`,
        weight: 3, tags: ['OVERTRADING', 'BLOCK'],
      });
    }

    // Revenge trading (consecutive losses followed by more trades)
    const recent = recentTradeResults || [];
    const consecutiveLosses = this.countTrailingLosses(recent);
    if (consecutiveLosses >= 3) {
      insights.push({
        id: `psy_${++seq}`, agentName: this.name, type: 'REVENGE_TRADING',
        direction: null, confidence: 90, timestamp: Date.now(),
        detail: `${consecutiveLosses} consecutive losses. High risk of revenge trading. Take a break.`,
        weight: 4, tags: ['REVENGE', 'BLOCK'],
      });
    }

    // FOMO detection (quick succession of trades, chasing)
    if (trades >= 3 && consecutiveLosses >= 2) {
      insights.push({
        id: `psy_${++seq}`, agentName: this.name, type: 'FOMO',
        direction: null, confidence: 75, timestamp: Date.now(),
        detail: 'Behavioral pattern suggests FOMO — entering without proper setup confirmation.',
        weight: 2.5, tags: ['FOMO', 'CAUTION'],
      });
    }

    // Greed (not taking profits, over-leveraging after wins)
    const recentWins = recent.filter(r => r === 'WIN').length;
    if (recentWins >= 4 && trades >= 4) {
      insights.push({
        id: `psy_${++seq}`, agentName: this.name, type: 'GREED',
        direction: null, confidence: 65, timestamp: Date.now(),
        detail: 'Win streak may trigger overconfidence/greed. Maintain discipline and standard sizing.',
        weight: 1.5, tags: ['GREED', 'CAUTION'],
      });
    }

    // Fear (not entering valid setups after losses — detected externally, flagged here)
    if (consecutiveLosses >= 2 && trades === 0) {
      insights.push({
        id: `psy_${++seq}`, agentName: this.name, type: 'FEAR',
        direction: null, confidence: 60, timestamp: Date.now(),
        detail: 'Recent losses may be causing hesitation. If setup is valid (confluence 60+), trust the plan.',
        weight: 1, tags: ['FEAR', 'INFO'],
      });
    }

    // Assessment
    const blockers = insights.filter(i => i.tags.includes('BLOCK'));
    const mentalState = blockers.length > 0 ? 'COMPROMISED' : insights.length > 0 ? 'CAUTION' : 'CLEAR';

    return {
      agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL',
      confidence: blockers.length > 0 ? 20 : 80, // Low confidence = DO NOT trade
      insights,
      narrative: mentalState === 'CLEAR'
        ? 'Psychology: Clear mental state. Proceed with discipline.'
        : mentalState === 'CAUTION'
          ? `Psychology: ${insights.map(i => i.type).join(', ')} detected. Trade cautiously.`
          : `Psychology: HALT — ${blockers.map(b => b.type).join(', ')}. Step away.`,
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private countTrailingLosses(results: ('WIN' | 'LOSS')[]): number {
    let count = 0;
    for (let i = results.length - 1; i >= 0; i--) {
      if (results[i] === 'LOSS') count++;
      else break;
    }
    return count;
  }

  reset(): void {}
}
