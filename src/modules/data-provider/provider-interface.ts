// ============================================================================
// UNIFIED DATA PROVIDER INTERFACE
// All providers must implement this interface
// ============================================================================

import { Candle, Tick, Timeframe } from '../../core/types';

export type ProviderName =
  | 'DUKASCOPY'
  | 'BINANCE'
  | 'OANDA'
  | 'INTERACTIVE_BROKERS'
  | 'POLYGON'
  | 'TWELVE_DATA'
  | 'ALPHA_VANTAGE';

export type ProviderStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'RATE_LIMITED';

export interface ProviderConfig {
  name: ProviderName;
  apiKey?: string;
  apiSecret?: string;
  accountId?: string;
  baseUrl: string;
  rateLimitPerMinute: number;
  rateLimitPerSecond: number;
  timeout: number; // ms
  retryAttempts: number;
  retryDelay: number; // ms
  priority: number; // Lower = higher priority for failover
  enabled: boolean;
}

export interface ProviderCapabilities {
  supportsHistorical: boolean;
  supportsRealtime: boolean;
  supportsTicks: boolean;
  supportedTimeframes: Timeframe[];
  supportedAssetClasses: AssetClass[];
  maxHistoryDays: number;
  websocketSupport: boolean;
}

export type AssetClass = 'FOREX' | 'CRYPTO' | 'STOCKS' | 'INDICES' | 'COMMODITIES' | 'FUTURES';


export interface DataProviderInterface {
  readonly name: ProviderName;
  readonly config: ProviderConfig;
  readonly capabilities: ProviderCapabilities;
  status: ProviderStatus;

  /**
   * Initialize the provider (authenticate, verify connection)
   */
  initialize(): Promise<void>;

  /**
   * Fetch historical candle data
   * Must return data sorted by timestamp ascending
   * Must NOT include future data (no look-ahead)
   */
  getCandles(
    symbol: string,
    timeframe: Timeframe,
    startDate: Date,
    endDate: Date
  ): Promise<Candle[]>;

  /**
   * Fetch tick data (if supported)
   */
  getTicks(
    symbol: string,
    startDate: Date,
    endDate: Date
  ): Promise<Tick[]>;

  /**
   * Get the latest price/candle for a symbol
   */
  getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }>;

  /**
   * Subscribe to real-time updates via WebSocket (if supported)
   * Returns unsubscribe function
   */
  subscribeRealtime(
    symbol: string,
    timeframe: Timeframe,
    onCandle: (candle: Candle) => void,
    onTick?: (tick: Tick) => void
  ): () => void;

  /**
   * Get list of available symbols
   */
  getAvailableSymbols(): Promise<string[]>;

  /**
   * Check if a symbol is available on this provider
   */
  isSymbolSupported(symbol: string): Promise<boolean>;

  /**
   * Disconnect and cleanup
   */
  disconnect(): void;
}

/**
 * Rate limiter utility for providers
 */
export class RateLimiter {
  private requests: number[] = [];
  private readonly maxPerMinute: number;
  private readonly maxPerSecond: number;

  constructor(maxPerMinute: number, maxPerSecond: number) {
    this.maxPerMinute = maxPerMinute;
    this.maxPerSecond = maxPerSecond;
  }

  async waitForSlot(): Promise<void> {
    const now = Date.now();
    // Clean old entries
    this.requests = this.requests.filter(t => now - t < 60000);

    // Check per-minute limit
    if (this.requests.length >= this.maxPerMinute) {
      const waitTime = 60000 - (now - this.requests[0]);
      await this.delay(waitTime);
    }

    // Check per-second limit
    const recentSecond = this.requests.filter(t => now - t < 1000);
    if (recentSecond.length >= this.maxPerSecond) {
      await this.delay(1000 - (now - recentSecond[0]));
    }

    this.requests.push(Date.now());
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, Math.max(0, ms)));
  }

  get remainingRequests(): number {
    const now = Date.now();
    const recentMinute = this.requests.filter(t => now - t < 60000).length;
    return this.maxPerMinute - recentMinute;
  }
}
