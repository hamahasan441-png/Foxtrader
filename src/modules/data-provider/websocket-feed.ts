// ============================================================================
// WEBSOCKET LIVE DATA FEED
// Unified WebSocket manager for all providers
// Auto-reconnect | Heartbeat | Message buffering | Multi-stream
// ============================================================================

import { Candle, Tick, Timeframe } from '../../core/types';
import { TradingEventBus } from '../../core/event-bus';
import { DataProviderInterface, ProviderName } from './provider-interface';

export interface WebSocketFeedConfig {
  reconnectAttempts: number;
  reconnectDelay: number; // ms - initial, uses exponential backoff
  heartbeatInterval: number; // ms
  bufferSize: number; // Max buffered messages during reconnect
  messageRateLimit: number; // Max messages per second to process
}

const DEFAULT_WS_CONFIG: WebSocketFeedConfig = {
  reconnectAttempts: 10,
  reconnectDelay: 1000,
  heartbeatInterval: 30000,
  bufferSize: 1000,
  messageRateLimit: 100,
};

interface StreamSubscription {
  id: string;
  symbol: string;
  timeframe: Timeframe;
  provider: DataProviderInterface;
  onCandle: (candle: Candle) => void;
  onTick?: (tick: Tick) => void;
  unsubscribe: () => void;
  active: boolean;
}


export class WebSocketFeed {
  private config: WebSocketFeedConfig;
  private eventBus?: TradingEventBus;
  private subscriptions: Map<string, StreamSubscription> = new Map();
  private messageBuffer: { timestamp: number; data: any }[] = [];
  private heartbeatTimers: Map<string, number> = new Map();
  private reconnectCounters: Map<string, number> = new Map();
  private isActive: boolean = false;

  // Candle aggregation state for tick-to-candle conversion
  private candleAggregators: Map<string, {
    current: Candle;
    timeframe: Timeframe;
    periodStart: number;
    periodMs: number;
  }> = new Map();

  constructor(config: Partial<WebSocketFeedConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_WS_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Subscribe to real-time data for a symbol via the best available provider
   */
  subscribe(
    symbol: string,
    timeframe: Timeframe,
    provider: DataProviderInterface,
    onCandle: (candle: Candle) => void,
    onTick?: (tick: Tick) => void
  ): string {
    const subId = `${provider.name}_${symbol}_${timeframe}_${Date.now()}`;

    // Create wrapped handlers with reconnection logic
    const wrappedOnCandle = (candle: Candle) => {
      // Validate candle timestamp - reject future data (no look-ahead)
      if (candle.timestamp > Date.now() + 60000) {
        console.warn(`[WSFeed] Rejected future candle: ${new Date(candle.timestamp).toISOString()}`);
        return;
      }
      onCandle(candle);
      this.eventBus?.emit({ type: 'NEW_CANDLE', data: { timeframe, candle } });
    };

    const wrappedOnTick = onTick ? (tick: Tick) => {
      if (tick.timestamp > Date.now() + 5000) return; // Reject future ticks
      onTick(tick);
      this.eventBus?.emit({ type: 'TICK', data: tick });
      // Aggregate ticks into candles
      this.aggregateTick(subId, tick, timeframe, wrappedOnCandle);
    } : undefined;

    // Subscribe via the provider's native WebSocket
    const unsubscribe = provider.subscribeRealtime(symbol, timeframe, wrappedOnCandle, wrappedOnTick);

    const subscription: StreamSubscription = {
      id: subId,
      symbol,
      timeframe,
      provider,
      onCandle: wrappedOnCandle,
      onTick: wrappedOnTick,
      unsubscribe,
      active: true,
    };

    this.subscriptions.set(subId, subscription);
    this.startHeartbeat(subId, provider);
    this.isActive = true;

    console.log(`[WSFeed] Subscribed: ${symbol} ${timeframe} via ${provider.name}`);
    return subId;
  }

  /**
   * Unsubscribe from a specific stream
   */
  unsubscribe(subId: string): void {
    const sub = this.subscriptions.get(subId);
    if (sub) {
      sub.unsubscribe();
      sub.active = false;
      this.subscriptions.delete(subId);
      this.stopHeartbeat(subId);
      this.candleAggregators.delete(subId);
    }
  }

  /**
   * Unsubscribe all streams
   */
  unsubscribeAll(): void {
    for (const [id] of this.subscriptions) {
      this.unsubscribe(id);
    }
    this.isActive = false;
  }

  /**
   * Reconnect a failed subscription with exponential backoff
   */
  private async reconnect(subId: string): Promise<void> {
    const sub = this.subscriptions.get(subId);
    if (!sub) return;

    const counter = (this.reconnectCounters.get(subId) || 0) + 1;
    this.reconnectCounters.set(subId, counter);

    if (counter > this.config.reconnectAttempts) {
      console.error(`[WSFeed] Max reconnect attempts reached for ${sub.symbol}`);
      sub.active = false;
      return;
    }

    const delay = this.config.reconnectDelay * Math.pow(2, counter - 1);
    console.log(`[WSFeed] Reconnecting ${sub.symbol} in ${delay}ms (attempt ${counter}/${this.config.reconnectAttempts})`);

    await new Promise(r => setTimeout(r, delay));

    // Re-subscribe
    const newUnsub = sub.provider.subscribeRealtime(sub.symbol, sub.timeframe, sub.onCandle, sub.onTick);
    sub.unsubscribe = newUnsub;
    sub.active = true;
    this.reconnectCounters.set(subId, 0);
  }


  /**
   * Aggregate incoming ticks into candles based on timeframe
   */
  private aggregateTick(subId: string, tick: Tick, timeframe: Timeframe, onCandle: (c: Candle) => void): void {
    const periodMs = this.timeframeToMs(timeframe);
    if (periodMs === 0) return; // TICK timeframe doesn't aggregate

    let agg = this.candleAggregators.get(subId);
    const periodStart = Math.floor(tick.timestamp / periodMs) * periodMs;
    const midPrice = (tick.bid + tick.ask) / 2;

    if (!agg || agg.periodStart !== periodStart) {
      // New period - emit previous candle if exists
      if (agg && agg.current.timestamp > 0) {
        onCandle({ ...agg.current });
      }

      // Start new candle
      agg = {
        current: {
          timestamp: periodStart,
          open: midPrice,
          high: midPrice,
          low: midPrice,
          close: midPrice,
          volume: tick.bidVolume || 0,
        },
        timeframe,
        periodStart,
        periodMs,
      };
      this.candleAggregators.set(subId, agg);
    } else {
      // Update current candle
      agg.current.high = Math.max(agg.current.high, midPrice);
      agg.current.low = Math.min(agg.current.low, midPrice);
      agg.current.close = midPrice;
      agg.current.volume += tick.bidVolume || 0;
    }
  }

  /**
   * Start heartbeat monitoring for a subscription
   */
  private startHeartbeat(subId: string, provider: DataProviderInterface): void {
    const timer = window.setInterval(() => {
      const sub = this.subscriptions.get(subId);
      if (!sub || !sub.active) {
        this.stopHeartbeat(subId);
        return;
      }

      // Check if provider is still connected
      if (provider.status === 'DISCONNECTED' || provider.status === 'ERROR') {
        console.warn(`[WSFeed] Provider ${provider.name} disconnected, attempting reconnect...`);
        this.reconnect(subId);
      }
    }, this.config.heartbeatInterval);

    this.heartbeatTimers.set(subId, timer);
  }

  /**
   * Stop heartbeat for a subscription
   */
  private stopHeartbeat(subId: string): void {
    const timer = this.heartbeatTimers.get(subId);
    if (timer) {
      clearInterval(timer);
      this.heartbeatTimers.delete(subId);
    }
  }

  /**
   * Get all active subscriptions
   */
  getActiveSubscriptions(): { id: string; symbol: string; timeframe: Timeframe; provider: ProviderName }[] {
    return Array.from(this.subscriptions.values())
      .filter(s => s.active)
      .map(s => ({ id: s.id, symbol: s.symbol, timeframe: s.timeframe, provider: s.provider.name }));
  }

  /**
   * Check if feed is active
   */
  getStatus(): { active: boolean; subscriptionCount: number; bufferSize: number } {
    return {
      active: this.isActive,
      subscriptionCount: this.subscriptions.size,
      bufferSize: this.messageBuffer.length,
    };
  }

  private timeframeToMs(tf: Timeframe): number {
    const map: Record<string, number> = {
      'TICK': 0, 'M1': 60000, 'M3': 180000, 'M5': 300000,
      'M15': 900000, 'M30': 1800000, 'H1': 3600000, 'H4': 14400000,
      'D1': 86400000, 'W1': 604800000, 'MN': 2592000000,
    };
    return map[tf] || 60000;
  }

  /**
   * Destroy the feed
   */
  destroy(): void {
    this.unsubscribeAll();
    for (const timer of this.heartbeatTimers.values()) clearInterval(timer);
    this.heartbeatTimers.clear();
    this.candleAggregators.clear();
    this.messageBuffer = [];
  }
}
