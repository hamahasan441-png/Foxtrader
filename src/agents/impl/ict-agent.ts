// ============================================================================
// AGENT 3: ICT AGENT
// OTE, Kill Zones, Judas Swing, Silver Bullet, AMD, SMT, Premium/Discount
// ============================================================================

import { TradingAgent, AgentContext, AgentOutput, AgentInsight, AgentName } from '../types';
import { Bias, Direction } from '../../core/types';
import { ICTConceptsAnalyzer } from '../../modules/ict-concepts';
import { findSwingPoints } from '../../core/utils';

let seq = 0;

export class ICTAgent implements TradingAgent {
  readonly name: AgentName = 'ICT';
  readonly description = 'OTE, Kill Zones, Judas Swing, Silver Bullet, AMD, SMT, Premium/Discount';
  readonly version = '1.0.0';
  private ict = new ICTConceptsAnalyzer();

  analyze(context: AgentContext): AgentOutput {
    const start = performance.now();
    const { candles, currentPrice } = context;
    const insights: AgentInsight[] = [];

    if (candles.length < 50) return this.empty(start);

    // Kill Zones
    const killZones = this.ict.getActiveKillZones(Date.now());
    if (killZones.length > 0) {
      insights.push({
        id: `ict_${++seq}`, agentName: this.name, type: 'KILL_ZONE',
        direction: null, confidence: 70, timestamp: Date.now(),
        detail: `Active Kill Zone: ${killZones.map(kz => kz.type).join(', ')}`,
        weight: 1.5, tags: killZones.map(kz => kz.type),
      });
    }

    // Premium/Discount
    const pd = this.ict.calculatePremiumDiscount(candles);
    const pdAligned = pd.currentPosition;
    insights.push({
      id: `ict_${++seq}`, agentName: this.name, type: 'PREMIUM_DISCOUNT',
      direction: pdAligned === 'DISCOUNT' ? 'BULLISH' : pdAligned === 'PREMIUM' ? 'BEARISH' : null,
      confidence: 65, timestamp: Date.now(), price: pd.equilibrium,
      detail: `Price in ${pdAligned} zone (${pd.percentageFromEquilibrium.toFixed(1)}% from EQ)`,
      weight: 2, tags: [pdAligned],
    });

    // OTE check
    const swings = findSwingPoints(candles, 5, 5);
    const highs = swings.filter(s => s.type === 'HIGH');
    const lows = swings.filter(s => s.type === 'LOW');
    if (highs.length > 0 && lows.length > 0) {
      const lastHigh = highs[highs.length - 1];
      const lastLow = lows[lows.length - 1];
      const direction: Direction = lastHigh.index > lastLow.index ? 'BEARISH' : 'BULLISH';
      const ote = this.ict.findOTE(lastHigh, lastLow, direction);

      if (currentPrice >= ote.zone.low && currentPrice <= ote.zone.high) {
        insights.push({
          id: `ict_${++seq}`, agentName: this.name, type: 'OTE',
          direction, confidence: 80, price: currentPrice,
          zone: { high: ote.zone.high, low: ote.zone.low },
          timestamp: Date.now(),
          detail: `Price in OTE zone (${direction}) — optimal entry area`,
          weight: 3, tags: ['OTE', 'AT_ZONE'],
        });
      }
    }

    // Turtle Soup
    const turtleSoups = this.ict.detectTurtleSoup(candles, []);
    const recentTS = turtleSoups.slice(-2);
    for (const ts of recentTS) {
      insights.push({
        id: `ict_${++seq}`, agentName: this.name, type: 'TURTLE_SOUP',
        direction: ts.direction, confidence: 72, price: ts.entryPrice,
        timestamp: ts.timestamp,
        detail: `Turtle Soup ${ts.direction} at ${ts.entryPrice.toFixed(5)}`,
        weight: 2, tags: ['TURTLE_SOUP'],
      });
    }

    // Opening Gaps (NDOG/WOG)
    const gaps = this.ict.detectOpeningGaps(candles);
    const unfilled = gaps.filter(g => !g.filled).slice(-2);
    for (const gap of unfilled) {
      insights.push({
        id: `ict_${++seq}`, agentName: this.name, type: gap.type,
        direction: currentPrice < gap.midpoint ? 'BULLISH' : 'BEARISH',
        confidence: 60, price: gap.midpoint, timestamp: gap.timestamp,
        zone: { high: gap.high, low: gap.low },
        detail: `${gap.type} unfilled at ${gap.midpoint.toFixed(5)}`,
        weight: 1.5, tags: [gap.type],
      });
    }

    // Daily Bias
    const dailyBias = this.ict.calculateDailyBias(candles, swings);

    // Aggregate
    const bullish = insights.filter(i => i.direction === 'BULLISH').length;
    const bearish = insights.filter(i => i.direction === 'BEARISH').length;
    const bias: Bias = dailyBias !== 'NEUTRAL' ? dailyBias
      : bullish > bearish ? 'BULLISH' : bearish > bullish ? 'BEARISH' : 'NEUTRAL';

    const confidence = Math.min(90, 40 + insights.filter(i => i.direction === bias).length * 8 +
      (killZones.length > 0 ? 10 : 0));

    return {
      agentName: this.name, status: 'COMPLETE', bias, confidence, insights,
      narrative: `ICT: Daily bias ${dailyBias}, ${killZones.length > 0 ? 'in kill zone' : 'outside KZ'}, ` +
        `PD: ${pdAligned}, ${insights.length} concepts identified.`,
      processingTimeMs: performance.now() - start, timestamp: Date.now(),
    };
  }

  private empty(start: number): AgentOutput {
    return { agentName: this.name, status: 'COMPLETE', bias: 'NEUTRAL', confidence: 30,
      insights: [], narrative: 'Insufficient data for ICT analysis.',
      processingTimeMs: performance.now() - start, timestamp: Date.now() };
  }

  reset(): void {}
}
