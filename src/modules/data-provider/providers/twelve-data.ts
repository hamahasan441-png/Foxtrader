// ============================================================================
// TWELVE DATA PROVIDER - Global market data (Stocks, Forex, Crypto, ETF)
// REST API + WebSocket | Requires API key
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface, ProviderConfig, ProviderCapabilities,
  ProviderStatus, ProviderName, RateLimiter,
} from '../provider-interface';

const TD_CONFIG: ProviderConfig = {
  name: 'TWELVE_DATA',
  baseUrl: 'https://api.twelvedata.com',
  rateLimitPerMinute: 8, // Free tier
  rateLimitPerSecond: 1,
  timeout: 15000,
  retryAttempts: 2,
  retryDelay: 2000,
  priority: 6,
  enabled: false,
};

export class TwelveDataProvider implements DataProviderInterface {
  readonly name: ProviderName = 'TWELVE_DATA';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = {
    supportsHistorical: true,
    supportsRealtime: true,
    supportsTicks: false,
    supportedTimeframes: ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1', 'W1', 'MN'],
    supportedAssetClasses: ['STOCKS', 'FOREX', 'CRYPTO', 'INDICES'],
    maxHistoryDays: 365,
    websocketSupport: true,
  };
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;
  private ws: WebSocket | null = null;

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...TD_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    if (!this.config.apiKey) { this.status = 'ERROR'; return; }
    try {
      const res = await fetch(`${this.config.baseUrl}/api_usage?apikey=${this.config.apiKey}`, { signal: AbortSignal.timeout(5000) });
      this.status = res.ok ? 'CONNECTED' : 'ERROR';
    } catch { this.status = 'ERROR'; }
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    const interval = this.tfToInterval(timeframe);
    await this.rateLimiter.waitForSlot();

    const url = `${this.config.baseUrl}/time_series?symbol=${symbol}&interval=${interval}&start_date=${startDate.toISOString()}&end_date=${endDate.toISOString()}&outputsize=5000&apikey=${this.config.apiKey}`;
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
      if (!res.ok) return [];
      const data = await res.json() as any;
      if (data.status === 'error') return [];

      return (data.values || []).map((v: any) => ({
        timestamp: new Date(v.datetime).getTime(),
        open: parseFloat(v.open),
        high: parseFloat(v.high),
        low: parseFloat(v.low),
        close: parseFloat(v.close),
        volume: parseFloat(v.volume || '0'),
      })).sort((a: Candle, b: Candle) => a.timestamp - b.timestamp);
    } catch { return []; }
  }

  async getTicks(): Promise<Tick[]> { return []; }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}/price?symbol=${symbol}&apikey=${this.config.apiKey}`);
    const data = await res.json() as any;
    const price = parseFloat(data.price || '0');
    return { bid: price, ask: price, timestamp: Date.now() };
  }

  subscribeRealtime(symbol: string, _tf: Timeframe, onCandle: (c: Candle) => void): () => void {
    this.ws = new WebSocket(`wss://ws.twelvedata.com/v1/quotes/price?apikey=${this.config.apiKey}`);
    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ action: 'subscribe', params: { symbols: symbol } }));
    };
    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.event === 'price') {
          onCandle({ timestamp: msg.timestamp * 1000, open: msg.price, high: msg.price, low: msg.price, close: msg.price, volume: 0 });
        }
      } catch { /* ignore */ }
    };
    return () => { this.ws?.close(); this.ws = null; };
  }

  async getAvailableSymbols(): Promise<string[]> { return []; }
  async isSymbolSupported(): Promise<boolean> { return true; }
  disconnect(): void { this.ws?.close(); this.status = 'DISCONNECTED'; }

  private tfToInterval(tf: Timeframe): string {
    const map: Record<string, string> = {
      'M1': '1min', 'M5': '5min', 'M15': '15min', 'M30': '30min',
      'H1': '1h', 'H4': '4h', 'D1': '1day', 'W1': '1week', 'MN': '1month',
    };
    return map[tf] || '1min';
  }
}
