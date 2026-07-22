// ============================================================================
// DATA ENGINE - Unified orchestrator for all data providers
// Failover | Priority routing | Local caching | Provider registry
// ============================================================================

import { Candle, Tick, Timeframe, CachedData } from '../../core/types';
import { TradingEventBus } from '../../core/event-bus';
import { DataProviderInterface, ProviderName, ProviderStatus } from './provider-interface';
import { DukascopyProvider } from './providers/dukascopy';
import { BinanceProvider } from './providers/binance';
import { OandaProvider } from './providers/oanda';
import { InteractiveBrokersProvider } from './providers/interactive-brokers';
import { PolygonProvider } from './providers/polygon';
import { TwelveDataProvider } from './providers/twelve-data';
import { AlphaVantageProvider } from './providers/alpha-vantage';
import { WebSocketFeed } from './websocket-feed';

export interface DataEngineConfig {
  primaryProvider: ProviderName;
  enableFailover: boolean;
  cacheSizeMB: number;
  cacheExpiryMs: number;
  autoSyncEnabled: boolean;
  autoSyncIntervalMs: number;
  providers: Partial<Record<ProviderName, { enabled: boolean; apiKey?: string; apiSecret?: string; accountId?: string }>>;
}

const DEFAULT_ENGINE_CONFIG: DataEngineConfig = {
  primaryProvider: 'DUKASCOPY',
  enableFailover: true,
  cacheSizeMB: 500,
  cacheExpiryMs: 300000, // 5 min cache for real-time
  autoSyncEnabled: true,
  autoSyncIntervalMs: 60000,
  providers: {
    DUKASCOPY: { enabled: true },
    BINANCE: { enabled: true },
    OANDA: { enabled: false },
    INTERACTIVE_BROKERS: { enabled: false },
    POLYGON: { enabled: false },
    TWELVE_DATA: { enabled: false },
    ALPHA_VANTAGE: { enabled: false },
  },
};


interface CacheEntry {
  data: Candle[];
  fetchedAt: number;
  provider: ProviderName;
}

export class DataEngine {
  private config: DataEngineConfig;
  private eventBus?: TradingEventBus;
  private providers: Map<ProviderName, DataProviderInterface> = new Map();
  private cache: Map<string, CacheEntry> = new Map();
  private wsFeed: WebSocketFeed;
  private syncTimers: Map<string, number> = new Map();
  private totalCacheBytes: number = 0;

  constructor(config: Partial<DataEngineConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_ENGINE_CONFIG, ...config };
    this.eventBus = eventBus;
    this.wsFeed = new WebSocketFeed({}, eventBus);
    this.registerProviders();
  }

  /**
   * Register and initialize all configured providers
   */
  private registerProviders(): void {
    const providerConfigs = this.config.providers;

    if (providerConfigs.DUKASCOPY?.enabled) {
      this.providers.set('DUKASCOPY', new DukascopyProvider());
    }
    if (providerConfigs.BINANCE?.enabled) {
      this.providers.set('BINANCE', new BinanceProvider({
        apiKey: providerConfigs.BINANCE.apiKey,
        apiSecret: providerConfigs.BINANCE.apiSecret,
      }));
    }
    if (providerConfigs.OANDA?.enabled) {
      this.providers.set('OANDA', new OandaProvider({
        apiKey: providerConfigs.OANDA.apiKey,
        accountId: providerConfigs.OANDA.accountId,
      }));
    }
    if (providerConfigs.INTERACTIVE_BROKERS?.enabled) {
      this.providers.set('INTERACTIVE_BROKERS', new InteractiveBrokersProvider());
    }
    if (providerConfigs.POLYGON?.enabled) {
      this.providers.set('POLYGON', new PolygonProvider({ apiKey: providerConfigs.POLYGON.apiKey }));
    }
    if (providerConfigs.TWELVE_DATA?.enabled) {
      this.providers.set('TWELVE_DATA', new TwelveDataProvider({ apiKey: providerConfigs.TWELVE_DATA.apiKey }));
    }
    if (providerConfigs.ALPHA_VANTAGE?.enabled) {
      this.providers.set('ALPHA_VANTAGE', new AlphaVantageProvider({ apiKey: providerConfigs.ALPHA_VANTAGE.apiKey }));
    }
  }

  /**
   * Initialize all enabled providers
   */
  async initialize(): Promise<void> {
    const initPromises = Array.from(this.providers.values()).map(p =>
      p.initialize().catch(err => console.warn(`[DataEngine] ${p.name} init failed:`, err))
    );
    await Promise.allSettled(initPromises);

    const connected = Array.from(this.providers.values()).filter(p => p.status === 'CONNECTED');
    console.log(`[DataEngine] Initialized: ${connected.length}/${this.providers.size} providers connected`);
    console.log(`[DataEngine] Active: ${connected.map(p => p.name).join(', ')}`);
  }

  /**
   * Get candles with failover support
   * Tries primary provider first, falls back to others if it fails
   * CRITICAL: Only returns confirmed historical data - no future candles
   */
  async getCandles(symbol: string, timeframe: Timeframe, startDate: Date, endDate?: Date): Promise<Candle[]> {
    const end = endDate || new Date();
    const cacheKey = `${symbol}_${timeframe}_${startDate.getTime()}_${end.getTime()}`;

    // Check cache
    const cached = this.cache.get(cacheKey);
    if (cached && Date.now() - cached.fetchedAt < this.config.cacheExpiryMs) {
      return cached.data;
    }

    // Get ordered provider list (primary first, then by priority)
    const orderedProviders = this.getOrderedProviders(symbol);

    for (const provider of orderedProviders) {
      if (provider.status !== 'CONNECTED') continue;

      try {
        const candles = await provider.getCandles(symbol, timeframe, startDate, end);
        if (candles.length > 0) {
          // CRITICAL: Filter out any future data (no look-ahead bias)
          const now = Date.now();
          const validCandles = candles.filter(c => c.timestamp <= now);

          // Cache the result
          this.setCacheEntry(cacheKey, validCandles, provider.name);
          return validCandles;
        }
      } catch (err) {
        console.warn(`[DataEngine] ${provider.name} failed for ${symbol} ${timeframe}:`, err);
        if (!this.config.enableFailover) throw err;
        // Continue to next provider (failover)
      }
    }

    // All providers failed
    console.error(`[DataEngine] All providers failed for ${symbol} ${timeframe}`);
    return cached?.data || []; // Return stale cache if available
  }


  /**
   * Get tick data with failover
   */
  async getTicks(symbol: string, startDate: Date, endDate: Date): Promise<Tick[]> {
    const providers = this.getOrderedProviders(symbol).filter(p => p.capabilities.supportsTicks);

    for (const provider of providers) {
      if (provider.status !== 'CONNECTED') continue;
      try {
        const ticks = await provider.getTicks(symbol, startDate, endDate);
        if (ticks.length > 0) return ticks.filter(t => t.timestamp <= Date.now());
      } catch { continue; }
    }
    return [];
  }

  /**
   * Subscribe to real-time data via WebSocket
   * Automatically selects the best provider with WebSocket support
   */
  subscribeRealtime(
    symbol: string,
    timeframe: Timeframe,
    onCandle: (candle: Candle) => void,
    onTick?: (tick: Tick) => void
  ): string {
    // Find best provider with WebSocket support for this symbol
    const realtimeProviders = this.getOrderedProviders(symbol)
      .filter(p => p.capabilities.websocketSupport && p.status === 'CONNECTED');

    if (realtimeProviders.length === 0) {
      console.warn(`[DataEngine] No real-time provider available for ${symbol}`);
      return '';
    }

    const provider = realtimeProviders[0];
    return this.wsFeed.subscribe(symbol, timeframe, provider, onCandle, onTick);
  }

  /**
   * Unsubscribe from real-time data
   */
  unsubscribeRealtime(subscriptionId: string): void {
    this.wsFeed.unsubscribe(subscriptionId);
  }

  /**
   * Start auto-sync for a symbol/timeframe pair
   * Periodically fetches latest data and emits new candles
   */
  startAutoSync(symbol: string, timeframe: Timeframe, onNewCandle?: (c: Candle) => void): void {
    const key = `${symbol}_${timeframe}`;
    if (this.syncTimers.has(key)) return;

    let lastTimestamp = 0;

    const sync = async () => {
      try {
        const start = lastTimestamp > 0 ? new Date(lastTimestamp) : new Date(Date.now() - 3600000);
        const candles = await this.getCandles(symbol, timeframe, start);

        for (const candle of candles) {
          if (candle.timestamp > lastTimestamp) {
            lastTimestamp = candle.timestamp;
            onNewCandle?.(candle);
          }
        }
      } catch (err) {
        console.warn(`[DataEngine] Auto-sync failed for ${key}:`, err);
      }
    };

    sync(); // Initial
    const timer = window.setInterval(sync, this.config.autoSyncIntervalMs);
    this.syncTimers.set(key, timer);
  }

  /**
   * Stop auto-sync
   */
  stopAutoSync(symbol: string, timeframe: Timeframe): void {
    const key = `${symbol}_${timeframe}`;
    const timer = this.syncTimers.get(key);
    if (timer) { clearInterval(timer); this.syncTimers.delete(key); }
  }

  /**
   * Stop all auto-syncs
   */
  stopAllSync(): void {
    for (const timer of this.syncTimers.values()) clearInterval(timer);
    this.syncTimers.clear();
  }

  /**
   * Get latest price from the fastest provider
   */
  async getLatestPrice(symbol: string): Promise<{ bid: number; ask: number; timestamp: number }> {
    const providers = this.getOrderedProviders(symbol).filter(p => p.status === 'CONNECTED');
    for (const provider of providers) {
      try {
        return await provider.getLatestPrice(symbol);
      } catch { continue; }
    }
    throw new Error(`No provider available for ${symbol}`);
  }

  /**
   * Get providers ordered by priority for a given symbol
   */
  private getOrderedProviders(symbol: string): DataProviderInterface[] {
    const all = Array.from(this.providers.values());

    // Primary first
    const primary = all.find(p => p.name === this.config.primaryProvider);
    const rest = all.filter(p => p.name !== this.config.primaryProvider)
      .sort((a, b) => a.config.priority - b.config.priority);

    return primary ? [primary, ...rest] : rest;
  }

  /**
   * Set cache entry with size management
   */
  private setCacheEntry(key: string, candles: Candle[], provider: ProviderName): void {
    const entrySize = candles.length * 48; // Approx bytes per candle

    // Evict if cache full
    const maxBytes = this.config.cacheSizeMB * 1024 * 1024;
    while (this.totalCacheBytes + entrySize > maxBytes && this.cache.size > 0) {
      const oldest = this.getOldestCacheEntry();
      if (oldest) { this.totalCacheBytes -= (this.cache.get(oldest)?.data.length || 0) * 48; this.cache.delete(oldest); }
      else break;
    }

    this.cache.set(key, { data: candles, fetchedAt: Date.now(), provider });
    this.totalCacheBytes += entrySize;
  }

  private getOldestCacheEntry(): string | null {
    let oldest: string | null = null;
    let oldestTime = Infinity;
    for (const [key, entry] of this.cache) {
      if (entry.fetchedAt < oldestTime) { oldestTime = entry.fetchedAt; oldest = key; }
    }
    return oldest;
  }

  // --- Public getters ---

  getProviderStatus(): { name: ProviderName; status: ProviderStatus; priority: number }[] {
    return Array.from(this.providers.values()).map(p => ({
      name: p.name, status: p.status, priority: p.config.priority,
    }));
  }

  getWebSocketStatus() { return this.wsFeed.getStatus(); }
  getActiveSubscriptions() { return this.wsFeed.getActiveSubscriptions(); }
  getCacheStats() { return { entries: this.cache.size, totalBytes: this.totalCacheBytes, maxBytes: this.config.cacheSizeMB * 1024 * 1024 }; }
  clearCache() { this.cache.clear(); this.totalCacheBytes = 0; }

  /**
   * Add a provider at runtime (for dynamic configuration)
   */
  addProvider(provider: DataProviderInterface): void {
    this.providers.set(provider.name, provider);
    provider.initialize().catch(err => console.warn(`[DataEngine] Late-add ${provider.name} failed:`, err));
  }

  /**
   * Remove a provider
   */
  removeProvider(name: ProviderName): void {
    const provider = this.providers.get(name);
    if (provider) { provider.disconnect(); this.providers.delete(name); }
  }

  /**
   * Destroy engine and all connections
   */
  destroy(): void {
    this.stopAllSync();
    this.wsFeed.destroy();
    for (const provider of this.providers.values()) provider.disconnect();
    this.providers.clear();
    this.cache.clear();
  }
}
