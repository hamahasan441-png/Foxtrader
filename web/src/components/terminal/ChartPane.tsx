import { useEffect, useRef, useState } from "react";
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

function toneColor(tone: Tone, tokens: Tokens) {
  if (tone === "bull") return tokens.bull;
  if (tone === "bear") return tokens.bear;
  if (tone === "accent") return tokens.accent;
  return tokens.muted;
}

export function ChartPane() {
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const layers = useDesk((state) => state.layers);
  const revision = useDesk((state) => state.revision);
  const viewNonce = useDesk((state) => state.viewNonce);
  const toggleLayer = useDesk((state) => state.toggleLayer);
  const setDetail = useDesk((state) => state.setDetail);
  const inst = instrumentOf(symbol);
  const tape = getTape(symbol, timeframe);
  const last = lastOf(tape);
  const pct = changeOf(tape);
  const [hover, setHover] = useState<Hover>(null);
  const [ready, setReady] = useState(false);
  const [marks, setMarks] = useState<Mark[]>([]);
  const [band, setBand] = useState<Band | null>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const apiRef = useRef<{ chart: IChartApi; series: ISeriesApi<"Candlestick"> } | null>(null);
  const keyRef = useRef("");
  const viewRef = useRef(0);
  const propsRef = useRef({ symbol, timeframe, layers, digits: inst.digits });
  propsRef.current = { symbol, timeframe, layers, digits: inst.digits };
  const drawRef = useRef<() => void>(() => {});

  drawRef.current = () => {
    const api = apiRef.current;
    const host = hostRef.current;
    if (!api || !host) return;
    const { chart, series } = api;
    const current = propsRef.current;
    const live = getTape(current.symbol, current.timeframe);
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
      if (y == null || x1 == null || x1 >= plotRight) continue;
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
    setMarks(next);
    setBand(nextBand);
  };

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
    const digits = inst.digits;
    const data = live.candles.map((candle) => ({
      time: candle.time as UTCTimestamp,
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
    }));
    const key = `${symbol}|${timeframe}`;
    const tokens = readTokens();
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
      api.chart.timeScale().fitContent();
      keyRef.current = key;
    } else {
      const bar = data[data.length - 1];
      if (bar) api.series.update(bar);
    }
    if (viewRef.current !== viewNonce) {
      api.chart.timeScale().fitContent();
      viewRef.current = viewNonce;
    }
    drawRef.current();
  }, [symbol, timeframe, revision, layers, viewNonce, ready, inst.digits]);

  const ohlc = hover ?? {
    o: tape.candles[tape.candles.length - 1].open,
    h: tape.candles[tape.candles.length - 1].high,
    l: tape.candles[tape.candles.length - 1].low,
    c: tape.candles[tape.candles.length - 1].close,
  };

  return (
    <section className="flex min-h-0 min-w-0 flex-1 flex-col bg-canvas">
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-line px-3">
        <div className="min-w-0">
          <div className="truncate text-sm text-fg">{inst.name}</div>
          <div className="hidden items-center gap-3 font-mono text-xs tabular-nums text-muted sm:flex">
            <span>O {formatPx(ohlc.o, inst.digits)}</span>
            <span>H {formatPx(ohlc.h, inst.digits)}</span>
            <span>L {formatPx(ohlc.l, inst.digits)}</span>
            <span>C {formatPx(ohlc.c, inst.digits)}</span>
          </div>
        </div>
        <div className="ml-auto text-right">
          <div
            className={cn(
              "font-mono text-xl tabular-nums",
              pct >= 0 ? "text-bull" : "text-bear",
            )}
          >
            {formatPx(last, inst.digits)}
          </div>
          <div className={cn("font-mono text-xs tabular-nums", pct >= 0 ? "text-bull" : "text-bear")}>
            {formatPct(pct)}
          </div>
        </div>
      </div>
      <div className="flex h-11 shrink-0 items-center gap-1 overflow-x-auto border-b border-line-soft px-2">
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
      </div>
      <div className="relative min-h-0 flex-1 overflow-hidden">
        <div ref={hostRef} className="absolute inset-0" />
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
          {marks
            .filter((mark) => mark.showLabel)
            .map((mark) => (
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
