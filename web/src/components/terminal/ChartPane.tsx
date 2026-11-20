import { Component, useEffect, useRef, useState, type ReactNode } from "react";
import {
  CandlestickSeries,
  ColorType,
  CrosshairMode,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from "lightweight-charts";
import { useDesk } from "@/lib/market/desk";
import { getTape } from "@/lib/market/cache";
import { changeOf, lastOf } from "@/lib/market/engine";
import { instrumentOf } from "@/lib/market/instruments";
import { formatPct, formatPx } from "@/lib/market/format";
import { projectLines, projectZone } from "@/lib/market/project";
import type { Layers, Tone } from "@/lib/market/types";
import { cn } from "@/lib/cn";

type Hover = { o: number; h: number; l: number; c: number } | null;

type Mark = {
  id: string;
  x1: number;
  x2: number;
  y: number;
  label: string;
  color: string;
  dashed: boolean;
  priority: number;
  showLabel: boolean;
  clickable: boolean;
};

type Band = { x: number; y: number; w: number; h: number; color: string };

type Tokens = {
  bull: string;
  bear: string;
  accent: string;
  muted: string;
  line: string;
  grid: string;
  raised: string;
};

const LAYER_TOGGLES: { key: keyof Layers; label: string; hint: string }[] = [
  { key: "structure", label: "Structure", hint: "BOS and CHoCH" },
  { key: "liquidity", label: "Liquidity", hint: "Nearest pools" },
  { key: "lit", label: "LIT", hint: "Active entry zone" },
  { key: "levels", label: "Levels", hint: "Entry, stop, targets" },
];

function readTokens(): Tokens {
  const style = getComputedStyle(document.documentElement);
  const pick = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
  return {
    bull: pick("--color-bull", "#3f9d78"),
    bear: pick("--color-bear", "#d16e68"),
    accent: pick("--color-accent", "#c4a36a"),
    muted: pick("--color-muted", "#a3acb8"),
    line: pick("--color-line", "#2c333d"),
    grid: pick("--color-line-soft", "#222830"),
    raised: pick("--color-raised", "#1b2129"),
  };
}

function placedLabels(marks: Mark[]) {
  const rows = marks.filter((mark) => mark.showLabel).map((mark) => ({ ...mark }));
  rows.sort((a, b) => a.y - b.y);
  let floor = -1e9;
  for (const row of rows) {
    if (row.y < floor) row.y = floor;
    floor = row.y + 18;
  }
  return rows;
}

function chartBars(candles: { time: number; open: number; high: number; low: number; close: number }[]) {
  const bars: { time: UTCTimestamp; open: number; high: number; low: number; close: number }[] = [];
  let lastTime = -1;
  for (const candle of candles) {
    const time = Math.floor(candle.time);
    if (!Number.isFinite(time) || time <= lastTime) continue;
    const open = candle.open;
    const close = candle.close;
    const high = Math.max(candle.high, open, close);
    const low = Math.min(candle.low, open, close);
    if (![open, high, low, close].every((value) => Number.isFinite(value) && value > 0)) continue;
    if (high < low) continue;
    lastTime = time;
    bars.push({ time: time as UTCTimestamp, open, high, low, close });
  }
  return bars;
}

function toneColor(tone: Tone, tokens: Tokens) {
  if (tone === "bull") return tokens.bull;
  if (tone === "bear") return tokens.bear;
  if (tone === "accent") return tokens.accent;
  return tokens.muted;
}

function ChartView() {
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const layers = useDesk((state) => state.layers);
  const revision = useDesk((state) => state.revision);
  const viewNonce = useDesk((state) => state.viewNonce);
  const toggleLayer = useDesk((state) => state.toggleLayer);
  const setDetail = useDesk((state) => state.setDetail);
  const feedNote = useDesk((state) => state.feedNote);
  const feed = useDesk((state) => state.feed);
  const refresh = useDesk((state) => state.refresh);
  const setMeridian = useDesk((state) => state.setMeridian);
  const meridianOn = layers.structure || layers.liquidity || layers.lit || layers.levels;
  const [indicatorsOpen, setIndicatorsOpen] = useState(false);
  const inst = instrumentOf(symbol);
  const tape = getTape(symbol, timeframe);
  const last = tape ? lastOf(tape) : null;
  const pct = tape ? changeOf(tape) : 0;
  const [hover, setHover] = useState<Hover>(null);
  const [ready, setReady] = useState(false);
  const [marks, setMarks] = useState<Mark[]>([]);
  const [band, setBand] = useState<Band | null>(null);
  const aliveRef = useRef(true);
  const hostRef = useRef<HTMLDivElement>(null);
  const apiRef = useRef<{ chart: IChartApi; series: ISeriesApi<"Candlestick"> } | null>(null);
  const keyRef = useRef("");
  const viewRef = useRef(0);
  const propsRef = useRef({ symbol, timeframe, layers, digits: inst.digits });
  propsRef.current = { symbol, timeframe, layers, digits: inst.digits };
  const drawRef = useRef<() => void>(() => {});

  drawRef.current = () => {
    try {
    const api = apiRef.current;
    const host = hostRef.current;
    if (!api || !host) return;
    const { chart, series } = api;
    const current = propsRef.current;
    const live = getTape(current.symbol, current.timeframe);
    if (!live) return;
    const tokens = readTokens();
    const width = host.clientWidth;
    const scaleWidth = chart.priceScale("right").width();
    const plotRight = Math.max(0, width - scaleWidth);
    const lines = projectLines(live, current.layers);
    const zone = projectZone(live, current.layers);

    const xOf = (time: number) => {
      const x = chart.timeScale().timeToCoordinate(time as UTCTimestamp);
      if (x != null) return x as number;
      const range = chart.timeScale().getVisibleRange();
      if (!range) return null;
      if (time <= Number(range.from)) return 0;
      return null;
    };

    const next: Mark[] = [];
    for (const line of lines) {
      const y = series.priceToCoordinate(line.price);
      const x1 = xOf(line.from);
      if (y == null || !Number.isFinite(y) || x1 == null || x1 >= plotRight) continue;
      next.push({
        id: line.id,
        x1,
        x2: plotRight,
        y: y as number,
        label: line.label,
        color: toneColor(line.tone, tokens),
        dashed: Boolean(line.dashed),
        priority: line.priority,
        showLabel: true,
        clickable: Boolean(line.clickable),
      });
    }

    const placed: number[] = [];
    const byPriority = [...next].sort((a, b) => b.priority - a.priority || a.y - b.y);
    const visible = new Set<string>();
    for (const mark of byPriority) {
      if (placed.every((y) => Math.abs(y - mark.y) >= 16)) {
        placed.push(mark.y);
        visible.add(mark.id);
      }
    }
    for (const mark of next) mark.showLabel = visible.has(mark.id);

    let nextBand: Band | null = null;
    if (zone) {
      const y1 = series.priceToCoordinate(zone.high);
      const y2 = series.priceToCoordinate(zone.low);
      const x1 = xOf(zone.from) ?? 0;
      if (y1 != null && y2 != null && x1 < plotRight) {
        const top = Math.min(y1 as number, y2 as number);
        const height = Math.abs((y2 as number) - (y1 as number));
        nextBand = {
          x: x1,
          y: top,
          w: Math.max(0, plotRight - x1),
          h: Math.max(height, 1),
          color: tokens.accent,
        };
      }
    }
    if (!aliveRef.current) return;
    setMarks(next);
    setBand(nextBand);
    } catch {
      /* chart went away mid-draw */
    }
  };

  useEffect(() => {
    aliveRef.current = true;
    try {
      if (localStorage.getItem("meridian-indicators-seen") !== "1") setIndicatorsOpen(true);
    } catch {
      /* ignore private storage */
    }
    return () => {
      aliveRef.current = false;
    };
  }, []);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const tokens = readTokens();
    const font = getComputedStyle(document.body).fontFamily;
    const chart = createChart(host, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: tokens.muted,
        fontFamily: font,
        fontSize: 12,
        attributionLogo: true,
      },
      grid: {
        vertLines: { color: tokens.grid },
        horzLines: { color: tokens.grid },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: { color: tokens.line, width: 1, labelBackgroundColor: tokens.raised },
        horzLine: { color: tokens.line, width: 1, labelBackgroundColor: tokens.raised },
      },
      rightPriceScale: {
        borderColor: tokens.line,
        scaleMargins: { top: 0.14, bottom: 0.1 },
      },
      timeScale: {
        borderColor: tokens.line,
        timeVisible: true,
        secondsVisible: false,
        rightOffset: 8,
        barSpacing: 7,
      },
      handleScroll: true,
      handleScale: true,
    });
    const series = chart.addSeries(CandlestickSeries, {
      upColor: tokens.bull,
      downColor: tokens.bear,
      borderVisible: false,
      wickUpColor: tokens.bull,
      wickDownColor: tokens.bear,
      priceLineVisible: false,
      lastValueVisible: true,
    });
    chart.subscribeCrosshairMove((param) => {
      const data = param.seriesData.get(series);
      if (data && "open" in data && "high" in data && "low" in data && "close" in data) {
        setHover({ o: data.open, h: data.high, l: data.low, c: data.close });
      } else {
        setHover(null);
      }
    });
    const onRange = () => drawRef.current();
    chart.timeScale().subscribeVisibleLogicalRangeChange(onRange);
    const observer = new ResizeObserver(() => drawRef.current());
    observer.observe(host);
    apiRef.current = { chart, series };
    keyRef.current = "";
    setReady(true);

    return () => {
      observer.disconnect();
      chart.remove();
      apiRef.current = null;
      setReady(false);
    };
  }, []);

  useEffect(() => {
    const api = apiRef.current;
    if (!api) return;
    const live = getTape(symbol, timeframe);
    if (!live) {
      if (keyRef.current !== "") {
        try {
          api.series.setData([]);
        } catch {
          /* chart already disposed */
        }
        keyRef.current = "";
      }
      return;
    }
    const digits = inst.digits;
    const data = chartBars(live.candles);
    const key = `${symbol}|${timeframe}`;
    const tokens = readTokens();
    try {
      api.chart.applyOptions({
        grid: {
          vertLines: { color: layers.grid ? tokens.grid : "transparent" },
          horzLines: { color: layers.grid ? tokens.grid : "transparent" },
        },
        localization: { priceFormatter: (price: number) => formatPx(price, digits) },
        timeScale: { timeVisible: timeframe !== "D1", secondsVisible: false },
      });
      if (keyRef.current !== key) {
        api.series.applyOptions({
          priceFormat: { type: "price", precision: digits, minMove: 10 ** -digits },
        });
        api.series.setData(data);
        if (data.length > 0) api.chart.timeScale().fitContent();
        keyRef.current = key;
      } else if (data.length > 0) {
        const bar = data[data.length - 1];
        try {
          api.series.update(bar);
        } catch {
          api.series.setData(data);
        }
      }
      if (viewRef.current !== viewNonce) {
        if (data.length > 0) api.chart.timeScale().fitContent();
        viewRef.current = viewNonce;
      }
      drawRef.current();
    } catch {
      /* keep the last good frame instead of taking down the desk */
    }
  }, [symbol, timeframe, revision, layers, viewNonce, ready, inst.digits]);

  const ohlc = hover ?? (tape && tape.candles.length > 0
    ? {
        o: tape.candles[tape.candles.length - 1].open,
        h: tape.candles[tape.candles.length - 1].high,
        l: tape.candles[tape.candles.length - 1].low,
        c: tape.candles[tape.candles.length - 1].close,
      }
    : null);

  const closeIndicators = () => {
    setIndicatorsOpen(false);
    try {
      localStorage.setItem("meridian-indicators-seen", "1");
    } catch {
      /* ignore private storage */
    }
  };

  return (
    <section className="flex min-h-0 min-w-0 flex-1 flex-col bg-canvas">
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-line px-3">
        <div className="min-w-0">
          <div className="truncate text-sm text-fg">{inst.name}</div>
          <div className="hidden items-center gap-3 font-mono text-xs tabular-nums text-muted sm:flex">
            {ohlc ? (
              <>
                <span>O {formatPx(ohlc.o, inst.digits)}</span>
                <span>H {formatPx(ohlc.h, inst.digits)}</span>
                <span>L {formatPx(ohlc.l, inst.digits)}</span>
                <span>C {formatPx(ohlc.c, inst.digits)}</span>
              </>
            ) : (
              <span>{feed === "down" ? "Dukascopy is not connected" : "Connecting to Dukascopy"}</span>
            )}
          </div>
        </div>
        <div className="ml-auto text-right">
          <div
            className={cn(
              "font-mono text-xl tabular-nums",
              pct >= 0 ? "text-bull" : "text-bear",
            )}
          >
            {last == null ? "—" : formatPx(last, inst.digits)}
          </div>
          <div className={cn("font-mono text-xs tabular-nums", last == null ? "text-faint" : pct >= 0 ? "text-bull" : "text-bear")}>
            {last == null ? "—" : formatPct(pct)}
          </div>
        </div>
      </div>
      <div className="relative flex h-11 shrink-0 items-center gap-1 overflow-x-auto border-b border-line-soft px-2">
        <button
          type="button"
          aria-expanded={indicatorsOpen}
          onClick={() => (indicatorsOpen ? closeIndicators() : setIndicatorsOpen(true))}
          className={cn(
            "h-11 shrink-0 border-b px-3 text-sm md:h-8",
            indicatorsOpen ? "border-accent text-fg" : "border-transparent text-muted hover:text-fg",
          )}
        >
          Indicators
        </button>
        <span className={cn("shrink-0 px-2 text-sm", meridianOn ? "text-accent" : "text-faint")}>
          Meridian {meridianOn ? "on" : "off"}
        </span>
        {LAYER_TOGGLES.map((layer) => {
          const on = layers[layer.key];
          return (
            <button
              key={layer.key}
              type="button"
              aria-pressed={on}
              title={layer.hint}
              onClick={() => toggleLayer(layer.key)}
              className={cn(
                "h-11 shrink-0 border-b px-3 text-sm md:h-8",
                on ? "border-accent text-fg" : "border-transparent text-faint hover:text-muted",
              )}
            >
              {layer.label}
            </button>
          );
        })}
        {indicatorsOpen ? (
          <div className="absolute top-11 left-2 z-30 w-72 border border-line bg-surface p-3 md:top-9">
            <div className="flex items-center justify-between">
              <h2 className="text-xs uppercase tracking-wider text-muted">Indicators</h2>
              <button type="button" onClick={closeIndicators} className="h-8 px-2 text-xs text-muted">
                Close
              </button>
            </div>
            <button
              type="button"
              onClick={() => setMeridian(!meridianOn)}
              className="mt-2 flex h-11 w-full items-center justify-between border border-line-soft px-3 text-left"
            >
              <span>
                <span className="block text-sm text-fg">Meridian</span>
                <span className="block text-xs text-muted">BOS / CHoCH, liquidity, LIT</span>
              </span>
              <span className={cn("text-xs", meridianOn ? "text-accent" : "text-faint")}>{meridianOn ? "On" : "Off"}</span>
            </button>
            <div className="mt-1">
              {LAYER_TOGGLES.map((layer) => (
                <button
                  key={`menu-${layer.key}`}
                  type="button"
                  onClick={() => toggleLayer(layer.key)}
                  className="flex h-11 w-full items-center justify-between px-3 text-left text-sm"
                >
                  <span className="text-fg">{layer.label}</span>
                  <span className={cn("text-xs", layers[layer.key] ? "text-accent" : "text-faint")}>
                    {layers[layer.key] ? "On" : "Off"}
                  </span>
                </button>
              ))}
            </div>
          </div>
        ) : null}
      </div>
      <div className="relative min-h-0 flex-1 overflow-hidden">
        <div ref={hostRef} className="absolute inset-0" />
        {!tape ? (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 px-6 text-center">
            <p className="text-sm text-muted">
              {feed === "down" ? "Dukascopy is not connected" : "Connecting to Dukascopy"}
            </p>
            {feedNote ? <p className="max-w-sm text-xs text-faint">{feedNote}</p> : null}
            {feed === "down" ? (
              <button type="button" onClick={() => void refresh(symbol, timeframe)} className="h-11 px-3 text-sm text-accent">
                Retry connection
              </button>
            ) : null}
          </div>
        ) : null}
        <div className="pointer-events-none absolute inset-0">
          {band ? (
            <svg className="absolute inset-0 h-full w-full">
              <rect
                x={band.x}
                y={band.y}
                width={band.w}
                height={band.h}
                fill={band.color}
                fillOpacity={0.12}
                stroke={band.color}
                strokeOpacity={0.45}
              />
            </svg>
          ) : null}
          <svg className="absolute inset-0 h-full w-full">
            {marks.map((mark) => (
              <line
                key={mark.id}
                x1={mark.x1}
                x2={mark.x2}
                y1={mark.y}
                y2={mark.y}
                stroke={mark.color}
                strokeOpacity={0.9}
                strokeWidth={1}
                strokeDasharray={mark.dashed ? "3 3" : undefined}
              />
            ))}
          </svg>
          {placedLabels(marks).map((mark) => (
              <div
                key={`${mark.id}-label`}
                className={cn("absolute", mark.clickable && "pointer-events-auto")}
                style={{ left: mark.x2 - 4, top: mark.y, transform: "translate(-100%, -50%)" }}
              >
                {mark.clickable ? (
                  <button
                    type="button"
                    onClick={() => setDetail(true)}
                  className="border border-line bg-canvas/90 px-2 py-1 font-mono text-xs"
                    style={{ color: mark.color }}
                  >
                    {mark.label}
                  </button>
                ) : (
                  <span
                    className="border border-line bg-canvas/90 px-1.5 py-0.5 font-mono text-xs"
                    style={{ color: mark.color }}
                  >
                    {mark.label}
                  </span>
                )}
              </div>
            ))}
        </div>
        {ready ? null : (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-muted">
            Preparing tape
          </div>
        )}
      </div>
    </section>
  );
}

class ChartBoundary extends Component<{ children: ReactNode }, { error: string | null }> {
  state = { error: null as string | null };

  static getDerivedStateFromError(error: unknown) {
    return { error: error instanceof Error ? error.message : "Chart failed" };
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <section className="flex min-h-0 min-w-0 flex-1 flex-col items-center justify-center gap-3 bg-canvas px-6 text-center">
        <p className="text-sm text-fg">The chart hit an error and was reset.</p>
        <p className="max-w-sm text-xs text-muted">{this.state.error}</p>
        <button type="button" className="h-11 px-3 text-sm text-accent" onClick={() => this.setState({ error: null })}>
          Reload chart
        </button>
      </section>
    );
  }
}

export function ChartPane() {
  return (
    <ChartBoundary>
      <ChartView />
    </ChartBoundary>
  );
}
