// ============================================================================
// OANDA DATA PROVIDER - Forex/CFD real-time + historical
// REST API v20 + Streaming API | Requires API key
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface, ProviderConfig, ProviderCapabilities,
  ProviderStatus, ProviderName, RateLimiter,
} from '../provider-interface';

const OANDA_CONFIG: ProviderConfig = {
  name: 'OANDA',
  baseUrl: 'https://api-fxpractice.oanda.com/v3',
  rateLimitPerMinute: 120,
  rateLimitPerSecond: 4,
  timeout: 20000,
  retryAttempts: 3,
  retryDelay: 1000,
  priority: 3,
  enabled: false, // Requires API key
};

export class OandaProvider implements DataProviderInterface {
  readonly name: ProviderName = 'OANDA';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = {
    supportsHistorical: true,
    supportsRealtime: true,
    supportsTicks: true,
    supportedTimeframes: ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1', 'W1', 'MN'],
    supportedAssetClasses: ['FOREX', 'INDICES', 'COMMODITIES'],
    maxHistoryDays: 3650,
    websocketSupport: true,
  };
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;
  private streamConnection: any = null;

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...OANDA_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    if (!this.config.apiKey) { this.status = 'ERROR'; return; }
    try {
      const res = await fetch(`${this.config.baseUrl}/accounts`, {
        headers: { 'Authorization': `Bearer ${this.config.apiKey}` },
        signal: AbortSignal.timeout(5000),
      });
      this.status = res.ok ? 'CONNECTED' : 'ERROR';
    } catch { this.status = 'ERROR'; }
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    const granularity = this.tfToGranularity(timeframe);
    const allCandles: Candle[] = [];
    let from = startDate.toISOString();

    while (new Date(from) < endDate) {
      await this.rateLimiter.waitForSlot();
      const url = `${this.config.baseUrl}/instruments/${symbol}/candles?granularity=${granularity}&from=${from}&count=5000&price=M`;
      
      try {
        const res = await fetch(url, {
          headers: { 'Authorization': `Bearer ${this.config.apiKey}` },
          signal: AbortSignal.timeout(this.config.timeout),
        });
        if (!res.ok) break;
        const data = await res.json() as any;
        const candles = data.candles || [];
        if (candles.length === 0) break;

        for (const c of candles) {
          if (!c.complete) continue; // Only confirmed candles - NO look-ahead
          allCandles.push({
            timestamp: new Date(c.time).getTime(),
            open: parseFloat(c.mid.o),
            high: parseFloat(c.mid.h),
            low: parseFloat(c.mid.l),
            close: parseFloat(c.mid.c),
            volume: c.volume || 0,
          });
        }
        from = candles[candles.length - 1].time;
      } catch { break; }
    }

    return allCandles.filter(c => c.timestamp <= endDate.getTime());
  }

  async getTicks(symbol: string, startDate: Date, endDate: Date): Promise<Tick[]> {
    // OANDA streaming ticks via REST (limited)
    return [];
  }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}/instruments/${symbol}/candles?count=1&granularity=S5&price=BA`, {
      headers: { 'Authorization': `Bearer ${this.config.apiKey}` },
    });
    const data = await res.json() as any;
    const c = data.candles?.[0];
    return { bid: parseFloat(c?.bid?.c || '0'), ask: parseFloat(c?.ask?.c || '0'), timestamp: Date.now() };
  }

  subscribeRealtime(symbol: string, _tf: Timeframe, onCandle: (c: Candle) => void, onTick?: (t: Tick) => void): () => void {
    // OANDA streaming via SSE
    const url = `https://stream-fxpractice.oanda.com/v3/accounts/${this.config.accountId}/pricing/stream?instruments=${symbol}`;
    const controller = new AbortController();

    fetch(url, {
      headers: { 'Authorization': `Bearer ${this.config.apiKey}` },
      signal: controller.signal,
    }).then(async (res) => {
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value);
        try {
          const msg = JSON.parse(text);
          if (msg.type === 'PRICE' && onTick) {
            onTick({
              timestamp: new Date(msg.time).getTime(),
              bid: parseFloat(msg.bids?.[0]?.price || '0'),
              ask: parseFloat(msg.asks?.[0]?.price || '0'),
            });
          }
        } catch { /* partial line */ }
      }
    }).catch(() => {});

    return () => controller.abort();
  }

  async getAvailableSymbols(): Promise<string[]> {
    return ['EUR_USD', 'GBP_USD', 'USD_JPY', 'USD_CHF', 'AUD_USD', 'XAU_USD', 'US30_USD', 'NAS100_USD'];
  }

  async isSymbolSupported(symbol: string): Promise<boolean> {
    return (await this.getAvailableSymbols()).includes(symbol);
  }

  disconnect(): void { this.status = 'DISCONNECTED'; }

  private tfToGranularity(tf: Timeframe): string {
    const map: Record<string, string> = {
      'M1': 'M1', 'M5': 'M5', 'M15': 'M15', 'M30': 'M30',
      'H1': 'H1', 'H4': 'H4', 'D1': 'D', 'W1': 'W', 'MN': 'M',
    };
    return map[tf] || 'M1';
  }
}
