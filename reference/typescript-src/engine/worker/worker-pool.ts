// ============================================================================
// WORKER POOL — Main-thread interface to the analysis Web Worker(s)
// Manages request/response correlation, timeouts, and data transfer.
//
// Usage:
//   const pool = new WorkerPool();
//   const result = await pool.request('ANALYZE_INDICATORS', { candles });
//
// Data transfer: Candle arrays are converted to transferable ArrayBuffers
// (zero-copy) so the main thread doesn't stall on serialization.
// ============================================================================

import type { Candle, Timeframe, Bias } from '../../core/types';
import type { WorkerRequest, WorkerRequestType, WorkerResponse } from './analysis-worker';
import { candlesToBuffer } from './analysis-worker';

export interface WorkerPoolConfig {
  /** Number of worker threads (default: navigator.hardwareConcurrency - 1) */
  poolSize: number;
  /** Timeout per request (ms) */
  timeoutMs: number;
  /** Worker script URL (Vite will bundle this) */
  workerUrl: string;
}

const DEFAULT_CONFIG: WorkerPoolConfig = {
  poolSize: Math.max(1, (typeof navigator !== 'undefined' ? navigator.hardwareConcurrency || 2 : 2) - 1),
  timeoutMs: 10000,
  workerUrl: new URL('./analysis-worker.ts', import.meta.url).href,
};

interface PendingRequest {
  resolve: (response: WorkerResponse) => void;
  reject: (error: Error) => void;
  timer: number;
}

let requestIdCounter = 0;

export class WorkerPool {
  private config: WorkerPoolConfig;
  private workers: Worker[] = [];
  private pending: Map<string, PendingRequest> = new Map();
  private roundRobin = 0;
  private initialized = false;

  constructor(config: Partial<WorkerPoolConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  /**
   * Initialize the worker pool (lazy — call before first request)
   */
  initialize(): void {
    if (this.initialized) return;

    for (let i = 0; i < this.config.poolSize; i++) {
      try {
        const worker = new Worker(this.config.workerUrl, { type: 'module' });
        worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
          this.handleResponse(event.data);
        };
        worker.onerror = (err) => {
          console.error(`[WorkerPool] Worker ${i} error:`, err);
        };
        this.workers.push(worker);
      } catch (err) {
        console.warn(`[WorkerPool] Failed to create worker ${i}:`, err);
      }
    }

    this.initialized = true;
    console.log(`[WorkerPool] Initialized with ${this.workers.length} worker(s)`);
  }

  // =========================================================================
  // PUBLIC API — Request/Response
  // =========================================================================

  /**
   * Send an analysis request to the worker pool.
   * Returns a promise that resolves with the worker's result.
   *
   * Candle data is transferred as an ArrayBuffer (zero-copy).
   */
  async request(
    type: WorkerRequestType,
    params: {
      candles?: Candle[];
      symbol?: string;
      timeframe?: Timeframe;
      direction?: 'BULLISH' | 'BEARISH';
      htfBias?: Bias;
      extra?: Record<string, unknown>;
    } = {}
  ): Promise<WorkerResponse> {
    if (!this.initialized) this.initialize();
    if (this.workers.length === 0) {
      throw new Error('[WorkerPool] No workers available');
    }

    const id = `req_${++requestIdCounter}_${Date.now()}`;

    // Convert candles to transferable buffer
    let candleBuffer: ArrayBuffer | undefined;
    let candleCount = 0;
    const transferList: Transferable[] = [];

    if (params.candles && params.candles.length > 0) {
      candleBuffer = candlesToBuffer(params.candles);
      candleCount = params.candles.length;
      transferList.push(candleBuffer);
    }

    const message: WorkerRequest = {
      id,
      type,
      payload: {
        candleBuffer,
        candleCount,
        symbol: params.symbol,
        timeframe: params.timeframe,
        params: {
          direction: params.direction,
          htfBias: params.htfBias,
          ...params.extra,
        },
      },
    };

    return new Promise<WorkerResponse>((resolve, reject) => {
      // Timeout handling
      const timer = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`[WorkerPool] Request ${type} timed out after ${this.config.timeoutMs}ms`));
      }, this.config.timeoutMs);

      this.pending.set(id, { resolve, reject, timer });

      // Round-robin worker selection
      const workerIndex = this.roundRobin % this.workers.length;
      this.roundRobin++;

      // Post with transferable (zero-copy)
      this.workers[workerIndex].postMessage(message, transferList);
    });
  }

  // =========================================================================
  // CONVENIENCE METHODS
  // =========================================================================

  /** Run indicator calculations off-thread */
  async analyzeIndicators(candles: Candle[]) {
    return this.request('ANALYZE_INDICATORS', { candles });
  }

  /** Run market structure analysis off-thread */
  async analyzeStructure(candles: Candle[], symbol?: string, timeframe?: Timeframe) {
    return this.request('ANALYZE_STRUCTURE', { candles, symbol, timeframe });
  }

  /** Run pattern detection off-thread */
  async detectPatterns(candles: Candle[]) {
    return this.request('DETECT_PATTERNS', { candles });
  }

  /** Run full 10-agent orchestration off-thread */
  async runAgents(candles: Candle[], symbol: string, timeframe: Timeframe) {
    return this.request('RUN_AGENTS', { candles, symbol, timeframe });
  }

  /** Calculate probability score off-thread */
  async calculateProbability(candles: Candle[], direction: 'BULLISH' | 'BEARISH', htfBias: Bias) {
    return this.request('CALCULATE_PROBABILITY', { candles, direction, htfBias });
  }

  // =========================================================================
  // RESPONSE HANDLING
  // =========================================================================

  private handleResponse(response: WorkerResponse): void {
    const pending = this.pending.get(response.id);
    if (!pending) return; // Timed out already or duplicate

    clearTimeout(pending.timer);
    this.pending.delete(response.id);

    if (response.type === 'ERROR') {
      pending.reject(new Error(`Worker error: ${response.payload.error}`));
    } else {
      pending.resolve(response);
    }
  }

  // =========================================================================
  // LIFECYCLE
  // =========================================================================

  /** Get pool status */
  getStatus(): { workers: number; pending: number; initialized: boolean } {
    return {
      workers: this.workers.length,
      pending: this.pending.size,
      initialized: this.initialized,
    };
  }

  /** Terminate all workers */
  destroy(): void {
    for (const worker of this.workers) {
      worker.terminate();
    }
    this.workers = [];
    // Reject all pending
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error('Worker pool destroyed'));
    }
    this.pending.clear();
    this.initialized = false;
  }
}

// ============================================================================
// SINGLETON
// ============================================================================
export const workerPool = new WorkerPool();
