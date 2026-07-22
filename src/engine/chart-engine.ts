// ============================================================================
// CHART ENGINE — High-level platform-facing chart facade
//
// Bridges the InstitutionalTradingPlatform orchestrator (main.ts) to the
// low-level WebGL2 renderer (FoxChart). The orchestrator speaks in terms of
// candles + annotations + replay; FoxChart speaks in terms of GPU buffers and
// viewports. This facade is the single seam between them.
//
// Design:
// - FoxChart owns all GPU rendering (candles, viewport, input, inertia).
// - ChartEngine owns platform concerns: annotation storage, replay state,
//   event-bus wiring, and the stable API main.ts depends on.
// - Annotations are stored here and will be handed to the WebGL zone/line
//   renderers as those passes are wired in (shaders already exist).
// ============================================================================

import { Candle, ChartAnnotation, EventBus } from '../core/types';
import { FoxChart } from './webgl/fox-chart';

export interface ChartEngineConfig {
  /** Cap device-pixel-ratio for performance (FoxChart also caps at 2). */
  maxDevicePixelRatio?: number;
  /** Target frames per second (FoxChart targets 120, degrades to 60). */
  targetFPS?: number;
}

/**
 * Platform-facing chart facade. Safe to construct before a container exists;
 * GPU resources are created lazily in {@link initialize}.
 */
export class ChartEngine {
  private readonly config: ChartEngineConfig;
  private readonly eventBus?: EventBus;

  private chart: FoxChart | null = null;
  private candles: Candle[] = [];

  // Annotation store (rendered by WebGL overlay passes as they are wired in).
  private annotations: Map<string, ChartAnnotation> = new Map();

  // Replay state.
  private replayActive = false;
  private replayIndex = 0;
  private replaySpeed = 1;
  private replayTimer: number = 0;

  constructor(config: ChartEngineConfig = {}, eventBus?: EventBus) {
    this.config = config;
    this.eventBus = eventBus;
  }

  // =========================================================================
  // LIFECYCLE
  // =========================================================================

  /** Create the WebGL chart inside [container]. Idempotent. */
  initialize(container: HTMLElement): void {
    if (this.chart) return;
    this.chart = new FoxChart(container, {
      targetFPS: this.config.targetFPS ?? 120,
    });
    if (this.candles.length > 0) {
      this.chart.setData(this.candles);
    }
  }

  // =========================================================================
  // DATA
  // =========================================================================

  setData(candles: Candle[]): void {
    this.candles = candles;
    this.chart?.setData(candles);
  }

  addCandle(candle: Candle): void {
    // Merge-or-append by timestamp so a forming bar updates in place.
    const n = this.candles.length;
    if (n > 0 && this.candles[n - 1].timestamp === candle.timestamp) {
      this.candles[n - 1] = candle;
      this.chart?.updateLastCandle(candle);
    } else {
      this.candles.push(candle);
      this.chart?.addCandle(candle);
    }
  }

  // =========================================================================
  // ANNOTATIONS
  // =========================================================================

  addAnnotation(annotation: ChartAnnotation): void {
    this.annotations.set(annotation.id, annotation);
  }

  clearAnnotations(): void {
    this.annotations.clear();
  }

  getAnnotations(): ChartAnnotation[] {
    return [...this.annotations.values()];
  }

  // =========================================================================
  // REPLAY
  // =========================================================================

  /**
   * Begin bar-by-bar replay from [startIndex] at [speed]× real time.
   * Feeds candles into the renderer one bar at a time so the platform can
   * observe non-repainting behaviour exactly as it would live.
   */
  startReplay(startIndex: number = 0, speed: number = 1): void {
    if (this.candles.length === 0) return;
    this.stopReplay();
    this.replayActive = true;
    this.replayIndex = Math.max(0, Math.min(startIndex, this.candles.length - 1));
    this.replaySpeed = speed <= 0 ? 1 : speed;

    // Seed the chart with everything up to the replay cursor.
    this.chart?.setData(this.candles.slice(0, this.replayIndex + 1));

    const stepMs = 1000 / this.replaySpeed;
    this.replayTimer = (globalThis.setInterval as typeof setInterval)(() => {
      if (!this.replayActive || this.replayIndex >= this.candles.length - 1) {
        this.stopReplay();
        return;
      }
      this.replayIndex++;
      this.chart?.addCandle(this.candles[this.replayIndex]);
    }, stepMs) as unknown as number;
  }

  stopReplay(): void {
    if (this.replayTimer) {
      clearInterval(this.replayTimer);
      this.replayTimer = 0;
    }
    this.replayActive = false;
  }

  isReplayActive(): boolean {
    return this.replayActive;
  }

  // =========================================================================
  // METRICS / PASS-THROUGH
  // =========================================================================

  getCurrentFPS(): number {
    return this.chart?.getFPS() ?? 0;
  }

  getTotalCandles(): number {
    return this.chart?.getTotalCandles() ?? this.candles.length;
  }

  /** The underlying WebGL renderer, for advanced callers. May be null. */
  getRenderer(): FoxChart | null {
    return this.chart;
  }

  // =========================================================================
  // TEARDOWN
  // =========================================================================

  destroy(): void {
    this.stopReplay();
    this.chart?.destroy();
    this.chart = null;
    this.annotations.clear();
    this.candles = [];
    void this.eventBus; // reserved for future annotation/event wiring
  }
}
