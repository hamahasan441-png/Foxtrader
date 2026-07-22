// ============================================================================
// EVENT BUS - High-performance pub/sub for real-time trading events
// ============================================================================

import { EventBus, PlatformEvent } from './types';

export class TradingEventBus implements EventBus {
  private listeners: Map<string, Set<(data: any) => void>> = new Map();
  private eventQueue: PlatformEvent[] = [];
  private processing = false;
  private batchSize = 100;

  emit(event: PlatformEvent): void {
    this.eventQueue.push(event);
    if (!this.processing) {
      this.processQueue();
    }
  }

  on(type: PlatformEvent['type'], handler: (data: any) => void): () => void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)!.add(handler);
    return () => this.off(type, handler);
  }

  off(type: PlatformEvent['type'], handler: (data: any) => void): void {
    this.listeners.get(type)?.delete(handler);
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
