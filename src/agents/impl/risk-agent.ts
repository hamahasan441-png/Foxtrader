// ============================================================================
// AGENT 7: RISK AGENT
// Monitor Risk, Exposure, Correlation, Margin, Drawdown, Volatility
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias } from '../../core/types';
import { calculateATR } from '../../core/utils';

let seq = 0;

export class RiskAgent implements TradingAgent {
  readonly name: AgentName = 'RISK';
  readonly description = 'Monitor risk, exposure, correlation, margin, drawdown, volatility';
  readonly version = '1.0.0';

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles, accountBalance, openPositionCount, dailyPnL } = context;
    const insights: AgentInsight[] = [];
    const balance = accountBalance || 100000;

    // Volatility assessment
    const atr = calculateATR(candles, 14);
    const currentATR = atr[candles.length - 1] || 0;
    const volPercent = (currentATR / candles[candles.length - 1]?.close || 1) * 100;
    const highVol = volPercent > 0.5;

    insights.push({
      id: `risk_${++seq}`, agentName: this.name, type: 'VOLATILITY',
      direction: null, confidence: 70, timestamp: Date.now(),
      detail: `ATR volatility: ${volPercent.toFixed(3)}% — ${highVol ? 'HIGH: reduce size' : 'normal'}`,
      weight: highVol ? 2 : 1, tags: highVol ? ['HIGH_VOL', 'CAUTION'] : ['NORMAL_VOL'],
    });

    // Exposure check
    const positions = openPositionCount || 0;
    const overExposed = positions >= 5;
    if (overExposed) {
      insights.push({
        id: `risk_${++seq}`, agentName: this.name, type: 'OVEREXPOSED',
        direction: null, confidence: 85, timestamp: Date.now(),
        detail: `${positions} open positions — overexposed. Reduce before new trades.`,
        weight: 3, tags: ['OVEREXPOSED', 'BLOCK'],
      });
    }

    // Daily PnL check
    const dailyLoss = dailyPnL ? Math.abs(Math.min(0, dailyPnL)) : 0;
    const dailyLossPercent = (dailyLoss / balance) * 100;
    if (dailyLossPercent >= 2) {
      insights.push({
        id: `risk_${++seq}`, agentName: this.name, type: 'DAILY_LOSS_LIMIT',
        direction: null, confidence: 95, timestamp: Date.now(),
        detail: `Daily loss ${dailyLossPercent.toFixed(1)}% — approaching/exceeding limit. HALT recommended.`,
        weight: 4, tags: ['HALT', 'DAILY_LIMIT'],
      });
    }

    // News proximity risk
    if (context.inNewsBlackout) {
      insights.push({
        id: `risk_${++seq}`, agentName: this.name, type: 'NEWS_BLACKOUT',
        direction: null, confidence: 90, timestamp: Date.now(),
        detail: 'In news blackout window — avoid new entries.',
        weight: 3, tags: ['NEWS', 'BLACKOUT', 'BLOCK'],
      });
    } else if (context.minutesToHighImpactNews !== undefined && context.minutesToHighImpactNews < 30) {
      insights.push({
        id: `risk_${++seq}`, agentName: this.name, type: 'NEWS_APPROACHING',
        direction: null, confidence: 75, timestamp: Date.now(),
        detail: `High-impact news in ${context.minutesToHighImpactNews?.toFixed(0)} min — caution.`,
        weight: 2, tags: ['NEWS', 'CAUTION'],
      });
    }

    // Risk verdict
    const blockers = insights.filter(i => i.tags.includes('BLOCK'));
    const bias: Bias = 'NEUTRAL'; // Risk agent doesn't vote direction
    const confidence = blockers.length > 0 ? 95 : 60;
    const canTrade = blockers.length === 0;

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative: canTrade
        ? `Risk: Clear to trade. Vol ${volPercent.toFixed(2)}%, ${positions} positions, daily PnL ${dailyLossPercent.toFixed(1)}%.`
        : `Risk: BLOCKED — ${blockers.map(b => b.type).join(', ')}. Do NOT open new trades.`,
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  reset(): void {}
}
