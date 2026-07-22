// ============================================================================
// EVENT BUS - High-performance type-safe pub/sub for real-time trading events
// ============================================================================

import { EventBus, EventType, EventMap, PlatformEvent } from './types';

export class TradingEventBus implements EventBus {
  private listeners: Map<string, Set<(data: unknown) => void>> = new Map();
  private eventQueue: PlatformEvent[] = [];
  private processing = false;
  private batchSize = 100;

  emit<K extends EventType>(event: { type: K; data: EventMap[K] }): void {
    this.eventQueue.push(event as PlatformEvent);
    if (!this.processing) {
      this.processQueue();
    }
  }

  on<K extends EventType>(type: K, handler: (data: EventMap[K]) => void): () => void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)!.add(handler as (data: unknown) => void);
    return () => this.off(type, handler);
  }

  off<K extends EventType>(type: K, handler: (data: EventMap[K]) => void): void {
    this.listeners.get(type)?.delete(handler as (data: unknown) => void);
  }

  private processQueue(): void {
    this.processing = true;
    let processed = 0;

    while (this.eventQueue.length > 0 && processed < this.batchSize) {
      const event = this.eventQueue.shift()!;
      const handlers = this.listeners.get(event.type);
      if (handlers) {
        for (const handler of handlers) {
          try {
            handler(event.data);
          } catch (err) {
            console.error(`[EventBus] Error in handler for ${event.type}:`, err);
          }
        }
      }
      processed++;
    }

    if (this.eventQueue.length > 0) {
      requestAnimationFrame(() => this.processQueue());
    } else {
      this.processing = false;
    }
  }

  clear(): void {
    this.listeners.clear();
    this.eventQueue = [];
    this.processing = false;
  }

  getListenerCount(type?: PlatformEvent['type']): number {
    if (type) {
      return this.listeners.get(type)?.size ?? 0;
    }
    let total = 0;
    for (const set of this.listeners.values()) {
      total += set.size;
    }
    return total;
  }
}

// Singleton instance
export const eventBus = new TradingEventBus();
