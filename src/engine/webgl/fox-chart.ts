// ============================================================================
// FOX CHART ENGINE — Main orchestrator
// WebGL2 GPU rendering | Infinite zoom/pan | 120fps target
// Smooth inertia | Elastic scrolling | Viewport culling
// ============================================================================

import { Candle, Timeframe } from '../../core/types';
import { CandleRenderer, ViewportState } from './candle-renderer';

export interface FoxChartConfig {
  /** Target FPS (120 if hardware supports, min 60) */
  targetFPS: number;
  /** Background color */
  backgroundColor: [number, number, number, number]; // RGBA 0-1
  /** Min visible bars (max zoom in) */
  minVisibleBars: number;
  /** Max visible bars (max zoom out) */
  maxVisibleBars: number;
  /** Scroll inertia friction (0-1, lower = more friction) */
  inertiaFriction: number;
  /** Elastic overscroll strength */
  elasticStrength: number;
  /** Price padding (percentage of range to add as padding) */
  pricePadding: number;
  /** Auto-scale price axis to visible data */
  autoScale: boolean;
}

const DEFAULT_CONFIG: FoxChartConfig = {
  targetFPS: 120,
  backgroundColor: [0.039, 0.055, 0.090, 1.0], // #0a0e17
  minVisibleBars: 10,
  maxVisibleBars: 50000,
  inertiaFriction: 0.92,
  elasticStrength: 0.3,
  pricePadding: 0.08,
  autoScale: true,
};

export class FoxChart {
  private canvas: HTMLCanvasElement;
  private gl: WebGL2RenderingContext;
  private config: FoxChartConfig;

  // Renderers
  private candleRenderer: CandleRenderer;

  // Viewport state (the "camera")
  private viewport: ViewportState = {
    startIndex: 0,
    visibleBars: 100,
    priceLow: 0,
    priceHigh: 1,
  };

  // Interaction state
  private isDragging = false;
  private lastPointerX = 0;
  private lastPointerY = 0;
  private velocityX = 0;
  private velocityY = 0;
  private animating = false;

  // Data
  private candles: Candle[] = [];
  private rafId = 0;
  private frameCount = 0;
  private fps = 0;
  private lastFpsTime = 0;
  private needsRender = true;
  private resizeObserver: ResizeObserver | null = null;

  constructor(container: HTMLElement, config: Partial<FoxChartConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };

    // Create canvas
    this.canvas = document.createElement('canvas');
    this.canvas.style.cssText = 'width:100%;height:100%;display:block;touch-action:none;';
    container.appendChild(this.canvas);

    // Initialize WebGL2
    const gl = this.canvas.getContext('webgl2', {
      alpha: false,
      antialias: false,       // We control AA ourselves
      powerPreference: 'high-performance',
      desynchronized: true,   // Reduces latency on supported browsers
      preserveDrawingBuffer: false,
    });
    if (!gl) throw new Error('WebGL2 not supported');
    this.gl = gl;

    // Enable blending for transparent zones
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    // Create renderers
    this.candleRenderer = new CandleRenderer(gl);

    // Setup interactions
    this.setupInput();

    // Responsive resize
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();

    // Start render loop
    this.startRenderLoop();
  }

  // =========================================================================
  // DATA
  // =========================================================================

  setData(candles: Candle[]): void {
    this.candles = candles;
    this.candleRenderer.setData(candles);

    // Initial viewport: show last 100 bars
    const count = Math.min(100, candles.length);
    this.viewport.startIndex = Math.max(0, candles.length - count);
    this.viewport.visibleBars = count;
    this.autoScalePriceAxis();
    this.needsRender = true;
  }

  addCandle(candle: Candle): void {
    this.candles.push(candle);
    this.candleRenderer.appendCandle(candle);
    // Keep viewport at the right edge if user hasn't scrolled back
    const atEdge = this.viewport.startIndex + this.viewport.visibleBars >= this.candles.length - 2;
    if (atEdge) {
      this.viewport.startIndex = Math.max(0, this.candles.length - this.viewport.visibleBars);
    }
    this.autoScalePriceAxis();
    this.needsRender = true;
  }

  updateLastCandle(candle: Candle): void {
    if (this.candles.length > 0) {
      this.candles[this.candles.length - 1] = candle;
      this.candleRenderer.updateLastCandle(candle);
      this.autoScalePriceAxis();
      this.needsRender = true;
    }
  }

  // =========================================================================
  // RENDER LOOP — 120fps target with dirty-check optimization
  // =========================================================================

  private startRenderLoop(): void {
    const loop = () => {
      this.rafId = requestAnimationFrame(loop);

      // Physics: apply inertia
      if (Math.abs(this.velocityX) > 0.01 || Math.abs(this.velocityY) > 0.01) {
        this.pan(this.velocityX, this.velocityY);
        this.velocityX *= this.config.inertiaFriction;
        this.velocityY *= this.config.inertiaFriction;
        this.needsRender = true;
      } else {
        this.velocityX = 0;
        this.velocityY = 0;
      }

      // Only render if something changed
      if (this.needsRender) {
        this.render();
        this.needsRender = false;
      }

      // FPS counter
      this.frameCount++;
      const now = performance.now();
      if (now - this.lastFpsTime >= 1000) {
        this.fps = this.frameCount;
        this.frameCount = 0;
        this.lastFpsTime = now;
      }
    };
    this.rafId = requestAnimationFrame(loop);
  }

  private render(): void {
    const gl = this.gl;
    const [r, g, b, a] = this.config.backgroundColor;
    gl.clearColor(r, g, b, a);
    gl.clear(gl.COLOR_BUFFER_BIT);

    // Render candles (instanced — one draw call for all visible)
    this.candleRenderer.render(this.viewport);
  }

  // =========================================================================
  // VIEWPORT MANIPULATION (zoom, pan, auto-scale)
  // =========================================================================

  private pan(deltaX: number, _deltaY: number): void {
    // deltaX in pixels → convert to bar units
    const barsPerPixel = this.viewport.visibleBars / this.canvas.width;
    const barDelta = deltaX * barsPerPixel;
    this.viewport.startIndex -= barDelta;

    // Clamp with elastic overscroll
    const maxStart = Math.max(0, this.candles.length - this.viewport.visibleBars);
    if (this.viewport.startIndex < 0) {
      this.viewport.startIndex *= this.config.elasticStrength;
    } else if (this.viewport.startIndex > maxStart) {
      this.viewport.startIndex = maxStart + (this.viewport.startIndex - maxStart) * this.config.elasticStrength;
    }

    this.autoScalePriceAxis();
  }

  zoom(factor: number, centerX?: number): void {
    const center = centerX !== undefined
      ? this.viewport.startIndex + (centerX / this.canvas.width) * this.viewport.visibleBars
      : this.viewport.startIndex + this.viewport.visibleBars / 2;

    const newVisibleBars = Math.max(
      this.config.minVisibleBars,
      Math.min(this.config.maxVisibleBars, this.viewport.visibleBars * factor)
    );

    // Zoom toward the center point
    const ratio = newVisibleBars / this.viewport.visibleBars;
    this.viewport.startIndex = center - (center - this.viewport.startIndex) * ratio;
    this.viewport.visibleBars = newVisibleBars;

    this.autoScalePriceAxis();
    this.needsRender = true;
  }

  private autoScalePriceAxis(): void {
    if (!this.config.autoScale || this.candles.length === 0) return;

    const start = Math.max(0, Math.floor(this.viewport.startIndex));
    const end = Math.min(this.candles.length, Math.ceil(this.viewport.startIndex + this.viewport.visibleBars));

    let high = -Infinity;
    let low = Infinity;
    for (let i = start; i < end; i++) {
      if (this.candles[i].high > high) high = this.candles[i].high;
      if (this.candles[i].low < low) low = this.candles[i].low;
    }

    if (high === -Infinity || low === Infinity) return;

    const range = high - low;
    const padding = range * this.config.pricePadding;
    this.viewport.priceHigh = high + padding;
    this.viewport.priceLow = low - padding;
  }

  // =========================================================================
  // INPUT HANDLING — touch/mouse/wheel with inertia
  // =========================================================================

  private setupInput(): void {
    const c = this.canvas;

    // Pointer (covers mouse + touch)
    c.addEventListener('pointerdown', (e) => {
      this.isDragging = true;
      this.lastPointerX = e.clientX;
      this.lastPointerY = e.clientY;
      this.velocityX = 0;
      this.velocityY = 0;
      c.setPointerCapture(e.pointerId);
    });

    c.addEventListener('pointermove', (e) => {
      if (!this.isDragging) return;
      const dx = e.clientX - this.lastPointerX;
      const dy = e.clientY - this.lastPointerY;
      this.pan(dx, dy);
      this.velocityX = dx;
      this.velocityY = dy;
      this.lastPointerX = e.clientX;
      this.lastPointerY = e.clientY;
      this.needsRender = true;
    });

    c.addEventListener('pointerup', (e) => {
      this.isDragging = false;
      c.releasePointerCapture(e.pointerId);
      // Inertia kicks in via the render loop
    });

    c.addEventListener('pointercancel', () => {
      this.isDragging = false;
    });

    // Wheel zoom (pinch on trackpad)
    c.addEventListener('wheel', (e) => {
      e.preventDefault();
      const factor = e.deltaY > 0 ? 1.08 : 0.92; // Smooth zoom steps
      const rect = c.getBoundingClientRect();
      this.zoom(factor, e.clientX - rect.left);
    }, { passive: false });

    // Touch pinch zoom
    let lastPinchDist = 0;
    c.addEventListener('touchstart', (e) => {
      if (e.touches.length === 2) {
        lastPinchDist = this.pinchDistance(e.touches);
      }
    }, { passive: true });

    c.addEventListener('touchmove', (e) => {
      if (e.touches.length === 2) {
        const dist = this.pinchDistance(e.touches);
        if (lastPinchDist > 0) {
          const factor = lastPinchDist / dist;
          this.zoom(factor);
        }
        lastPinchDist = dist;
      }
    }, { passive: true });
  }

  private pinchDistance(touches: TouchList): number {
    const dx = touches[0].clientX - touches[1].clientX;
    const dy = touches[0].clientY - touches[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  // =========================================================================
  // RESIZE
  // =========================================================================

  private resize(): void {
    const dpr = Math.min(window.devicePixelRatio || 1, 2); // Cap at 2x for perf
    const rect = this.canvas.parentElement?.getBoundingClientRect();
    if (!rect) return;

    this.canvas.width = rect.width * dpr;
    this.canvas.height = rect.height * dpr;
    this.gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    this.needsRender = true;
  }

  // =========================================================================
  // PUBLIC API
  // =========================================================================

  getFPS(): number { return this.fps; }
  getViewport(): ViewportState { return { ...this.viewport }; }
  getTotalCandles(): number { return this.candles.length; }
  getVisibleRange(): { start: number; end: number } {
    return {
      start: Math.max(0, Math.floor(this.viewport.startIndex)),
      end: Math.min(this.candles.length, Math.ceil(this.viewport.startIndex + this.viewport.visibleBars)),
    };
  }

  /** Scroll to show the most recent candles */
  scrollToEnd(): void {
    this.viewport.startIndex = Math.max(0, this.candles.length - this.viewport.visibleBars);
    this.autoScalePriceAxis();
    this.needsRender = true;
  }

  /** Set visible bar count (zoom level) */
  setVisibleBars(count: number): void {
    this.viewport.visibleBars = Math.max(this.config.minVisibleBars, Math.min(this.config.maxVisibleBars, count));
    this.autoScalePriceAxis();
    this.needsRender = true;
  }

  /** Force a re-render next frame */
  invalidate(): void { this.needsRender = true; }

  /** Cleanup */
  destroy(): void {
    if (this.rafId) cancelAnimationFrame(this.rafId);
    this.resizeObserver?.disconnect();
    this.candleRenderer.destroy();
    this.canvas.remove();
  }
}
