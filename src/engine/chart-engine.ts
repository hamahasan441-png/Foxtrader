// ============================================================================
// CHART ENGINE - Built on TradingView Lightweight Charts
// Professional rendering | Unlimited Zoom | Replay Mode | Tick Replay
// Multi-chart Layout | Split Screen | 60 FPS native
// ============================================================================

import {
  Candle,
  Timeframe,
  ChartAnnotation,
  ChartAnnotationType,
  ColorScheme,
  Direction,
  PriceZone,
} from '../core/types';
import { TradingEventBus } from '../core/event-bus';

// TradingView Lightweight Charts types (v4.x)
// We reference the library API - imported at runtime
declare const LightweightCharts: any;

export interface ChartEngineConfig {
  container: HTMLElement | null;
  width: number;
  height: number;
  theme: 'dark' | 'light';
  crosshair: boolean;
  volumePanel: boolean;
  watermark: string;
  timeScaleVisible: boolean;
  priceScaleVisible: boolean;
  gridVisible: boolean;
  autoScale: boolean;
}

const DEFAULT_ENGINE_CONFIG: ChartEngineConfig = {
  container: null,
  width: 1920,
  height: 1080,
  theme: 'dark',
  crosshair: true,
  volumePanel: true,
  watermark: 'Institutional Trading Platform',
  timeScaleVisible: true,
  priceScaleVisible: true,
  gridVisible: true,
  autoScale: true,
};


// --- Theme definitions for Lightweight Charts ---
const DARK_THEME = {
  layout: {
    background: { type: 'solid', color: '#0a0e17' },
    textColor: '#94a3b8',
    fontSize: 11,
  },
  grid: {
    vertLines: { color: '#1e293b', style: 3 },
    horzLines: { color: '#1e293b', style: 3 },
  },
  crosshair: {
    mode: 0, // Normal
    vertLine: { color: '#475569', width: 1, style: 2, labelBackgroundColor: '#334155' },
    horzLine: { color: '#475569', width: 1, style: 2, labelBackgroundColor: '#334155' },
  },
  timeScale: {
    borderColor: '#1e293b',
    timeVisible: true,
    secondsVisible: false,
  },
  rightPriceScale: {
    borderColor: '#1e293b',
    scaleMargins: { top: 0.1, bottom: 0.1 },
  },
};

const LIGHT_THEME = {
  layout: {
    background: { type: 'solid', color: '#ffffff' },
    textColor: '#333333',
    fontSize: 11,
  },
  grid: {
    vertLines: { color: '#f0f0f0', style: 3 },
    horzLines: { color: '#f0f0f0', style: 3 },
  },
  crosshair: {
    mode: 0,
    vertLine: { color: '#9e9e9e', width: 1, style: 2, labelBackgroundColor: '#e0e0e0' },
    horzLine: { color: '#9e9e9e', width: 1, style: 2, labelBackgroundColor: '#e0e0e0' },
  },
  timeScale: { borderColor: '#e0e0e0', timeVisible: true, secondsVisible: false },
  rightPriceScale: { borderColor: '#e0e0e0', scaleMargins: { top: 0.1, bottom: 0.1 } },
};


// --- Interfaces for annotation rendering ---

interface PriceLineData {
  id: string;
  price: number;
  color: string;
  lineWidth: number;
  lineStyle: number; // 0=Solid, 1=Dotted, 2=Dashed, 3=LargeDashed
  axisLabelVisible: boolean;
  title: string;
}

interface MarkerData {
  time: number;
  position: 'aboveBar' | 'belowBar' | 'inBar';
  color: string;
  shape: 'circle' | 'square' | 'arrowUp' | 'arrowDown';
  text: string;
  size: number;
}

interface ZoneRectangle {
  id: string;
  priceHigh: number;
  priceLow: number;
  timeStart: number;
  timeEnd: number | null; // null = extends to right edge
  color: string;
  borderColor: string;
  label: string;
}

// Multi-chart layout types
export type LayoutMode = 'SINGLE' | 'SPLIT_HORIZONTAL' | 'SPLIT_VERTICAL' | 'QUAD' | 'TRIPLE';

interface ChartInstance {
  id: string;
  chart: any; // IChartApi
  candleSeries: any; // ISeriesApi<'Candlestick'>
  volumeSeries: any; // ISeriesApi<'Histogram'>
  priceLines: Map<string, any>;
  markers: MarkerData[];
  zones: ZoneRectangle[];
  lineSeries: Map<string, any>; // Additional line series for structure
  areaSeries: Map<string, any>; // Area fills for zones
  symbol: string;
  timeframe: Timeframe;
}


export class ChartEngine {
  private config: ChartEngineConfig;
  private eventBus?: TradingEventBus;
  private charts: Map<string, ChartInstance> = new Map();
  private activeChartId: string = 'main';
  private layoutMode: LayoutMode = 'SINGLE';
  private candles: Candle[] = [];
  private annotations: ChartAnnotation[] = [];

  // Replay mode state
  private replayMode: boolean = false;
  private replayIndex: number = 0;
  private replaySpeed: number = 1;
  private replayTimer: number = 0;
  private replayCandles: Candle[] = [];

  // Performance tracking
  private fps: number = 60;
  private frameCount: number = 0;
  private lastFPSTime: number = 0;

  constructor(config: Partial<ChartEngineConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_ENGINE_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Initialize chart engine with TradingView Lightweight Charts
   */
  initialize(container: HTMLElement): void {
    this.config.container = container;
    this.config.width = container.clientWidth;
    this.config.height = container.clientHeight;

    // Create main chart
    this.createChart('main', container);

    // FPS counter
    this.startFPSCounter();

    console.log('[ChartEngine] Initialized with TradingView Lightweight Charts');
  }

  /**
   * Create a new chart instance using Lightweight Charts API
   */
  private createChart(id: string, container: HTMLElement): ChartInstance {
    const theme = this.config.theme === 'dark' ? DARK_THEME : LIGHT_THEME;

    // Create chart via Lightweight Charts API
    const chart = (window as any).LightweightCharts?.createChart(container, {
      width: container.clientWidth,
      height: container.clientHeight,
      ...theme,
      watermark: {
        visible: true,
        text: this.config.watermark,
        fontSize: 48,
        color: 'rgba(100, 100, 100, 0.1)',
        horzAlign: 'center',
        vertAlign: 'center',
      },
      handleScroll: { vertTouchDrag: true, horzTouchDrag: true, mouseWheel: true, pressedMouseMove: true },
      handleScale: { axisPressedMouseMove: true, mouseWheel: true, pinch: true },
    });


    // Candlestick series
    const candleSeries = chart?.addCandlestickSeries({
      upColor: '#00dc82',
      downColor: '#ff4757',
      borderUpColor: '#00dc82',
      borderDownColor: '#ff4757',
      wickUpColor: '#00dc82',
      wickDownColor: '#ff4757',
    });

    // Volume histogram
    let volumeSeries: any = null;
    if (this.config.volumePanel) {
      volumeSeries = chart?.addHistogramSeries({
        color: '#475569',
        priceFormat: { type: 'volume' },
        priceScaleId: 'volume',
      });
      chart?.priceScale('volume')?.applyOptions({
        scaleMargins: { top: 0.85, bottom: 0 },
      });
    }

    // Resize observer for responsive chart
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        chart?.applyOptions({
          width: entry.contentRect.width,
          height: entry.contentRect.height,
        });
      }
    });
    resizeObserver.observe(container);

    const instance: ChartInstance = {
      id,
      chart,
      candleSeries,
      volumeSeries,
      priceLines: new Map(),
      markers: [],
      zones: [],
      lineSeries: new Map(),
      areaSeries: new Map(),
      symbol: '',
      timeframe: 'M15',
    };

    this.charts.set(id, instance);
    return instance;
  }


  // =========================================================================
  // DATA MANAGEMENT
  // =========================================================================

  /**
   * Load candle data into the chart
   */
  setData(candles: Candle[]): void {
    this.candles = candles;
    const instance = this.getActiveChart();
    if (!instance) return;

    // Convert to Lightweight Charts format
    const chartData = candles.map(c => ({
      time: Math.floor(c.timestamp / 1000) as any, // Unix timestamp in seconds
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }));

    instance.candleSeries?.setData(chartData);

    // Volume data
    if (instance.volumeSeries) {
      const volumeData = candles.map(c => ({
        time: Math.floor(c.timestamp / 1000) as any,
        value: c.volume,
        color: c.close >= c.open ? 'rgba(0, 220, 130, 0.3)' : 'rgba(255, 71, 87, 0.3)',
      }));
      instance.volumeSeries.setData(volumeData);
    }
  }

  /**
   * Add a single new candle (real-time update)
   * No repainting - only updates the last bar or adds new
   */
  addCandle(candle: Candle): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    const barData = {
      time: Math.floor(candle.timestamp / 1000) as any,
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
    };

    // update() adds or updates the last bar - standard non-repainting behavior
    instance.candleSeries?.update(barData);

    if (instance.volumeSeries) {
      instance.volumeSeries.update({
        time: Math.floor(candle.timestamp / 1000) as any,
        value: candle.volume,
        color: candle.close >= candle.open ? 'rgba(0, 220, 130, 0.3)' : 'rgba(255, 71, 87, 0.3)',
      });
    }

    // Track candles internally
    if (this.candles.length > 0 && this.candles[this.candles.length - 1].timestamp === candle.timestamp) {
      this.candles[this.candles.length - 1] = candle;
    } else {
      this.candles.push(candle);
    }
  }


  // =========================================================================
  // ANNOTATIONS - Drawing Smart Money concepts on chart
  // =========================================================================

  /**
   * Add a price line (BOS, CHOCH, Liquidity levels, Entry/SL/TP)
   */
  addPriceLine(data: PriceLineData): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    // Remove existing line with same ID
    this.removePriceLine(data.id);

    const line = instance.candleSeries?.createPriceLine({
      price: data.price,
      color: data.color,
      lineWidth: data.lineWidth as any,
      lineStyle: data.lineStyle,
      axisLabelVisible: data.axisLabelVisible,
      title: data.title,
    });

    if (line) {
      instance.priceLines.set(data.id, line);
    }
  }

  /**
   * Remove a price line by ID
   */
  removePriceLine(id: string): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    const existing = instance.priceLines.get(id);
    if (existing) {
      instance.candleSeries?.removePriceLine(existing);
      instance.priceLines.delete(id);
    }
  }

  /**
   * Add chart markers (Sweep points, SMT, LIT signals)
   */
  addMarker(marker: MarkerData): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    instance.markers.push(marker);
    this.applyMarkers(instance);
  }

  /**
   * Set all markers at once (more efficient for batch updates)
   */
  setMarkers(markers: MarkerData[]): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    instance.markers = markers;
    this.applyMarkers(instance);
  }

  /**
   * Apply markers to the candlestick series
   */
  private applyMarkers(instance: ChartInstance): void {
    // Sort markers by time (required by Lightweight Charts)
    const sorted = [...instance.markers].sort((a, b) => a.time - b.time);

    const lwcMarkers = sorted.map(m => ({
      time: Math.floor(m.time / 1000) as any,
      position: m.position,
      color: m.color,
      shape: m.shape,
      text: m.text,
      size: m.size,
    }));

    instance.candleSeries?.setMarkers(lwcMarkers);
  }


  /**
   * Draw a zone rectangle using line series pairs (OB, FVG, Sessions, Premium/Discount)
   * Lightweight Charts doesn't have native rectangles, so we use area series
   */
  addZone(zone: ZoneRectangle): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    // Remove existing zone with same ID
    this.removeZone(zone.id);

    // Create area series to represent the zone
    const areaSeries = instance.chart?.addAreaSeries({
      topColor: zone.color,
      bottomColor: zone.color,
      lineColor: zone.borderColor,
      lineWidth: 1,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
      priceScaleId: 'overlay_' + zone.id,
    });

    // Configure the overlay price scale (invisible)
    instance.chart?.priceScale('overlay_' + zone.id)?.applyOptions({
      scaleMargins: { top: 0, bottom: 0 },
      visible: false,
    });

    if (areaSeries) {
      // Build zone data points
      const startTime = Math.floor(zone.timeStart / 1000);
      const endTime = zone.timeEnd ? Math.floor(zone.timeEnd / 1000) : Math.floor(Date.now() / 1000);

      // Use top price line and bottom price line to represent the zone
      areaSeries.createPriceLine({
        price: zone.priceHigh,
        color: zone.borderColor,
        lineWidth: 1,
        lineStyle: 2, // Dashed
        axisLabelVisible: false,
        title: zone.label,
      });

      areaSeries.createPriceLine({
        price: zone.priceLow,
        color: zone.borderColor,
        lineWidth: 1,
        lineStyle: 2,
        axisLabelVisible: false,
        title: '',
      });

      instance.areaSeries.set(zone.id, areaSeries);
      instance.zones.push(zone);
    }
  }

  /**
   * Remove a zone by ID
   */
  removeZone(id: string): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    const series = instance.areaSeries.get(id);
    if (series) {
      instance.chart?.removeSeries(series);
      instance.areaSeries.delete(id);
    }
    instance.zones = instance.zones.filter(z => z.id !== id);
  }


  /**
   * Add a structure line (BOS/CHOCH connecting two points)
   */
  addStructureLine(id: string, data: {
    startTime: number;
    endTime: number;
    price: number;
    color: string;
    label: string;
    dashed: boolean;
  }): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    this.removeStructureLine(id);

    // Use a line series with just 2 points
    const lineSeries = instance.chart?.addLineSeries({
      color: data.color,
      lineWidth: 2,
      lineStyle: data.dashed ? 2 : 0,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
      pointMarkersVisible: false,
    });

    if (lineSeries) {
      lineSeries.setData([
        { time: Math.floor(data.startTime / 1000) as any, value: data.price },
        { time: Math.floor(data.endTime / 1000) as any, value: data.price },
      ]);

      instance.lineSeries.set(id, lineSeries);
    }

    // Add marker at the break point with label
    if (data.label) {
      this.addMarker({
        time: data.endTime,
        position: data.price > 0 ? 'aboveBar' : 'belowBar',
        color: data.color,
        shape: 'circle',
        text: data.label,
        size: 1,
      });
    }
  }

  /**
   * Remove a structure line
   */
  removeStructureLine(id: string): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    const series = instance.lineSeries.get(id);
    if (series) {
      instance.chart?.removeSeries(series);
      instance.lineSeries.delete(id);
    }
  }

  /**
   * Add annotation from the unified annotation system
   */
  addAnnotation(annotation: ChartAnnotation): void {
    this.annotations.push(annotation);
    this.renderAnnotation(annotation);
  }

  /**
   * Clear all annotations
   */
  clearAnnotations(): void {
    const instance = this.getActiveChart();
    if (!instance) return;

    // Clear all price lines
    for (const [id] of instance.priceLines) {
      this.removePriceLine(id);
    }

    // Clear all zones
    for (const [id] of instance.areaSeries) {
      this.removeZone(id);
    }

    // Clear all line series
    for (const [id] of instance.lineSeries) {
      this.removeStructureLine(id);
    }

    // Clear markers
    instance.markers = [];
    instance.candleSeries?.setMarkers([]);

    this.annotations = [];
  }


  /**
   * Render a single annotation based on its type
   */
  private renderAnnotation(ann: ChartAnnotation): void {
    if (!ann.visible) return;
    const d = ann.data as Record<string, any>;

    switch (ann.type) {
      case 'BOS_LINE':
      case 'CHOCH_LINE':
      case 'STRUCTURE_LINE':
        this.addStructureLine(ann.id, {
          startTime: d.startTime || (d.startIndex * 60000),
          endTime: d.endTime || (d.endIndex * 60000),
          price: d.price,
          color: d.color,
          label: d.label || '',
          dashed: d.dashed || ann.type === 'CHOCH_LINE',
        });
        break;

      case 'ORDER_BLOCK_ZONE':
      case 'BREAKER_ZONE':
      case 'MITIGATION_ZONE':
      case 'FVG_ZONE':
      case 'IFVG_ZONE':
      case 'PREMIUM_ZONE':
      case 'DISCOUNT_ZONE':
      case 'OTE_ZONE':
      case 'KILL_ZONE':
      case 'TARGET_ZONE':
      case 'SESSION_BOX':
        this.addZone({
          id: ann.id,
          priceHigh: d.high,
          priceLow: d.low,
          timeStart: d.startTime || (d.startIndex * 60000),
          timeEnd: d.endTime || null,
          color: d.color,
          borderColor: d.borderColor || d.color,
          label: d.label || ann.type.replace(/_/g, ' '),
        });
        break;

      case 'LIQUIDITY_LINE':
        this.addPriceLine({
          id: ann.id,
          price: d.price,
          color: d.color,
          lineWidth: 1,
          lineStyle: 2, // Dashed
          axisLabelVisible: false,
          title: d.label || 'LIQ',
        });
        break;

      case 'ENTRY_LINE':
        this.addPriceLine({
          id: ann.id,
          price: d.price,
          color: d.color || '#00dc82',
          lineWidth: 2,
          lineStyle: 0, // Solid
          axisLabelVisible: true,
          title: d.label || 'ENTRY',
        });
        break;

      case 'SL_LINE':
        this.addPriceLine({
          id: ann.id,
          price: d.price,
          color: d.color || '#ef4444',
          lineWidth: 2,
          lineStyle: 2,
          axisLabelVisible: true,
          title: d.label || 'SL',
        });
        break;

      case 'TP_LINE':
        this.addPriceLine({
          id: ann.id,
          price: d.price,
          color: d.color || '#3b82f6',
          lineWidth: 1,
          lineStyle: 1, // Dotted
          axisLabelVisible: true,
          title: d.label || 'TP',
        });
        break;

      case 'INVALIDATION_LINE':
        this.addPriceLine({
          id: ann.id,
          price: d.price,
          color: d.color || '#ff6b6b',
          lineWidth: 1,
          lineStyle: 3, // Large dashed
          axisLabelVisible: false,
          title: 'INVALID',
        });
        break;

      case 'SWEEP_MARKER':
        this.addMarker({
          time: d.timestamp || (d.index * 60000),
          position: 'aboveBar',
          color: d.color || '#f43f5e',
          shape: 'circle',
          text: '💧',
          size: 2,
        });
        break;

      case 'SMT_MARKER':
        this.addMarker({
          time: d.timestamp || 0,
          position: 'belowBar',
          color: d.color || '#a78bfa',
          shape: 'square',
          text: 'SMT',
          size: 1,
        });
        break;

      case 'LIT_MARKER':
        this.addMarker({
          time: d.timestamp || (d.index * 60000),
          position: d.direction === 'BULLISH' ? 'belowBar' : 'aboveBar',
          color: d.color || '#f59e0b',
          shape: d.direction === 'BULLISH' ? 'arrowUp' : 'arrowDown',
          text: d.label || 'LIT',
          size: 2,
        });
        break;
    }
  }


  // =========================================================================
  // REPLAY MODE - Bar-by-bar historical replay
  // =========================================================================

  /**
   * Start replay mode from a specific index
   * Replays candles one-by-one, triggering analysis on each new bar
   */
  startReplay(startIndex: number = 0, speed: number = 1): void {
    this.replayMode = true;
    this.replayIndex = startIndex;
    this.replaySpeed = speed;
    this.replayCandles = [...this.candles];

    // Reset chart to show only candles up to startIndex
    const visibleCandles = this.replayCandles.slice(0, startIndex);
    this.setData(visibleCandles);

    // Start replay timer
    const intervalMs = Math.max(16, 1000 / speed); // Min 16ms (60fps)
    this.replayTimer = window.setInterval(() => {
      this.advanceReplay();
    }, intervalMs);

    console.log(`[ChartEngine] Replay started at index ${startIndex}, speed ${speed}x`);
  }

  /**
   * Advance replay by one candle (tick replay advances by tick)
   */
  private advanceReplay(): void {
    if (!this.replayMode) return;

    if (this.replayIndex < this.replayCandles.length) {
      const newCandle = this.replayCandles[this.replayIndex];
      this.addCandle(newCandle);
      this.replayIndex++;

      // Emit event for modules to process
      this.eventBus?.emit({
        type: 'NEW_CANDLE',
        data: { timeframe: 'M1' as Timeframe, candle: newCandle },
      });
    } else {
      this.stopReplay();
      console.log('[ChartEngine] Replay completed');
    }
  }

  /**
   * Stop replay mode
   */
  stopReplay(): void {
    this.replayMode = false;
    if (this.replayTimer) {
      clearInterval(this.replayTimer);
      this.replayTimer = 0;
    }
  }

  /**
   * Step forward one candle in replay (manual step)
   */
  stepForward(): void {
    if (this.replayMode && this.replayIndex < this.replayCandles.length) {
      this.advanceReplay();
    }
  }

  /**
   * Step backward one candle in replay
   */
  stepBackward(): void {
    if (this.replayMode && this.replayIndex > 1) {
      this.replayIndex--;
      const visibleCandles = this.replayCandles.slice(0, this.replayIndex);
      this.setData(visibleCandles);
    }
  }

  /**
   * Change replay speed
   */
  setReplaySpeed(speed: number): void {
    this.replaySpeed = speed;
    if (this.replayMode) {
      this.stopReplay();
      this.startReplay(this.replayIndex, speed);
    }
  }


  // =========================================================================
  // MULTI-CHART LAYOUT
  // =========================================================================

  /**
   * Set layout mode (single, split, quad)
   */
  setLayout(mode: LayoutMode): void {
    if (!this.config.container) return;
    this.layoutMode = mode;

    // Clear existing charts except main
    for (const [id] of this.charts) {
      if (id !== 'main') {
        const inst = this.charts.get(id);
        inst?.chart?.remove();
        this.charts.delete(id);
      }
    }

    // Reconfigure container
    const container = this.config.container;
    container.innerHTML = '';

    switch (mode) {
      case 'SINGLE':
        this.setupSingleLayout(container);
        break;
      case 'SPLIT_HORIZONTAL':
        this.setupSplitHorizontal(container);
        break;
      case 'SPLIT_VERTICAL':
        this.setupSplitVertical(container);
        break;
      case 'QUAD':
        this.setupQuadLayout(container);
        break;
      case 'TRIPLE':
        this.setupTripleLayout(container);
        break;
    }
  }

  private setupSingleLayout(container: HTMLElement): void {
    const div = document.createElement('div');
    div.style.cssText = 'width: 100%; height: 100%;';
    container.appendChild(div);
    this.createChart('main', div);
  }

  private setupSplitHorizontal(container: HTMLElement): void {
    container.style.display = 'grid';
    container.style.gridTemplateColumns = '1fr 1fr';
    container.style.gap = '1px';

    for (let i = 0; i < 2; i++) {
      const div = document.createElement('div');
      div.style.cssText = 'width: 100%; height: 100%; background: #0a0e17;';
      container.appendChild(div);
      this.createChart(i === 0 ? 'main' : `chart_${i}`, div);
    }
  }

  private setupSplitVertical(container: HTMLElement): void {
    container.style.display = 'grid';
    container.style.gridTemplateRows = '1fr 1fr';
    container.style.gap = '1px';

    for (let i = 0; i < 2; i++) {
      const div = document.createElement('div');
      div.style.cssText = 'width: 100%; height: 100%; background: #0a0e17;';
      container.appendChild(div);
      this.createChart(i === 0 ? 'main' : `chart_${i}`, div);
    }
  }

  private setupQuadLayout(container: HTMLElement): void {
    container.style.display = 'grid';
    container.style.gridTemplateColumns = '1fr 1fr';
    container.style.gridTemplateRows = '1fr 1fr';
    container.style.gap = '1px';

    for (let i = 0; i < 4; i++) {
      const div = document.createElement('div');
      div.style.cssText = 'width: 100%; height: 100%; background: #0a0e17;';
      container.appendChild(div);
      this.createChart(i === 0 ? 'main' : `chart_${i}`, div);
    }
  }

  private setupTripleLayout(container: HTMLElement): void {
    container.style.display = 'grid';
    container.style.gridTemplateColumns = '2fr 1fr';
    container.style.gridTemplateRows = '1fr 1fr';
    container.style.gap = '1px';

    // Main chart spans full height on left
    const mainDiv = document.createElement('div');
    mainDiv.style.cssText = 'width: 100%; height: 100%; grid-row: 1/3; background: #0a0e17;';
    container.appendChild(mainDiv);
    this.createChart('main', mainDiv);

    // Two smaller charts on right
    for (let i = 1; i <= 2; i++) {
      const div = document.createElement('div');
      div.style.cssText = 'width: 100%; height: 100%; background: #0a0e17;';
      container.appendChild(div);
      this.createChart(`chart_${i}`, div);
    }
  }


  // =========================================================================
  // UTILITIES & PUBLIC API
  // =========================================================================

  /**
   * Get the active chart instance
   */
  private getActiveChart(): ChartInstance | undefined {
    return this.charts.get(this.activeChartId);
  }

  /**
   * Switch active chart (for multi-chart layouts)
   */
  setActiveChart(chartId: string): void {
    if (this.charts.has(chartId)) {
      this.activeChartId = chartId;
    }
  }

  /**
   * Zoom to fit all data
   */
  fitContent(): void {
    const instance = this.getActiveChart();
    instance?.chart?.timeScale()?.fitContent();
  }

  /**
   * Scroll to real-time (latest candle)
   */
  scrollToRealTime(): void {
    const instance = this.getActiveChart();
    instance?.chart?.timeScale()?.scrollToRealTime();
  }

  /**
   * Set visible range
   */
  setVisibleRange(from: number, to: number): void {
    const instance = this.getActiveChart();
    instance?.chart?.timeScale()?.setVisibleRange({
      from: Math.floor(from / 1000),
      to: Math.floor(to / 1000),
    });
  }

  /**
   * Take screenshot of current chart
   */
  takeScreenshot(): string | null {
    const instance = this.getActiveChart();
    return instance?.chart?.takeScreenshot()?.toDataURL() || null;
  }

  /**
   * Apply theme
   */
  setTheme(theme: 'dark' | 'light'): void {
    this.config.theme = theme;
    const themeConfig = theme === 'dark' ? DARK_THEME : LIGHT_THEME;

    for (const instance of this.charts.values()) {
      instance.chart?.applyOptions(themeConfig);
    }
  }

  /**
   * Start FPS counter
   */
  private startFPSCounter(): void {
    this.lastFPSTime = performance.now();
    const measure = () => {
      this.frameCount++;
      const now = performance.now();
      if (now - this.lastFPSTime >= 1000) {
        this.fps = this.frameCount;
        this.frameCount = 0;
        this.lastFPSTime = now;
      }
      if (this.charts.size > 0) {
        requestAnimationFrame(measure);
      }
    };
    requestAnimationFrame(measure);
  }

  /**
   * Get current FPS
   */
  getCurrentFPS(): number {
    return this.fps;
  }

  /**
   * Check if replay is active
   */
  isReplayActive(): boolean {
    return this.replayMode;
  }

  /**
   * Get view state info
   */
  getViewState(): { visibleBars: number; totalBars: number; replayIndex: number } {
    const instance = this.getActiveChart();
    const range = instance?.chart?.timeScale()?.getVisibleLogicalRange();
    return {
      visibleBars: range ? Math.floor(range.to - range.from) : 0,
      totalBars: this.candles.length,
      replayIndex: this.replayIndex,
    };
  }

  /**
   * Start the chart (called after initialization)
   */
  start(): void {
    // Lightweight Charts handles its own rendering loop at native 60fps
    console.log('[ChartEngine] Running (TradingView Lightweight Charts handles rendering)');
  }

  /**
   * Stop chart rendering
   */
  stop(): void {
    this.stopReplay();
  }

  /**
   * Destroy and cleanup all chart instances
   */
  destroy(): void {
    this.stopReplay();
    for (const instance of this.charts.values()) {
      instance.chart?.remove();
    }
    this.charts.clear();
    this.annotations = [];
    this.candles = [];
  }
}
