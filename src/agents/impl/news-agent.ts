// ============================================================================
// AGENT 8: NEWS AGENT
// Economic Calendar, Breaking News, Central Banks, Macroeconomics
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias } from '../../core/types';

let seq = 0;

export class NewsAgent implements TradingAgent {
  readonly name: AgentName = 'NEWS';
  readonly description = 'Economic Calendar, Breaking News, Central Banks, Macroeconomics';
  readonly version = '1.0.0';

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const insights: AgentInsight[] = [];

    // Assess news risk
    const { minutesToHighImpactNews, inNewsBlackout } = context;

    if (inNewsBlackout) {
      insights.push({
        id: `news_${++seq}`, agentName: this.name, type: 'BLACKOUT_ACTIVE',
        direction: null, confidence: 95, timestamp: Date.now(),
        detail: 'High-impact news imminent or just released — extreme volatility expected.',
        weight: 4, tags: ['BLACKOUT', 'EXTREME_RISK'],
      });
    } else if (minutesToHighImpactNews !== undefined && minutesToHighImpactNews < 60) {
      insights.push({
        id: `news_${++seq}`, agentName: this.name, type: 'NEWS_APPROACHING',
        direction: null, confidence: 70, timestamp: Date.now(),
        detail: `High-impact news in ${minutesToHighImpactNews.toFixed(0)} minutes. Plan exits or avoid entries.`,
        weight: 2, tags: ['APPROACHING'],
      });
    } else {
      insights.push({
        id: `news_${++seq}`, agentName: this.name, type: 'NEWS_CLEAR',
        direction: null, confidence: 80, timestamp: Date.now(),
        detail: 'No imminent high-impact news. Calendar is clear for trading.',
        weight: 0.5, tags: ['CLEAR'],
      });
    }

    // News score for probability: 100 = clear, 0 = extreme news risk
    const newsScore = inNewsBlackout ? 10
      : minutesToHighImpactNews !== undefined
        ? Math.min(100, minutesToHighImpactNews * 1.5)
        : 85;

    return {
      agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL',
      confidence: newsScore, insights,
      narrative: inNewsBlackout
        ? 'NEWS BLACKOUT: Avoid all new entries.'
        : minutesToHighImpactNews && minutesToHighImpactNews < 60
          ? `News in ${minutesToHighImpactNews.toFixed(0)}m. Exercise caution.`
          : 'Calendar clear. Normal trading conditions.',
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  reset(): void {}
}
