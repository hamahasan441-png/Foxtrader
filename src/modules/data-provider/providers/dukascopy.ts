// ============================================================================
// DUKASCOPY DATA PROVIDER - Primary historical data source
// Tick + M1-Monthly | Binary format parsing | Free historical data
// ============================================================================

import { Candle, Tick, Timeframe } from '../../../core/types';
import {
  DataProviderInterface,
  ProviderConfig,
  ProviderCapabilities,
  ProviderStatus,
  ProviderName,
  RateLimiter,
} from '../provider-interface';

const DUKASCOPY_CONFIG: ProviderConfig = {
  name: 'DUKASCOPY',
  baseUrl: 'https://datafeed.dukascopy.com/datafeed',
  rateLimitPerMinute: 120,
  rateLimitPerSecond: 5,
  timeout: 30000,
  retryAttempts: 3,
  retryDelay: 1000,
  priority: 1, // Highest priority (primary)
  enabled: true,
};

const DUKASCOPY_CAPABILITIES: ProviderCapabilities = {
  supportsHistorical: true,
  supportsRealtime: false,
  supportsTicks: true,
  supportedTimeframes: ['TICK', 'M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1', 'W1', 'MN'],
  supportedAssetClasses: ['FOREX', 'INDICES', 'COMMODITIES', 'CRYPTO'],
  maxHistoryDays: 7300, // ~20 years
  websocketSupport: false,
};


export class DukascopyProvider implements DataProviderInterface {
  readonly name: ProviderName = 'DUKASCOPY';
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities = DUKASCOPY_CAPABILITIES;
  status: ProviderStatus = 'DISCONNECTED';
  private rateLimiter: RateLimiter;

  constructor(config: Partial<ProviderConfig> = {}) {
    this.config = { ...DUKASCOPY_CONFIG, ...config };
    this.rateLimiter = new RateLimiter(this.config.rateLimitPerMinute, this.config.rateLimitPerSecond);
  }

  async initialize(): Promise<void> {
    // Dukascopy doesn't require auth - just verify connectivity
    try {
      const testUrl = `${this.config.baseUrl}/EURUSD/2024/00/01/00h_ticks.bi5`;
      const response = await fetch(testUrl, { method: 'HEAD', signal: AbortSignal.timeout(5000) });
      this.status = response.ok || response.status === 404 ? 'CONNECTED' : 'ERROR';
    } catch {
      // Offline mode - still usable with cache
      this.status = 'CONNECTED';
    }
  }

  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate: Date): Promise<Candle[]> {
    const allCandles: Candle[] = [];
    const chunkMs = this.getChunkDuration(timeframe);
    let currentStart = new Date(startDate);

    while (currentStart < endDate) {
      const chunkEnd = new Date(Math.min(currentStart.getTime() + chunkMs, endDate.getTime()));

      try {
        await this.rateLimiter.waitForSlot();
        const url = this.buildUrl(symbol, timeframe, currentStart);
        const buffer = await this.fetchBinary(url);
        const candles = this.parseCandleBuffer(buffer, currentStart.getTime(), timeframe);
        allCandles.push(...candles);
      } catch (err) {
        console.warn(`[Dukascopy] Chunk failed: ${currentStart.toISOString()}`);
      }

      currentStart = chunkEnd;
    }

    // Sort and deduplicate - ensures no future data leaks in
    return this.cleanCandles(allCandles, startDate.getTime(), endDate.getTime());
  }

  async getTicks(symbol: string, startDate: Date, endDate: Date): Promise<Tick[]> {
    const allTicks: Tick[] = [];
    let currentHour = new Date(startDate);
    currentHour.setUTCMinutes(0, 0, 0);

    while (currentHour < endDate) {
      try {
        await this.rateLimiter.waitForSlot();
        const url = this.buildTickUrl(symbol, currentHour);
        const buffer = await this.fetchBinary(url);
        const ticks = this.parseTickBuffer(buffer, currentHour.getTime());
        allTicks.push(...ticks);
      } catch {
        // Skip failed hours
      }
      currentHour = new Date(currentHour.getTime() + 3600000);
    }

    return allTicks.filter(t => t.timestamp >= startDate.getTime() && t.timestamp <= endDate.getTime());
  }

  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    // Dukascopy doesn't have real-time - return latest from historical
    const end = new Date();
    const start = new Date(end.getTime() - 3600000);
    const candles = await this.getCandles(symbol, 'M1', start, end);
    const last = candles[candles.length - 1];
    if (!last) throw new Error('No data available');
    return { bid: last.close, ask: last.close + 0.00002, timestamp: last.timestamp };
  }

  subscribeRealtime(): () => void {
    console.warn('[Dukascopy] Real-time not supported - use another provider');
    return () => {};
  }

  async getAvailableSymbols(): Promise<string[]> {
    return [
      'EURUSD', 'GBPUSD', 'USDJPY', 'USDCHF', 'AUDUSD', 'NZDUSD', 'USDCAD',
      'EURGBP', 'EURJPY', 'GBPJPY', 'AUDJPY', 'EURAUD', 'EURCHF', 'GBPCHF',
      'XAUUSD', 'XAGUSD', 'WTIUSD', 'BRENTUSD',
      'US30', 'US500', 'NAS100', 'DE30', 'UK100', 'JP225',
      'BTCUSD', 'ETHUSD',
    ];
  }

  async isSymbolSupported(symbol: string): Promise<boolean> {
    const symbols = await this.getAvailableSymbols();
    return symbols.includes(symbol.toUpperCase());
  }

  disconnect(): void {
    this.status = 'DISCONNECTED';
  }


  // --- Private helpers ---

  private buildUrl(symbol: string, timeframe: Timeframe, date: Date): string {
    const y = date.getUTCFullYear();
    const m = String(date.getUTCMonth()).padStart(2, '0'); // 0-indexed
    const d = String(date.getUTCDate()).padStart(2, '0');
    const h = String(date.getUTCHours()).padStart(2, '0');

    const tfFile: Record<string, string> = {
      'M1': 'BID_candles_min_1', 'M5': 'BID_candles_min_5',
      'M15': 'BID_candles_min_15', 'M30': 'BID_candles_min_30',
      'H1': 'BID_candles_hour_1', 'H4': 'BID_candles_hour_4',
      'D1': 'BID_candles_day_1', 'W1': 'BID_candles_week_1', 'MN': 'BID_candles_month_1',
    };
    const file = tfFile[timeframe] || 'BID_candles_min_1';
    return `${this.config.baseUrl}/${symbol}/${y}/${m}/${d}/${h}h_${file}.bi5`;
  }

  private buildTickUrl(symbol: string, date: Date): string {
    const y = date.getUTCFullYear();
    const m = String(date.getUTCMonth()).padStart(2, '0');
    const d = String(date.getUTCDate()).padStart(2, '0');
    const h = String(date.getUTCHours()).padStart(2, '0');
    return `${this.config.baseUrl}/${symbol}/${y}/${m}/${d}/${h}h_ticks.bi5`;
  }

  private async fetchBinary(url: string): Promise<ArrayBuffer> {
    let lastErr: Error | null = null;
    for (let i = 0; i < this.config.retryAttempts; i++) {
      try {
        const res = await fetch(url, { signal: AbortSignal.timeout(this.config.timeout) });
        if (res.status === 404) return new ArrayBuffer(0);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return await res.arrayBuffer();
      } catch (err) {
        lastErr = err as Error;
        if (i < this.config.retryAttempts - 1) {
          await new Promise(r => setTimeout(r, this.config.retryDelay * (i + 1)));
        }
      }
    }
    throw lastErr || new Error('Fetch failed');
  }

  private parseCandleBuffer(buffer: ArrayBuffer, baseTimestamp: number, timeframe: Timeframe): Candle[] {
    if (buffer.byteLength === 0) return [];
    const view = new DataView(buffer);
    const recordSize = 24; // time(4)+open(4)+close(4)+low(4)+high(4)+vol(4)
    const count = Math.floor(buffer.byteLength / recordSize);
    const candles: Candle[] = [];

    for (let i = 0; i < count; i++) {
      const off = i * recordSize;
      const timeDelta = view.getUint32(off, true);
      const open = view.getUint32(off + 4, true) / 100000;
      const close = view.getUint32(off + 8, true) / 100000;
      const low = view.getUint32(off + 12, true) / 100000;
      const high = view.getUint32(off + 16, true) / 100000;
      const volume = view.getFloat32(off + 20, true);

      candles.push({
        timestamp: baseTimestamp + timeDelta * 1000,
        open, high, low, close, volume,
      });
    }
    return candles;
  }

  private parseTickBuffer(buffer: ArrayBuffer, baseTimestamp: number): Tick[] {
    if (buffer.byteLength === 0) return [];
    const view = new DataView(buffer);
    const recordSize = 20;
    const count = Math.floor(buffer.byteLength / recordSize);
    const ticks: Tick[] = [];

    for (let i = 0; i < count; i++) {
      const off = i * recordSize;
      const timeDelta = view.getUint32(off, true);
      const ask = view.getUint32(off + 4, true) / 100000;
      const bid = view.getUint32(off + 8, true) / 100000;
      const askVol = view.getFloat32(off + 12, true);
      const bidVol = view.getFloat32(off + 16, true);

      ticks.push({ timestamp: baseTimestamp + timeDelta, ask, bid, askVolume: askVol, bidVolume: bidVol });
    }
    return ticks;
  }

  private getChunkDuration(tf: Timeframe): number {
    const map: Record<string, number> = {
      'TICK': 3600000, 'M1': 3600000, 'M5': 86400000, 'M15': 86400000,
      'M30': 86400000, 'H1': 604800000, 'H4': 604800000,
      'D1': 2592000000, 'W1': 2592000000, 'MN': 31536000000,
    };
    return map[tf] || 86400000;
  }

  private cleanCandles(candles: Candle[], startMs: number, endMs: number): Candle[] {
    const seen = new Set<number>();
    return candles
      .filter(c => c.timestamp >= startMs && c.timestamp <= endMs)
      .sort((a, b) => a.timestamp - b.timestamp)
      .filter(c => { if (seen.has(c.timestamp)) return false; seen.add(c.timestamp); return true; });
  }
}
