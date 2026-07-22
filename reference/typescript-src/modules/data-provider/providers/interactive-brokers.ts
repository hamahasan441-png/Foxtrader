// ============================================================================
// INTERACTIVE BROKERS DATA PROVIDER - Multi-asset institutional data
// Client Portal API | Requires running IB Gateway/TWS
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface, ProviderConfig, ProviderCapabilities,
  ProviderStatus, ProviderName, RateLimiter,
} from '../provider-interface';

const IB_CONFIG: ProviderConfig = {
  name: 'INTERACTIVE_BROKERS',
  baseUrl: 'https://localhost:5000/v1/api',
  rateLimitPerMinute: 60,
  rateLimitPerSecond: 2,
  timeout: 30000,
  retryAttempts: 2,
  retryDelay: 2000,
  priority: 4,
  enabled: false,
};

export class InteractiveBrokersProvider implements DataProviderInterface {
  readonly name: ProviderName = 'INTERACTIVE_BROKERS';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = {
    supportsHistorical: true,
    supportsRealtime: true,
    supportsTicks: true,
    supportedTimeframes: ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1', 'W1', 'MN'],
    supportedAssetClasses: ['FOREX', 'STOCKS', 'FUTURES', 'INDICES', 'COMMODITIES'],
    maxHistoryDays: 365,
    websocketSupport: true,
  };
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;
  private ws: WebSocket | null = null;

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...IB_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    try {
      const res = await fetch(`${this.config.baseUrl}/iserver/auth/status`, {
        signal: AbortSignal.timeout(5000),
      });
      const data = await res.json() as any;
      this.status = data.authenticated ? 'CONNECTED' : 'ERROR';
    } catch { this.status = 'ERROR'; }
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    await this.rateLimiter.waitForSlot();
    const period = this.tfToPeriod(timeframe);
    const bar = this.tfToBar(timeframe);
    const url = `${this.config.baseUrl}/iserver/marketdata/history?conid=${symbol}&period=${period}&bar=${bar}`;

    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
      if (!res.ok) return [];
      const data = await res.json() as any;

      return (data.data || [])
        .filter((d: any) => d.t * 1000 >= startDate.getTime() && d.t * 1000 <= endDate.getTime())
        .map((d: any) => ({
          timestamp: d.t * 1000,
          open: d.o, high: d.h, low: d.l, close: d.c, volume: d.v || 0,
        }));
    } catch { return []; }
  }

  async getTicks(symbol: string, startDate: Date, endDate: Date): Promise<Tick[]> { return []; }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}/iserver/marketdata/snapshot?conids=${symbol}&fields=84,85,86`);
    const data = await res.json() as any;
    const quote = data[0] || {};
    return { bid: quote['84'] || 0, ask: quote['86'] || 0, timestamp: Date.now() };
  }

  subscribeRealtime(symbol: string, _tf: Timeframe, onCandle: (c: Candle) => void): () => void {
    const wsUrl = `wss://localhost:5000/v1/api/ws`;
    this.ws = new WebSocket(wsUrl);
    this.ws.onopen = () => { this.ws?.send(JSON.stringify({ conid: symbol, fields: ['31', '84', '85', '86'] })); };
    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.conid === symbol && msg['31']) {
          onCandle({ timestamp: Date.now(), open: msg['31'], high: msg['31'], low: msg['31'], close: msg['31'], volume: 0 });
        }
      } catch { /* ignore */ }
    };
    return () => { this.ws?.close(); this.ws = null; };
  }

  async getAvailableSymbols(): Promise<string[]> { return []; /* Requires search */ }
  async isSymbolSupported(): Promise<boolean> { return true; }
  disconnect(): void { this.ws?.close(); this.status = 'DISCONNECTED'; }

  private tfToPeriod(tf: Timeframe): string {
    const map: Record<string, string> = { 'M1': '1d', 'M5': '1w', 'M15': '1w', 'H1': '1m', 'H4': '3m', 'D1': '1y', 'W1': '5y', 'MN': '10y' };
    return map[tf] || '1d';
  }
  private tfToBar(tf: Timeframe): string {
    const map: Record<string, string> = { 'M1': '1min', 'M5': '5min', 'M15': '15min', 'M30': '30min', 'H1': '1h', 'H4': '4h', 'D1': '1d', 'W1': '1w', 'MN': '1m' };
    return map[tf] || '1min';
  }
}
