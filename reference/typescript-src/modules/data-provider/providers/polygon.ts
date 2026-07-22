// ============================================================================
// POLYGON.IO DATA PROVIDER - US Stocks, Forex, Crypto, Options
// REST API + WebSocket | Requires API key
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface, ProviderConfig, ProviderCapabilities,
  ProviderStatus, ProviderName, RateLimiter,
} from '../provider-interface';

const POLYGON_CONFIG: ProviderConfig = {
  name: 'POLYGON',
  baseUrl: 'https://api.polygon.io',
  rateLimitPerMinute: 5, // Free tier
  rateLimitPerSecond: 1,
  timeout: 15000,
  retryAttempts: 3,
  retryDelay: 1000,
  priority: 5,
  enabled: false,
};

export class PolygonProvider implements DataProviderInterface {
  readonly name: ProviderName = 'POLYGON';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = {
    supportsHistorical: true,
    supportsRealtime: true,
    supportsTicks: true,
    supportedTimeframes: ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1', 'W1', 'MN'],
    supportedAssetClasses: ['STOCKS', 'FOREX', 'CRYPTO', 'INDICES'],
    maxHistoryDays: 730,
    websocketSupport: true,
  };
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;
  private ws: WebSocket | null = null;

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...POLYGON_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    if (!this.config.apiKey) { this.status = 'ERROR'; return; }
    try {
      const res = await fetch(`${this.config.baseUrl}/v1/marketstatus/now?apiKey=${this.config.apiKey}`, { signal: AbortSignal.timeout(5000) });
      this.status = res.ok ? 'CONNECTED' : 'ERROR';
    } catch { this.status = 'ERROR'; }
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    const { multiplier, span } = this.tfToPolygon(timeframe);
    const from = startDate.toISOString().split('T')[0];
    const to = endDate.toISOString().split('T')[0];

    await this.rateLimiter.waitForSlot();
    const url = `${this.config.baseUrl}/v2/aggs/ticker/${symbol}/range/${multiplier}/${span}/${from}/${to}?adjusted=true&sort=asc&limit=50000&apiKey=${this.config.apiKey}`;

    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
      if (!res.ok) return [];
      const data = await res.json() as any;

      return (data.results || []).map((r: any) => ({
        timestamp: r.t,
        open: r.o, high: r.h, low: r.l, close: r.c, volume: r.v || 0,
      }));
    } catch { return []; }
  }

  async getTicks(symbol: string, startDate: Date, endDate: Date): Promise<Tick[]> {
    const date = startDate.toISOString().split('T')[0];
    await this.rateLimiter.waitForSlot();
    const url = `${this.config.baseUrl}/v3/trades/${symbol}?timestamp.gte=${startDate.getTime() * 1e6}&timestamp.lte=${endDate.getTime() * 1e6}&limit=50000&apiKey=${this.config.apiKey}`;
    try {
      const res = await fetch(url);
      const data = await res.json() as any;
      return (data.results || []).map((t: any) => ({
        timestamp: t.participant_timestamp / 1e6,
        bid: t.price, ask: t.price, bidVolume: t.size, askVolume: t.size,
      }));
    } catch { return []; }
  }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}/v2/last/trade/${symbol}?apiKey=${this.config.apiKey}`);
    const data = await res.json() as any;
    const price = data.results?.p || 0;
    return { bid: price, ask: price, timestamp: Date.now() };
  }

  subscribeRealtime(symbol: string, _tf: Timeframe, onCandle: (c: Candle) => void, onTick?: (t: Tick) => void): () => void {
    this.ws = new WebSocket('wss://socket.polygon.io/stocks');
    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ action: 'auth', params: this.config.apiKey }));
      this.ws?.send(JSON.stringify({ action: 'subscribe', params: `AM.${symbol}` }));
      if (onTick) this.ws?.send(JSON.stringify({ action: 'subscribe', params: `T.${symbol}` }));
    };
    this.ws.onmessage = (event) => {
      const msgs = JSON.parse(event.data);
      for (const msg of msgs) {
        if (msg.ev === 'AM') {
          onCandle({ timestamp: msg.s, open: msg.o, high: msg.h, low: msg.l, close: msg.c, volume: msg.v });
        }
        if (msg.ev === 'T' && onTick) {
          onTick({ timestamp: msg.t / 1e6, bid: msg.p, ask: msg.p, bidVolume: msg.s, askVolume: msg.s });
        }
      }
    };
    return () => { this.ws?.close(); this.ws = null; };
  }

  async getAvailableSymbols(): Promise<string[]> { return []; }
  async isSymbolSupported(): Promise<boolean> { return true; }
  disconnect(): void { this.ws?.close(); this.status = 'DISCONNECTED'; }

  private tfToPolygon(tf: Timeframe): { multiplier: number; span: string } {
    const map: Record<string, { multiplier: number; span: string }> = {
      'M1': { multiplier: 1, span: 'minute' }, 'M5': { multiplier: 5, span: 'minute' },
      'M15': { multiplier: 15, span: 'minute' }, 'M30': { multiplier: 30, span: 'minute' },
      'H1': { multiplier: 1, span: 'hour' }, 'H4': { multiplier: 4, span: 'hour' },
      'D1': { multiplier: 1, span: 'day' }, 'W1': { multiplier: 1, span: 'week' },
      'MN': { multiplier: 1, span: 'month' },
    };
    return map[tf] || { multiplier: 1, span: 'minute' };
  }
}
