import { instrumentOf } from "./instruments";
import type { Candle, LineSpec, Setup, Tape, TimeframeId } from "./types";

type Swing = { kind: "high" | "low"; time: number; price: number; index: number };

export function tapeFromCandles(symbol: string, tf: TimeframeId, candles: Candle[]): Tape {
  if (candles.length === 0) throw new Error("Dukascopy returned no candles");
  const inst = instrumentOf(symbol);
  const atr = averageRange(candles);
  const unit = atr > 0 ? atr : inst.unit;
  const last = candles[candles.length - 1];
  const swings = findSwings(candles, 2);
  const highs = swings.filter((swing) => swing.kind === "high");
  const lows = swings.filter((swing) => swing.kind === "low");
  const swingHigh = highs[highs.length - 1];
  const swingLow = lows[lows.length - 1];
  const brokeHigh = Boolean(swingHigh && last.close > swingHigh.price && last.time > swingHigh.time);
  const brokeLow = Boolean(swingLow && last.close < swingLow.price && last.time > swingLow.time);

  let bias: Tape["bias"] = "Neutral";
  let biasDetail = "No fresh break of structure";
  if (brokeHigh && !brokeLow) {
    bias = "Bullish";
    biasDetail = "Close through the last swing high";
  } else if (brokeLow && !brokeHigh) {
    bias = "Bearish";
    biasDetail = "Close through the last swing low";
  } else if (swingHigh && swingLow) {
    bias = last.close >= (swingHigh.price + swingLow.price) / 2 ? "Bullish" : "Bearish";
    biasDetail = "Still inside the last swing range";
  }

  const lines: LineSpec[] = [];
  if (swingHigh) {
    lines.push({
      id: "swing-high",
      price: swingHigh.price,
      label: brokeHigh ? "BOS" : "Swing high",
      tone: "bull",
      layer: "structure",
      from: swingHigh.time,
      priority: 3,
    });
  }
  if (swingLow) {
    lines.push({
      id: "swing-low",
      price: swingLow.price,
      label: brokeLow ? "BOS" : "Swing low",
      tone: "bear",
      layer: "structure",
      from: swingLow.time,
      priority: 3,
    });
  }

  const buySide = highs.filter((swing) => swing.price > last.close + unit * 0.1).sort((a, b) => a.price - b.price)[0];
  const sellSide = lows.filter((swing) => swing.price < last.close - unit * 0.1).sort((a, b) => b.price - a.price)[0];
  if (buySide) {
    lines.push({
      id: "bsl",
      price: buySide.price,
      label: "BSL",
      tone: "muted",
      layer: "liquidity",
      from: buySide.time,
      priority: 2,
      dashed: true,
    });
  }
  if (sellSide) {
    lines.push({
      id: "ssl",
      price: sellSide.price,
      label: "SSL",
      tone: "muted",
      layer: "liquidity",
      from: sellSide.time,
      priority: 2,
      dashed: true,
    });
  }

  const setup = findSetup(candles, highs, lows, unit);
  if (setup) {
    lines.push(
      { id: "entry", price: setup.entry, label: "Entry", tone: "accent", layer: "levels", from: candles[Math.max(0, candles.length - 20)].time, priority: 5, clickable: true },
      { id: "stop", price: setup.stop, label: "SL", tone: "bear", layer: "levels", from: candles[Math.max(0, candles.length - 20)].time, priority: 4 },
      { id: "tp1", price: setup.tp1, label: "TP1", tone: "bull", layer: "levels", from: candles[Math.max(0, candles.length - 20)].time, priority: 4 },
      { id: "tp2", price: setup.tp2, label: "TP2", tone: "bull", layer: "levels", from: candles[Math.max(0, candles.length - 20)].time, priority: 3 },
      { id: "tp3", price: setup.tp3, label: "TP3", tone: "bull", layer: "levels", from: candles[Math.max(0, candles.length - 20)].time, priority: 2 },
    );
  }

  const lookback = tf === "D1" ? 1 : tf === "H4" ? 6 : tf === "H1" ? 24 : 20;
  const reference = candles[Math.max(0, candles.length - 1 - lookback)].close;

  return {
    candles,
    lines,
    zone: setup
      ? {
          id: "entry-zone",
          low: setup.entryLow,
          high: setup.entryHigh,
          from: candles[Math.max(0, candles.length - 12)].time,
        }
      : null,
    setup,
    bias,
    biasDetail,
    liquidityDetail: buySide || sellSide ? "Nearest untouched swing pools" : "No nearby pool",
    structureDetail: biasDetail,
    entryDetail: setup ? (setup.status === "active" ? "Sweep still holds" : "Stop has been traded through") : "No sweep armed",
    breatheLow: last.low,
    breatheHigh: last.high,
    unit,
    digits: inst.digits,
    reference,
    rng: () => 0.5,
  };
}

function averageRange(candles: Candle[]) {
  const start = Math.max(1, candles.length - 14);
  let sum = 0;
  let count = 0;
  for (let index = start; index < candles.length; index += 1) {
    const bar = candles[index];
    const prev = candles[index - 1];
    sum += Math.max(bar.high - bar.low, Math.abs(bar.high - prev.close), Math.abs(bar.low - prev.close));
    count += 1;
  }
  return count ? sum / count : 0;
}

function findSwings(candles: Candle[], wing: number): Swing[] {
  const swings: Swing[] = [];
  for (let index = wing; index < candles.length - wing; index += 1) {
    let high = true;
    let low = true;
    for (let step = 1; step <= wing; step += 1) {
      if (candles[index].high <= candles[index - step].high || candles[index].high <= candles[index + step].high) high = false;
      if (candles[index].low >= candles[index - step].low || candles[index].low >= candles[index + step].low) low = false;
    }
    if (high) swings.push({ kind: "high", time: candles[index].time, price: candles[index].high, index });
    else if (low) swings.push({ kind: "low", time: candles[index].time, price: candles[index].low, index });
  }
  return swings;
}

function findSetup(candles: Candle[], highs: Swing[], lows: Swing[], unit: number): Setup | null {
  const from = Math.max(1, candles.length - 30);
  for (let index = candles.length - 1; index >= from; index -= 1) {
    const bar = candles[index];
    const poolLow = lows.filter((swing) => swing.index < index).at(-1);
    const poolHigh = highs.filter((swing) => swing.index < index).at(-1);
    if (poolLow && bar.low < poolLow.price && bar.close > poolLow.price) {
      return buildSetup("long", candles, index, bar.low, poolHigh?.price ?? bar.close + unit * 2, unit);
    }
    if (poolHigh && bar.high > poolHigh.price && bar.close < poolHigh.price) {
      return buildSetup("short", candles, index, bar.high, poolLow?.price ?? bar.close - unit * 2, unit);
    }
  }
  return null;
}

function buildSetup(
  direction: "long" | "short",
  candles: Candle[],
  index: number,
  sweep: number,
  target: number,
  unit: number,
): Setup {
  const bar = candles[index];
  const up = direction === "long";
  const entry = bar.close;
  const stop = up ? sweep - unit * 0.15 : sweep + unit * 0.15;
  const risk = Math.abs(entry - stop);
  const tp1raw = up ? Math.max(target, entry + risk) : Math.min(target, entry - risk);
  const tp1 = tp1raw;
  const tp2 = up ? entry + risk * 2 : entry - risk * 2;
  const tp3 = up ? entry + risk * 3 : entry - risk * 3;
  const later = candles.slice(index + 1);
  const invalidated = later.some((item) => (up ? item.low <= stop : item.high >= stop));
  const rr = (price: number) => (risk > 0 ? Math.abs(price - entry) / risk : 0);
  return {
    id: `${direction}-${bar.time}`,
    direction,
    status: invalidated ? "invalidated" : "active",
    entry,
    entryLow: Math.min(bar.open, bar.close),
    entryHigh: Math.max(bar.open, bar.close),
    stop,
    tp1,
    tp2,
    tp3,
    sweep,
    rr1: rr(tp1),
    rr2: rr(tp2),
    rr3: rr(tp3),
    checklist: [
      { label: "Liquidity pool marked from a prior swing", ok: true },
      { label: up ? "Sell-side swept and reclaimed" : "Buy-side swept and reclaimed", ok: true },
      { label: "Stop still beyond the sweep", ok: !invalidated },
    ],
    invalidation: up ? "A close through the sweep low" : "A close through the sweep high",
    thesis: up
      ? "Price swept the prior swing low and closed back above it. The entry is that reclaim, with the stop beyond the sweep."
      : "Price swept the prior swing high and closed back below it. The entry is that reclaim, with the stop beyond the sweep.",
  };
}
