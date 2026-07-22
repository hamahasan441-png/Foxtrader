// ============================================================================
// ALPHA VANTAGE DATA PROVIDER - Stocks, Forex, Crypto
// REST API | Free tier with rate limits | Requires API key
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface, ProviderConfig, ProviderCapabilities,
  ProviderStatus, ProviderName, RateLimiter,
} from '../provider-interface';

const AV_CONFIG: ProviderConfig = {
  name: 'ALPHA_VANTAGE',
  baseUrl: 'https://www.alphavantage.co/query',
  rateLimitPerMinute: 5,
  rateLimitPerSecond: 1,
  timeout: 20000,
  retryAttempts: 2,
  retryDelay: 3000,
  priority: 7,
  enabled: false,
};

export class AlphaVantageProvider implements DataProviderInterface {
  readonly name: ProviderName = 'ALPHA_VANTAGE';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = {
    supportsHistorical: true,
    supportsRealtime: false,
    supportsTicks: false,
    supportedTimeframes: ['M1', 'M5', 'M15', 'M30', 'H1', 'D1', 'W1', 'MN'],
    supportedAssetClasses: ['STOCKS', 'FOREX', 'CRYPTO'],
    maxHistoryDays: 730,
    websocketSupport: false,
  };
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...AV_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    if (!this.config.apiKey) { this.status = 'ERROR'; return; }
    this.status = 'CONNECTED';
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    await this.rateLimiter.waitForSlot();
    const { func, interval } = this.tfToAVParams(timeframe);
    const url = `${this.config.baseUrl}?function=${func}&symbol=${symbol}${interval ? `&interval=${interval}` : ''}&outputsize=full&apikey=${this.config.apiKey}`;

    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
      if (!res.ok) return [];
      const data = await res.json() as any;

      // Alpha Vantage returns different keys per function
      const seriesKey = Object.keys(data).find(k => k.includes('Time Series'));
      if (!seriesKey) return [];

      const series = data[seriesKey];
      const candles: Candle[] = [];

      for (const [dateStr, values] of Object.entries(series) as [string, any][]) {
        const timestamp = new Date(dateStr).getTime();
        if (timestamp < startDate.getTime() || timestamp > endDate.getTime()) continue;

        candles.push({
          timestamp,
          open: parseFloat(values['1. open']),
          high: parseFloat(values['2. high']),
          low: parseFloat(values['3. low']),
          close: parseFloat(values['4. close']),
          volume: parseFloat(values['5. volume'] || '0'),
        });
      }

      return candles.sort((a, b) => a.timestamp - b.timestamp);
    } catch { return []; }
  }

  async getTicks(): Promise<Tick[]> { return []; }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}?function=GLOBAL_QUOTE&symbol=${symbol}&apikey=${this.config.apiKey}`);
    const data = await res.json() as any;
    const quote = data['Global Quote'] || {};
    const price = parseFloat(quote['05. price'] || '0');
    return { bid: price, ask: price, timestamp: Date.now() };
  }

  subscribeRealtime(): () => void {
    console.warn('[AlphaVantage] Real-time not supported');
    return () => {};
  }

  async getAvailableSymbols(): Promise<string[]> { return []; }
  async isSymbolSupported(): Promise<boolean> { return true; }
  disconnect(): void { this.status = 'DISCONNECTED'; }

  private tfToAVParams(tf: Timeframe): { func: string; interval?: string } {
    switch (tf) {
      case 'M1': return { func: 'TIME_SERIES_INTRADAY', interval: '1min' };
      case 'M5': return { func: 'TIME_SERIES_INTRADAY', interval: '5min' };
      case 'M15': return { func: 'TIME_SERIES_INTRADAY', interval: '15min' };
      case 'M30': return { func: 'TIME_SERIES_INTRADAY', interval: '30min' };
      case 'H1': return { func: 'TIME_SERIES_INTRADAY', interval: '60min' };
      case 'D1': return { func: 'TIME_SERIES_DAILY' };
      case 'W1': return { func: 'TIME_SERIES_WEEKLY' };
      case 'MN': return { func: 'TIME_SERIES_MONTHLY' };
      default: return { func: 'TIME_SERIES_INTRADAY', interval: '1min' };
    }
  }
}
