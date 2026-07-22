// ============================================================================
// BINANCE DATA PROVIDER - Crypto market data
// REST API + WebSocket real-time | Spot + Futures
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface, ProviderConfig, ProviderCapabilities,
  ProviderStatus, ProviderName, RateLimiter,
} from '../provider-interface';

const BINANCE_CONFIG: ProviderConfig = {
  name: 'BINANCE',
  baseUrl: 'https://api.binance.com/api/v3',
  rateLimitPerMinute: 1200,
  rateLimitPerSecond: 10,
  timeout: 15000,
  retryAttempts: 3,
  retryDelay: 500,
  priority: 2,
  enabled: true,
};

const BINANCE_CAPABILITIES: ProviderCapabilities = {
  supportsHistorical: true,
  supportsRealtime: true,
  supportsTicks: true,
  supportedTimeframes: ['M1', 'M3', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1', 'W1', 'MN'],
  supportedAssetClasses: ['CRYPTO'],
  maxHistoryDays: 1500,
  websocketSupport: true,
};


export class BinanceProvider implements DataProviderInterface {
  readonly name: ProviderName = 'BINANCE';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = BINANCE_CAPABILITIES;
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;
  private wsConnections: Map<string, WebSocket> = new Map();

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...BINANCE_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    try {
      await this.rateLimiter.waitForSlot();
      const res = await fetch(`${this.config.baseUrl}/ping`, { signal: AbortSignal.timeout(5000) });
      this.status = res.ok ? 'CONNECTED' : 'ERROR';
    } catch { this.status = 'ERROR'; }
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    const interval = this.tfToInterval(timeframe);
    const allCandles: Candle[] = [];
    let startMs = startDate.getTime();
    const endMs = endDate.getTime();

    while (startMs < endMs) {
      await this.rateLimiter.waitForSlot();
      const url = `${this.config.baseUrl}/klines?symbol=${symbol}&interval=${interval}&startTime=${startMs}&endTime=${endMs}&limit=1000`;
      
      try {
        const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json() as any[];
        if (data.length === 0) break;

        for (const k of data) {
          allCandles.push({
            timestamp: k[0],
            open: parseFloat(k[1]),
            high: parseFloat(k[2]),
            low: parseFloat(k[3]),
            close: parseFloat(k[4]),
            volume: parseFloat(k[5]),
          });
        }
        startMs = data[data.length - 1][0] + 1;
      } catch (err) {
        console.warn(`[Binance] Fetch error:`, err);
        break;
      }
    }

    return allCandles.filter(c => c.timestamp <= endMs);
  }

  async getTicks(symbol: string, startDate: Date, endDate: Date): Promise<Tick[]> {
    // Binance aggTrades as tick proxy
    await this.rateLimiter.waitForSlot();
    const url = `${this.config.baseUrl}/aggTrades?symbol=${symbol}&startTime=${startDate.getTime()}&endTime=${endDate.getTime()}&limit=1000`;
    const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
    const data = await res.json() as any[];

    return data.map((t: any) => ({
      timestamp: t.T,
      bid: parseFloat(t.p),
      ask: parseFloat(t.p),
      bidVolume: parseFloat(t.q),
      askVolume: parseFloat(t.q),
    }));
  }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}/ticker/bookTicker?symbol=${symbol}`);
    const data = await res.json() as any;
    return { bid: parseFloat(data.bidPrice), ask: parseFloat(data.askPrice), timestamp: Date.now() };
  }

  subscribeRealtime(symbol: string, timeframe: Timeframe, onCandle: (c: Candle) => void, onTick?: (t: Tick) => void): () => void {
    const interval = this.tfToInterval(timeframe);
    const stream = `${symbol.toLowerCase()}@kline_${interval}`;
    const wsUrl = `wss://stream.binance.com:9443/ws/${stream}`;

    const ws = new WebSocket(wsUrl);
    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      if (msg.e === 'kline') {
        const k = msg.k;
        onCandle({
          timestamp: k.t,
          open: parseFloat(k.o),
          high: parseFloat(k.h),
          low: parseFloat(k.l),
          close: parseFloat(k.c),
          volume: parseFloat(k.v),
        });
      }
    };

    this.wsConnections.set(stream, ws);
    return () => { ws.close(); this.wsConnections.delete(stream); };
  }

  async getAvailableSymbols(): Promise<string[]> {
    await this.rateLimiter.waitForSlot();
    const res = await fetch(`${this.config.baseUrl}/exchangeInfo`);
    const data = await res.json() as any;
    return data.symbols?.map((s: any) => s.symbol) || [];
  }

  async isSymbolSupported(symbol: string): Promise<boolean> {
    const symbols = await this.getAvailableSymbols();
    return symbols.includes(symbol.toUpperCase());
  }

  disconnect(): void {
    for (const ws of this.wsConnections.values()) ws.close();
    this.wsConnections.clear();
    this.status = 'DISCONNECTED';
  }

  private tfToInterval(tf: Timeframe): string {
    const map: Record<string, string> = {
      'M1': '1m', 'M3': '3m', 'M5': '5m', 'M15': '15m', 'M30': '30m',
      'H1': '1h', 'H4': '4h', 'D1': '1d', 'W1': '1w', 'MN': '1M',
    };
    return map[tf] || '1m';
  }
}
