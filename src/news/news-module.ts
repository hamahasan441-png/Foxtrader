// ============================================================================
// NEWS MODULE
// Economic Calendar | Impact levels | Central Banks | CPI/NFP/GDP/FOMC/ECB/BOE/BOJ
// AI explains potential market impact.
// ============================================================================

import { TradingEventBus } from '../core/event-bus';

export type NewsImpact = 'HIGH' | 'MEDIUM' | 'LOW';
export type Currency = 'USD' | 'EUR' | 'GBP' | 'JPY' | 'CHF' | 'AUD' | 'NZD' | 'CAD' | 'CNY';

export type NewsCategory =
  | 'INTEREST_RATE' | 'CPI' | 'NFP' | 'GDP' | 'PMI' | 'RETAIL_SALES'
  | 'EMPLOYMENT' | 'CENTRAL_BANK_SPEECH' | 'FOMC' | 'ECB' | 'BOE' | 'BOJ'
  | 'TRADE_BALANCE' | 'CONSUMER_CONFIDENCE' | 'MANUFACTURING' | 'HOUSING' | 'OTHER';

export interface NewsEvent {
  id: string;
  title: string;
  currency: Currency;
  impact: NewsImpact;
  category: NewsCategory;
  timestamp: number;
  actual?: number | string;
  forecast?: number | string;
  previous?: number | string;
  unit?: string;
  /** Central bank if applicable */
  centralBank?: 'FED' | 'ECB' | 'BOE' | 'BOJ' | 'SNB' | 'RBA' | 'RBNZ' | 'BOC' | 'PBOC';
  source?: string;
}

export interface NewsImpactAnalysis {
  event: NewsEvent;
  affectedSymbols: string[];
  expectedVolatility: 'EXTREME' | 'HIGH' | 'MODERATE' | 'LOW';
  directionBias: 'BULLISH' | 'BEARISH' | 'NEUTRAL' | 'UNCERTAIN';
  explanation: string;
  tradingRecommendation: string;
  blackoutWindow: { start: number; end: number };
  surpriseFactor?: number; // How much actual deviated from forecast
}

export interface CalendarFilter {
  currencies?: Currency[];
  impacts?: NewsImpact[];
  categories?: NewsCategory[];
  from?: number;
  to?: number;
}

let newsIdSeq = 0;

export class NewsModule {
  private events: Map<string, NewsEvent> = new Map();
  private eventBus?: TradingEventBus;
  /** Blackout window in minutes before/after high-impact news */
  private blackoutMinutes = { before: 15, after: 15 };

  constructor(eventBus?: TradingEventBus) {
    this.eventBus = eventBus;
  }

  // =========================================================================
  // EVENT MANAGEMENT
  // =========================================================================

  /**
   * Load economic calendar events (from provider/API)
   */
  loadEvents(events: Omit<NewsEvent, 'id'>[]): void {
    for (const e of events) {
      const event: NewsEvent = { ...e, id: `news_${Date.now()}_${++newsIdSeq}` };
      this.events.set(event.id, event);
    }
  }

  addEvent(event: Omit<NewsEvent, 'id'>): NewsEvent {
    const full: NewsEvent = { ...event, id: `news_${Date.now()}_${++newsIdSeq}` };
    this.events.set(full.id, full);
    return full;
  }

  /**
   * Update an event with actual release value (triggers impact analysis)
   */
  updateActual(id: string, actual: number | string): NewsImpactAnalysis | null {
    const event = this.events.get(id);
    if (!event) return null;
    event.actual = actual;
    const analysis = this.analyzeImpact(event);
    this.eventBus?.emit({ type: 'NEWS_RELEASE', data: analysis });
    return analysis;
  }

  // =========================================================================
  // CALENDAR QUERIES
  // =========================================================================

  getCalendar(filter?: CalendarFilter): NewsEvent[] {
    let events = Array.from(this.events.values());
    if (filter) {
      if (filter.currencies) events = events.filter(e => filter.currencies!.includes(e.currency));
      if (filter.impacts) events = events.filter(e => filter.impacts!.includes(e.impact));
      if (filter.categories) events = events.filter(e => filter.categories!.includes(e.category));
      if (filter.from) events = events.filter(e => e.timestamp >= filter.from!);
      if (filter.to) events = events.filter(e => e.timestamp <= filter.to!);
    }
    return events.sort((a, b) => a.timestamp - b.timestamp);
  }

  getUpcoming(withinMs: number = 86400000): NewsEvent[] {
    const now = Date.now();
    return this.getCalendar({ from: now, to: now + withinMs });
  }

  getHighImpactEvents(withinMs: number = 86400000): NewsEvent[] {
    return this.getUpcoming(withinMs).filter(e => e.impact === 'HIGH');
  }

  /**
   * Get news context for the probability engine (minutes to next high-impact, blackout status)
   */
  getNewsContext(currency?: Currency): { minutesToHighImpact: number; inBlackout: boolean } {
    const now = Date.now();
    let events = this.getHighImpactEvents();
    if (currency) events = events.filter(e => e.currency === currency);

    if (events.length === 0) return { minutesToHighImpact: Infinity, inBlackout: false };

    const next = events[0];
    const minutesToNews = (next.timestamp - now) / 60000;

    // Check blackout: within [before] minutes before or [after] minutes after any high-impact event
    const inBlackout = this.getCalendar({ impacts: ['HIGH'] }).some(e => {
      const start = e.timestamp - this.blackoutMinutes.before * 60000;
      const end = e.timestamp + this.blackoutMinutes.after * 60000;
      return now >= start && now <= end;
    });

    return { minutesToHighImpact: Math.max(0, minutesToNews), inBlackout };
  }

  // =========================================================================
  // AI IMPACT ANALYSIS
  // =========================================================================

  /**
   * AI analyzes the potential market impact of a news event
   */
  analyzeImpact(event: NewsEvent): NewsImpactAnalysis {
    const affectedSymbols = this.getAffectedSymbols(event.currency);
    const expectedVolatility = this.assessVolatility(event);
    const { directionBias, surpriseFactor } = this.assessDirection(event);
    const now = Date.now();

    return {
      event,
      affectedSymbols,
      expectedVolatility,
      directionBias,
      surpriseFactor,
      explanation: this.buildExplanation(event, directionBias, surpriseFactor),
      tradingRecommendation: this.buildRecommendation(event, expectedVolatility),
      blackoutWindow: {
        start: event.timestamp - this.blackoutMinutes.before * 60000,
        end: event.timestamp + this.blackoutMinutes.after * 60000,
      },
    };
  }

  private getAffectedSymbols(currency: Currency): string[] {
    const pairs: Record<Currency, string[]> = {
      USD: ['EURUSD', 'GBPUSD', 'USDJPY', 'USDCHF', 'AUDUSD', 'USDCAD', 'XAUUSD', 'US30', 'NAS100', 'US500'],
      EUR: ['EURUSD', 'EURGBP', 'EURJPY', 'EURCHF', 'EURAUD', 'DE30'],
      GBP: ['GBPUSD', 'EURGBP', 'GBPJPY', 'GBPCHF', 'UK100'],
      JPY: ['USDJPY', 'EURJPY', 'GBPJPY', 'AUDJPY', 'JP225'],
      CHF: ['USDCHF', 'EURCHF', 'GBPCHF'],
      AUD: ['AUDUSD', 'EURAUD', 'AUDJPY'],
      NZD: ['NZDUSD', 'AUDNZD'],
      CAD: ['USDCAD', 'CADJPY'],
      CNY: ['USDCNH'],
    };
    return pairs[currency] || [];
  }

  private assessVolatility(event: NewsEvent): NewsImpactAnalysis['expectedVolatility'] {
    // Highest-impact categories
    const extremeCategories: NewsCategory[] = ['INTEREST_RATE', 'NFP', 'FOMC', 'CPI'];
    if (event.impact === 'HIGH' && extremeCategories.includes(event.category)) return 'EXTREME';
    if (event.impact === 'HIGH') return 'HIGH';
    if (event.impact === 'MEDIUM') return 'MODERATE';
    return 'LOW';
  }

  private assessDirection(event: NewsEvent): { directionBias: NewsImpactAnalysis['directionBias']; surpriseFactor?: number } {
    // If actual is available, compare to forecast
    if (event.actual !== undefined && event.forecast !== undefined) {
      const actualNum = typeof event.actual === 'number' ? event.actual : parseFloat(String(event.actual));
      const forecastNum = typeof event.forecast === 'number' ? event.forecast : parseFloat(String(event.forecast));

      if (!isNaN(actualNum) && !isNaN(forecastNum) && forecastNum !== 0) {
        const surprise = ((actualNum - forecastNum) / Math.abs(forecastNum)) * 100;

        // For most indicators, higher-than-forecast = currency bullish
        // (rate hikes, strong GDP, strong employment, higher CPI => hawkish)
        const bullishOnBeat: NewsCategory[] = ['INTEREST_RATE', 'CPI', 'GDP', 'NFP', 'EMPLOYMENT', 'PMI', 'RETAIL_SALES', 'CONSUMER_CONFIDENCE', 'MANUFACTURING'];
        const isBullishIndicator = bullishOnBeat.includes(event.category);

        let bias: NewsImpactAnalysis['directionBias'];
        if (Math.abs(surprise) < 1) bias = 'NEUTRAL';
        else if (surprise > 0) bias = isBullishIndicator ? 'BULLISH' : 'BEARISH';
        else bias = isBullishIndicator ? 'BEARISH' : 'BULLISH';

        return { directionBias: bias, surpriseFactor: surprise };
      }
    }
    return { directionBias: 'UNCERTAIN' };
  }

  private buildExplanation(event: NewsEvent, bias: NewsImpactAnalysis['directionBias'], surprise?: number): string {
    const parts: string[] = [];

    // Category context
    const contexts: Partial<Record<NewsCategory, string>> = {
      INTEREST_RATE: `Interest rate decisions are the most impactful driver for ${event.currency}. Rate hikes typically strengthen the currency (hawkish), cuts weaken it (dovish).`,
      CPI: `CPI measures inflation. Higher-than-expected CPI raises rate-hike expectations, typically bullish for ${event.currency}.`,
      NFP: `Non-Farm Payrolls is the key US employment metric. Strong jobs data supports USD strength and risk sentiment.`,
      GDP: `GDP reflects overall economic health. Beats signal a strong economy, generally bullish for ${event.currency}.`,
      FOMC: `The FOMC sets US monetary policy. Hawkish tone (rate hikes/tapering) is USD-bullish; dovish is USD-bearish.`,
      ECB: `ECB policy drives EUR. Watch the statement and press conference tone for forward guidance.`,
      BOE: `BOE decisions drive GBP. Focus on the vote split and inflation commentary.`,
      BOJ: `BOJ policy affects JPY. Any shift away from ultra-loose policy is strongly JPY-bullish.`,
      EMPLOYMENT: `Employment data signals economic strength and influences central bank policy.`,
      PMI: `PMI is a leading indicator of economic activity. Above 50 = expansion.`,
    };
    if (contexts[event.category]) parts.push(contexts[event.category]!);

    // Surprise analysis
    if (surprise !== undefined) {
      const dir = surprise > 0 ? 'beat' : 'missed';
      parts.push(`Actual ${dir} forecast by ${Math.abs(surprise).toFixed(1)}%, suggesting ${bias.toLowerCase()} pressure on ${event.currency}.`);
    } else {
      parts.push(`Awaiting release. Forecast: ${event.forecast ?? 'N/A'}, Previous: ${event.previous ?? 'N/A'}.`);
    }

    return parts.join(' ');
  }

  private buildRecommendation(event: NewsEvent, volatility: NewsImpactAnalysis['expectedVolatility']): string {
    if (volatility === 'EXTREME') {
      return `AVOID new positions ${this.blackoutMinutes.before} min before through ${this.blackoutMinutes.after} min after. Expect violent spikes, spread widening, and potential stop hunts. Consider closing or reducing exposure beforehand.`;
    }
    if (volatility === 'HIGH') {
      return `Exercise caution. Widen stops or stand aside during the release. Wait for the initial spike to settle before entering.`;
    }
    if (volatility === 'MODERATE') {
      return `Minor volatility expected. Trade normally but be aware of the release time.`;
    }
    return `Low impact. No special precautions needed.`;
  }

  // =========================================================================
  // CONFIG & GETTERS
  // =========================================================================

  setBlackoutWindow(beforeMin: number, afterMin: number): void {
    this.blackoutMinutes = { before: beforeMin, after: afterMin };
  }

  getEvent(id: string): NewsEvent | undefined { return this.events.get(id); }
  getAllEvents(): NewsEvent[] { return Array.from(this.events.values()); }

  /** Generate a mock/sample calendar (until a live provider is wired) */
  generateSampleCalendar(baseTime: number = Date.now()): void {
    const samples: Omit<NewsEvent, 'id'>[] = [
      { title: 'Fed Interest Rate Decision', currency: 'USD', impact: 'HIGH', category: 'FOMC', centralBank: 'FED', timestamp: baseTime + 3600000, forecast: 5.5, previous: 5.5, unit: '%' },
      { title: 'Non-Farm Payrolls', currency: 'USD', impact: 'HIGH', category: 'NFP', timestamp: baseTime + 7200000, forecast: 180, previous: 175, unit: 'K' },
      { title: 'CPI y/y', currency: 'USD', impact: 'HIGH', category: 'CPI', timestamp: baseTime + 10800000, forecast: 3.2, previous: 3.4, unit: '%' },
      { title: 'ECB Rate Decision', currency: 'EUR', impact: 'HIGH', category: 'ECB', centralBank: 'ECB', timestamp: baseTime + 14400000, forecast: 4.5, previous: 4.5, unit: '%' },
      { title: 'BOE Rate Decision', currency: 'GBP', impact: 'HIGH', category: 'BOE', centralBank: 'BOE', timestamp: baseTime + 18000000, forecast: 5.25, previous: 5.25, unit: '%' },
      { title: 'BOJ Policy Rate', currency: 'JPY', impact: 'HIGH', category: 'BOJ', centralBank: 'BOJ', timestamp: baseTime + 21600000, forecast: -0.1, previous: -0.1, unit: '%' },
      { title: 'GDP q/q', currency: 'USD', impact: 'MEDIUM', category: 'GDP', timestamp: baseTime + 25200000, forecast: 2.1, previous: 2.0, unit: '%' },
      { title: 'Manufacturing PMI', currency: 'EUR', impact: 'MEDIUM', category: 'PMI', timestamp: baseTime + 28800000, forecast: 49.5, previous: 48.8 },
      { title: 'Retail Sales m/m', currency: 'GBP', impact: 'LOW', category: 'RETAIL_SALES', timestamp: baseTime + 32400000, forecast: 0.3, previous: 0.2, unit: '%' },
    ];
    this.loadEvents(samples);
  }

  reset(): void { this.events.clear(); }
}
