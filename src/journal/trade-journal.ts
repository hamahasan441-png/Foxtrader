// ============================================================================
// TRADE JOURNAL
// Auto-saves: Screenshot, Chart, Indicators, Entry, Exit, SL, TP, Notes,
// Emotion, Mistakes, Success Rate. AI analyzes journal for improvements.
// ============================================================================

import { Direction, Timeframe } from '../core/types';
import { TradingEventBus } from '../core/event-bus';

export type Emotion =
  | 'CONFIDENT' | 'CALM' | 'FEARFUL' | 'GREEDY' | 'FOMO'
  | 'REVENGE' | 'ANXIOUS' | 'DISCIPLINED' | 'IMPATIENT' | 'NEUTRAL';

export type TradeMistake =
  | 'NO_STOP_LOSS' | 'MOVED_STOP' | 'OVERSIZED' | 'CHASED_ENTRY'
  | 'AGAINST_BIAS' | 'NO_CONFIRMATION' | 'EARLY_EXIT' | 'LATE_EXIT'
  | 'OVERTRADING' | 'IGNORED_NEWS' | 'REVENGE_TRADE' | 'FOMO_ENTRY'
  | 'NO_PLAN' | 'BROKE_RULES';

export type TradeResult = 'WIN' | 'LOSS' | 'BREAKEVEN' | 'OPEN';

export interface JournalEntry {
  id: string;
  symbol: string;
  timeframe: Timeframe;
  direction: Direction;
  result: TradeResult;

  // Execution details
  entryPrice: number;
  exitPrice?: number;
  stopLoss: number;
  takeProfits: number[];
  volume: number;
  entryTime: number;
  exitTime?: number;

  // Performance
  pnl: number;
  pnlPercent: number;
  riskReward: number;
  plannedRR: number;
  rMultiple: number; // Actual R gained/lost

  // Context snapshot
  screenshot?: string; // base64 data URL
  chartState?: Record<string, unknown>; // Serialized chart annotations
  indicators: Record<string, number>; // Indicator values at entry
  setupType: string;
  confidence: number;
  confluenceFactors: string[];

  // Trader reflection
  notes: string;
  emotion: Emotion;
  emotionBefore?: Emotion;
  emotionAfter?: Emotion;
  mistakes: TradeMistake[];
  followedPlan: boolean;
  tags: string[];

  createdAt: number;
  updatedAt: number;
}

export interface JournalStats {
  totalTrades: number;
  wins: number;
  losses: number;
  breakeven: number;
  winRate: number;
  avgWin: number;
  avgLoss: number;
  profitFactor: number;
  totalPnL: number;
  avgRMultiple: number;
  expectancy: number;
  largestWin: number;
  largestLoss: number;
  avgHoldingTime: number;
  planAdherenceRate: number;
  bestSetup: string;
  worstSetup: string;
}

export interface JournalInsight {
  category: 'STRENGTH' | 'WEAKNESS' | 'PATTERN' | 'RECOMMENDATION';
  title: string;
  detail: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  affectedTrades: number;
  suggestion: string;
}

let journalIdSeq = 0;

export class TradeJournal {
  private entries: Map<string, JournalEntry> = new Map();
  private eventBus?: TradingEventBus;
  private storageKey = 'trading_journal';

  constructor(eventBus?: TradingEventBus) {
    this.eventBus = eventBus;
    this.load();
  }

  // =========================================================================
  // ENTRY MANAGEMENT
  // =========================================================================

  /**
   * Create a new journal entry (auto-called when a trade opens)
   */
  createEntry(data: Partial<JournalEntry> & {
    symbol: string;
    timeframe: Timeframe;
    direction: Direction;
    entryPrice: number;
    stopLoss: number;
    volume: number;
  }): JournalEntry {
    const entry: JournalEntry = {
      id: `journal_${Date.now()}_${++journalIdSeq}`,
      symbol: data.symbol,
      timeframe: data.timeframe,
      direction: data.direction,
      result: 'OPEN',
      entryPrice: data.entryPrice,
      exitPrice: undefined,
      stopLoss: data.stopLoss,
      takeProfits: data.takeProfits ?? [],
      volume: data.volume,
      entryTime: data.entryTime ?? Date.now(),
      pnl: 0,
      pnlPercent: 0,
      riskReward: 0,
      plannedRR: data.plannedRR ?? 0,
      rMultiple: 0,
      screenshot: data.screenshot,
      chartState: data.chartState,
      indicators: data.indicators ?? {},
      setupType: data.setupType ?? 'Manual',
      confidence: data.confidence ?? 0,
      confluenceFactors: data.confluenceFactors ?? [],
      notes: data.notes ?? '',
      emotion: data.emotion ?? 'NEUTRAL',
      emotionBefore: data.emotionBefore,
      mistakes: data.mistakes ?? [],
      followedPlan: data.followedPlan ?? true,
      tags: data.tags ?? [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    this.entries.set(entry.id, entry);
    this.persist();
    this.eventBus?.emit({ type: 'JOURNAL_ENTRY' as any, data: entry });
    return entry;
  }

  /**
   * Close a journal entry with exit details
   */
  closeEntry(id: string, exitPrice: number, exitTime?: number): JournalEntry | null {
    const entry = this.entries.get(id);
    if (!entry) return null;

    entry.exitPrice = exitPrice;
    entry.exitTime = exitTime ?? Date.now();

    // Calculate P&L
    const priceDiff = entry.direction === 'BULLISH'
      ? exitPrice - entry.entryPrice
      : entry.entryPrice - exitPrice;
    entry.pnl = priceDiff * entry.volume * 100000;
    entry.pnlPercent = (priceDiff / entry.entryPrice) * 100;

    // R-multiple
    const risk = Math.abs(entry.entryPrice - entry.stopLoss);
    entry.rMultiple = risk > 0 ? priceDiff / risk : 0;
    entry.riskReward = entry.rMultiple;

    // Result
    if (Math.abs(entry.pnl) < 0.01) entry.result = 'BREAKEVEN';
    else entry.result = entry.pnl > 0 ? 'WIN' : 'LOSS';

    entry.updatedAt = Date.now();
    this.persist();
    return entry;
  }

  /**
   * Update reflection fields (notes, emotion, mistakes)
   */
  updateReflection(id: string, reflection: {
    notes?: string;
    emotion?: Emotion;
    emotionAfter?: Emotion;
    mistakes?: TradeMistake[];
    followedPlan?: boolean;
    tags?: string[];
  }): boolean {
    const entry = this.entries.get(id);
    if (!entry) return false;
    Object.assign(entry, reflection);
    entry.updatedAt = Date.now();
    this.persist();
    return true;
  }

  /**
   * Attach a screenshot (base64 data URL) to an entry
   */
  attachScreenshot(id: string, dataUrl: string): boolean {
    const entry = this.entries.get(id);
    if (!entry) return false;
    entry.screenshot = dataUrl;
    entry.updatedAt = Date.now();
    this.persist();
    return true;
  }


  // =========================================================================
  // STATISTICS
  // =========================================================================

  /**
   * Compute journal statistics
   */
  getStats(): JournalStats {
    const closed = this.getClosedEntries();
    const wins = closed.filter(e => e.result === 'WIN');
    const losses = closed.filter(e => e.result === 'LOSS');
    const be = closed.filter(e => e.result === 'BREAKEVEN');

    const grossProfit = wins.reduce((s, e) => s + e.pnl, 0);
    const grossLoss = Math.abs(losses.reduce((s, e) => s + e.pnl, 0));
    const totalPnL = closed.reduce((s, e) => s + e.pnl, 0);

    const avgWin = wins.length ? grossProfit / wins.length : 0;
    const avgLoss = losses.length ? grossLoss / losses.length : 0;
    const winRate = closed.length ? (wins.length / closed.length) * 100 : 0;

    const avgR = closed.length ? closed.reduce((s, e) => s + e.rMultiple, 0) / closed.length : 0;

    // Expectancy = (WinRate * AvgWin) - (LossRate * AvgLoss)
    const lossRate = closed.length ? losses.length / closed.length : 0;
    const expectancy = (winRate / 100) * avgWin - lossRate * avgLoss;

    // Holding times
    const holdingTimes = closed
      .filter(e => e.exitTime)
      .map(e => (e.exitTime! - e.entryTime));
    const avgHolding = holdingTimes.length ? holdingTimes.reduce((a, b) => a + b, 0) / holdingTimes.length : 0;

    // Best/worst setups by win rate
    const setupPerf = this.getSetupPerformance(closed);

    // Plan adherence
    const followedPlan = closed.filter(e => e.followedPlan).length;

    return {
      totalTrades: closed.length,
      wins: wins.length,
      losses: losses.length,
      breakeven: be.length,
      winRate,
      avgWin,
      avgLoss,
      profitFactor: grossLoss > 0 ? grossProfit / grossLoss : grossProfit > 0 ? Infinity : 0,
      totalPnL,
      avgRMultiple: avgR,
      expectancy,
      largestWin: wins.length ? Math.max(...wins.map(e => e.pnl)) : 0,
      largestLoss: losses.length ? Math.min(...losses.map(e => e.pnl)) : 0,
      avgHoldingTime: avgHolding,
      planAdherenceRate: closed.length ? (followedPlan / closed.length) * 100 : 0,
      bestSetup: setupPerf.best,
      worstSetup: setupPerf.worst,
    };
  }

  private getSetupPerformance(closed: JournalEntry[]): { best: string; worst: string } {
    const bySetup = new Map<string, { wins: number; total: number }>();
    for (const e of closed) {
      const s = bySetup.get(e.setupType) || { wins: 0, total: 0 };
      s.total++;
      if (e.result === 'WIN') s.wins++;
      bySetup.set(e.setupType, s);
    }
    let best = 'N/A', worst = 'N/A', bestRate = -1, worstRate = 101;
    for (const [setup, perf] of bySetup) {
      if (perf.total < 2) continue;
      const rate = (perf.wins / perf.total) * 100;
      if (rate > bestRate) { bestRate = rate; best = setup; }
      if (rate < worstRate) { worstRate = rate; worst = setup; }
    }
    return { best, worst };
  }

  // =========================================================================
  // AI JOURNAL ANALYSIS - Improvement Suggestions
  // =========================================================================

  /**
   * AI analyzes the journal and provides improvement insights
   */
  analyzeJournal(): JournalInsight[] {
    const insights: JournalInsight[] = [];
    const closed = this.getClosedEntries();
    if (closed.length < 3) {
      return [{
        category: 'RECOMMENDATION',
        title: 'Insufficient data',
        detail: 'Log at least 10 trades for meaningful analysis.',
        severity: 'INFO',
        affectedTrades: closed.length,
        suggestion: 'Keep journaling every trade including screenshots and emotions.',
      }];
    }

    const stats = this.getStats();

    // 1. Emotion-based patterns
    const emotionLosses = this.analyzeEmotionImpact(closed);
    insights.push(...emotionLosses);

    // 2. Mistake frequency
    insights.push(...this.analyzeMistakes(closed));

    // 3. Plan adherence
    if (stats.planAdherenceRate < 70) {
      insights.push({
        category: 'WEAKNESS',
        title: 'Low plan adherence',
        detail: `You followed your plan on only ${stats.planAdherenceRate.toFixed(0)}% of trades.`,
        severity: 'CRITICAL',
        affectedTrades: closed.filter(e => !e.followedPlan).length,
        suggestion: 'Commit to only taking trades that match your pre-defined plan. Skip impulse trades.',
      });
    }

    // 4. Risk:Reward analysis
    const avgPlannedRR = closed.reduce((s, e) => s + e.plannedRR, 0) / closed.length;
    if (avgPlannedRR < 2 && avgPlannedRR > 0) {
      insights.push({
        category: 'WEAKNESS',
        title: 'Low average Risk:Reward',
        detail: `Average planned R:R is ${avgPlannedRR.toFixed(1)}. Higher R:R improves long-term expectancy.`,
        severity: 'WARNING',
        affectedTrades: closed.length,
        suggestion: 'Target minimum 1:2 R:R. Let winners run to further liquidity targets.',
      });
    }

    // 5. Win rate vs expectancy
    if (stats.winRate < 40 && stats.expectancy <= 0) {
      insights.push({
        category: 'WEAKNESS',
        title: 'Negative expectancy',
        detail: `Win rate ${stats.winRate.toFixed(0)}% with negative expectancy. Strategy needs revision.`,
        severity: 'CRITICAL',
        affectedTrades: closed.length,
        suggestion: 'Review entry criteria and tighten confluence requirements before entering.',
      });
    } else if (stats.expectancy > 0) {
      insights.push({
        category: 'STRENGTH',
        title: 'Positive expectancy',
        detail: `Your system has positive expectancy (${stats.expectancy.toFixed(2)} per trade).`,
        severity: 'INFO',
        affectedTrades: closed.length,
        suggestion: 'Maintain discipline and consider scaling position size gradually.',
      });
    }

    // 6. Best setup exploitation
    if (stats.bestSetup !== 'N/A') {
      insights.push({
        category: 'PATTERN',
        title: `Best performing setup: ${stats.bestSetup}`,
        detail: `${stats.bestSetup} is your most profitable setup type.`,
        severity: 'INFO',
        affectedTrades: closed.filter(e => e.setupType === stats.bestSetup).length,
        suggestion: `Focus more on ${stats.bestSetup} setups and reduce lower-quality trades.`,
      });
    }

    // 7. Overtrading detection
    insights.push(...this.analyzeOvertrading(closed));

    return insights;
  }

  private analyzeEmotionImpact(closed: JournalEntry[]): JournalInsight[] {
    const insights: JournalInsight[] = [];
    const negativeEmotions: Emotion[] = ['FEARFUL', 'GREEDY', 'FOMO', 'REVENGE', 'ANXIOUS', 'IMPATIENT'];

    const emotionalTrades = closed.filter(e => negativeEmotions.includes(e.emotion));
    const emotionalLosses = emotionalTrades.filter(e => e.result === 'LOSS');

    if (emotionalTrades.length >= 3 && emotionalLosses.length / emotionalTrades.length > 0.6) {
      insights.push({
        category: 'WEAKNESS',
        title: 'Emotional trading hurts performance',
        detail: `${((emotionalLosses.length / emotionalTrades.length) * 100).toFixed(0)}% of emotionally-driven trades lost.`,
        severity: 'CRITICAL',
        affectedTrades: emotionalTrades.length,
        suggestion: 'When feeling FOMO, greed, or revenge, step away. Only trade in a CALM/DISCIPLINED state.',
      });
    }
    return insights;
  }

  private analyzeMistakes(closed: JournalEntry[]): JournalInsight[] {
    const mistakeCount = new Map<TradeMistake, number>();
    for (const e of closed) {
      for (const m of e.mistakes) {
        mistakeCount.set(m, (mistakeCount.get(m) || 0) + 1);
      }
    }

    const insights: JournalInsight[] = [];
    const sorted = Array.from(mistakeCount.entries()).sort((a, b) => b[1] - a[1]);

    for (const [mistake, count] of sorted.slice(0, 3)) {
      if (count >= 2) {
        insights.push({
          category: 'WEAKNESS',
          title: `Recurring mistake: ${mistake.replace(/_/g, ' ')}`,
          detail: `This mistake appeared in ${count} trades.`,
          severity: count >= 4 ? 'CRITICAL' : 'WARNING',
          affectedTrades: count,
          suggestion: this.getMistakeSuggestion(mistake),
        });
      }
    }
    return insights;
  }

  private getMistakeSuggestion(mistake: TradeMistake): string {
    const suggestions: Record<TradeMistake, string> = {
      NO_STOP_LOSS: 'Always set a stop loss before entering. Use the risk engine to auto-calculate it.',
      MOVED_STOP: 'Never move your stop away from price. Only trail it in profit.',
      OVERSIZED: 'Stick to your fixed risk % per trade. Let the risk engine size positions.',
      CHASED_ENTRY: 'Wait for price to come to your level. Use limit orders at OB/FVG zones.',
      AGAINST_BIAS: 'Only trade in the direction of HTF bias unless there is strong reversal confluence.',
      NO_CONFIRMATION: 'Wait for confirmation (CHOCH, sweep, rejection) before entering.',
      EARLY_EXIT: 'Trust your plan. Let price reach TP1 before considering manual exit.',
      LATE_EXIT: 'Take partials at TP levels. Do not get greedy near targets.',
      OVERTRADING: 'Limit your daily trades. Quality over quantity.',
      IGNORED_NEWS: 'Check the economic calendar. Avoid entries near high-impact news.',
      REVENGE_TRADE: 'After a loss, take a break. Never trade to "win back" money.',
      FOMO_ENTRY: 'If you missed the entry, wait for the next setup. Do not chase.',
      NO_PLAN: 'Always generate a trade plan before entering. Use the AI Trade Planner.',
      BROKE_RULES: 'Review and recommit to your trading rules daily.',
    };
    return suggestions[mistake] || 'Review this pattern and adjust your approach.';
  }

  private analyzeOvertrading(closed: JournalEntry[]): JournalInsight[] {
    // Group by day
    const byDay = new Map<string, number>();
    for (const e of closed) {
      const day = new Date(e.entryTime).toISOString().split('T')[0];
      byDay.set(day, (byDay.get(day) || 0) + 1);
    }
    const overtradeDays = Array.from(byDay.values()).filter(c => c > 5).length;
    if (overtradeDays >= 2) {
      return [{
        category: 'WEAKNESS',
        title: 'Overtrading detected',
        detail: `${overtradeDays} days with more than 5 trades.`,
        severity: 'WARNING',
        affectedTrades: 0,
        suggestion: 'Set a maximum daily trade limit (2-3 high quality setups).',
      }];
    }
    return [];
  }

  // =========================================================================
  // PERSISTENCE (localStorage / can be synced to cloud)
  // =========================================================================

  private persist(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      const data = JSON.stringify(Array.from(this.entries.values()));
      localStorage.setItem(this.storageKey, data);
    } catch (err) {
      console.warn('[Journal] Persist failed:', err);
    }
  }

  private load(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      const data = localStorage.getItem(this.storageKey);
      if (data) {
        const entries: JournalEntry[] = JSON.parse(data);
        for (const e of entries) this.entries.set(e.id, e);
      }
    } catch (err) {
      console.warn('[Journal] Load failed:', err);
    }
  }

  // =========================================================================
  // GETTERS
  // =========================================================================

  getEntry(id: string): JournalEntry | undefined { return this.entries.get(id); }
  getAllEntries(): JournalEntry[] { return Array.from(this.entries.values()).sort((a, b) => b.entryTime - a.entryTime); }
  getClosedEntries(): JournalEntry[] { return this.getAllEntries().filter(e => e.result !== 'OPEN'); }
  getOpenEntries(): JournalEntry[] { return this.getAllEntries().filter(e => e.result === 'OPEN'); }
  getEntriesBySymbol(symbol: string): JournalEntry[] { return this.getAllEntries().filter(e => e.symbol === symbol); }

  /** Export journal as JSON (for cloud sync / backup) */
  export(): string { return JSON.stringify(Array.from(this.entries.values())); }

  /** Import journal from JSON */
  import(json: string): void {
    try {
      const entries: JournalEntry[] = JSON.parse(json);
      for (const e of entries) this.entries.set(e.id, e);
      this.persist();
    } catch (err) {
      console.warn('[Journal] Import failed:', err);
    }
  }

  deleteEntry(id: string): boolean {
    const result = this.entries.delete(id);
    if (result) this.persist();
    return result;
  }

  reset(): void {
    this.entries.clear();
    this.persist();
  }
}
