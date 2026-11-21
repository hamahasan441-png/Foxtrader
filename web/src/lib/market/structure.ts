import { formatPx } from "./format";
import { instrumentOf } from "./instruments";
import type { Candle, LineSpec, Setup, Tape, TimeframeId } from "./types";

type Swing = { kind: "high" | "low"; time: number; price: number; index: number };
type EventName = "BOS" | "CHoCH";

type StructureEvent = {
  name: EventName;
  direction: "Bullish" | "Bearish";
  price: number;
  time: number;
};

export function tapeFromCandles(symbol: string, tf: TimeframeId, candles: Candle[]): Tape {
  if (candles.length === 0) throw new Error("Dukascopy returned no candles");
  const inst = instrumentOf(symbol);
  const atr = averageRange(candles);
  const unit = atr > 0 ? atr : inst.unit;
  const last = candles[candles.length - 1];
  const px = (price: number) => formatPx(price, inst.digits);
  const raw = findSwings(candles, 2);
  const spaced = separate(raw, unit * 0.55);
  const swings = spaced.length >= 4 ? spaced : raw;
  const highs = swings.filter((swing) => swing.kind === "high");
  const lows = swings.filter((swing) => swing.kind === "low");
  const read = readStructure(candles, swings);
  const lines: LineSpec[] = [];

  if (read.event) {
    lines.push({
      id: "structure-event",
      price: read.event.price,
      label: read.event.name,
      tone: read.event.direction === "Bullish" ? "bull" : "bear",
      layer: "structure",
      from: read.event.time,
      priority: 4,
    });
  }
  if (read.event) {
    const hold = read.bias === "Bearish" ? read.high : read.low;
    if (hold && Math.abs(hold.price - read.event.price) > unit * 0.35) {
      lines.push({
        id: "structure-hold",
        price: hold.price,
        label: "Hold",
        tone: "muted",
        layer: "structure",
        from: hold.time,
        priority: 3,
      });
    }
  } else if (read.high && read.low) {
    lines.push(
      { id: "range-high", price: read.high.price, label: "High", tone: "muted", layer: "structure", from: read.high.time, priority: 3 },
      { id: "range-low", price: read.low.price, label: "Low", tone: "muted", layer: "structure", from: read.low.time, priority: 3 },
    );
  }

  const buySide = nearestResting(highs, candles, last.close, "high", unit);
  const sellSide = nearestResting(lows, candles, last.close, "low", unit);
  const pools: string[] = [];
  if (buySide && !near(lines, buySide.price, unit)) {
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
    pools.push(`BSL ${px(buySide.price)}`);
  }
  if (sellSide && !near(lines, sellSide.price, unit)) {
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
    pools.push(`SSL ${px(sellSide.price)}`);
  }

  const setup = findSetup(candles, highs, lows, unit, read.bias);
  if (setup) {
    const from = candles[setupOrigin(candles, setup)].time;
    lines.push(
      { id: "entry", price: setup.entry, label: "Entry", tone: "accent", layer: "levels", from, priority: 5, clickable: true },
      { id: "stop", price: setup.stop, label: "SL", tone: "bear", layer: "levels", from, priority: 4 },
      { id: "tp1", price: setup.tp1, label: "TP1", tone: "bull", layer: "levels", from, priority: 4 },
    );
    if (Math.abs(setup.tp2 - setup.tp1) > unit * 0.45) {
      lines.push({ id: "tp2", price: setup.tp2, label: "TP2", tone: "bull", layer: "levels", from, priority: 2 });
    }
    if (Math.abs(setup.tp3 - setup.tp2) > unit * 0.45) {
      lines.push({ id: "tp3", price: setup.tp3, label: "TP3", tone: "bull", layer: "levels", from, priority: 1 });
    }
  }

  const lookback = tf === "D1" ? 1 : tf === "H4" ? 6 : tf === "H1" ? 24 : 20;
  const reference = candles[Math.max(0, candles.length - 1 - lookback)].close;

  return {
    candles,
    lines,
    zone: setup && setup.status === "active"
      ? { id: "entry-zone", low: setup.entryLow, high: setup.entryHigh, from: candles[Math.max(0, candles.length - 8)].time }
      : null,
    setup,
    bias: read.bias,
    biasDetail: read.event ? `${read.event.name} ${read.event.direction.toLowerCase()}` : "No confirmed break",
    liquidityDetail: pools.join(" · ") || "No separate pool",
    structureDetail: read.event
      ? `${read.event.name} ${px(read.event.price)}`
      : read.high && read.low
        ? `Range ${px(read.low.price)} – ${px(read.high.price)}`
        : "No confirmed swing",
    entryDetail: setup ? (setup.status === "active" ? "Valid" : "Stop traded") : "No qualified sweep",
    breatheLow: last.low,
    breatheHigh: last.high,
    unit,
    digits: inst.digits,
    reference,
    rng: () => 0.5,
  };
}

function readStructure(candles: Candle[], swings: Swing[]) {
  let bias: Tape["bias"] = "Neutral";
  let high: Swing | null = null;
  let low: Swing | null = null;
  let event: StructureEvent | null = null;

  for (const swing of swings) {
    if (swing.kind === "high") {
      if (high && swing.price > high.price) {
        event = {
          name: bias === "Bearish" ? "CHoCH" : "BOS",
          direction: "Bullish",
          price: high.price,
          time: high.time,
        };
        bias = "Bullish";
      }
      high = swing;
    } else {
      if (low && swing.price < low.price) {
        event = {
          name: bias === "Bullish" ? "CHoCH" : "BOS",
          direction: "Bearish",
          price: low.price,
          time: low.time,
        };
        bias = "Bearish";
      }
      low = swing;
    }
  }

  const last = candles[candles.length - 1];
  if (high && last.close > high.price && last.time > high.time) {
    event = {
      name: bias === "Bearish" ? "CHoCH" : "BOS",
      direction: "Bullish",
      price: high.price,
      time: high.time,
    };
    bias = "Bullish";
  } else if (low && last.close < low.price && last.time > low.time) {
    event = {
      name: bias === "Bullish" ? "CHoCH" : "BOS",
      direction: "Bearish",
      price: low.price,
      time: low.time,
    };
    bias = "Bearish";
  }

  if (bias === "Neutral" && high && low) {
    bias = last.close >= (high.price + low.price) / 2 ? "Bullish" : "Bearish";
  }
  return { bias, event, high, low };
}

function nearestResting(swings: Swing[], candles: Candle[], close: number, side: "high" | "low", unit: number) {
  const resting = swings.filter((swing) => {
    const beyond = side === "high" ? swing.price > close + unit * 0.15 : swing.price < close - unit * 0.15;
    if (!beyond) return false;
    for (let index = swing.index + 1; index < candles.length; index += 1) {
      const bar = candles[index];
      if (side === "high" && bar.high >= swing.price) return false;
      if (side === "low" && bar.low <= swing.price) return false;
    }
    return true;
  });
  resting.sort((a, b) => Math.abs(a.price - close) - Math.abs(b.price - close));
  return resting[0];
}

function near(lines: LineSpec[], price: number, unit: number) {
  return lines.some((line) => Math.abs(line.price - price) <= unit * 0.35);
}

function findSetup(
  candles: Candle[],
  highs: Swing[],
  lows: Swing[],
  unit: number,
  bias: Tape["bias"],
): Setup | null {
  const from = Math.max(1, candles.length - 18);
  for (let index = candles.length - 1; index >= from; index -= 1) {
    const bar = candles[index];
    const body = Math.abs(bar.close - bar.open);
    if (body < unit * 0.35) continue;
    const poolLow = lows.filter((swing) => swing.index <= index - 3).at(-1);
    const poolHigh = highs.filter((swing) => swing.index <= index - 3).at(-1);
    const sweptLow = poolLow && bar.low < poolLow.price - unit * 0.08 && bar.close > poolLow.price;
    const sweptHigh = poolHigh && bar.high > poolHigh.price + unit * 0.08 && bar.close < poolHigh.price;
    if (!sweptLow && !sweptHigh) continue;
    const direction = sweptLow ? "long" : "short";
    const sweep = sweptLow ? bar.low : bar.high;
    const target = direction === "long" ? poolHigh?.price : poolLow?.price;
    const setup = buildSetup(direction, candles, index, sweep, target ?? null, unit, bias);
    const since = candles.length - 1 - index;
    const left = direction === "long" ? candles.at(-1)!.close > setup.entry + unit * 1.6 : candles.at(-1)!.close < setup.entry - unit * 1.6;
    if (setup.status === "active" && since > 14 && left) continue;
    return setup;
  }
  return null;
}

function buildSetup(
  direction: "long" | "short",
  candles: Candle[],
  index: number,
  sweep: number,
  target: number | null,
  unit: number,
  bias: Tape["bias"],
): Setup {
  const bar = candles[index];
  const up = direction === "long";
  const entry = bar.close;
  const stop = up ? sweep - unit * 0.12 : sweep + unit * 0.12;
  const risk = Math.abs(entry - stop);
  const external = target != null && (up ? target > entry + risk : target < entry - risk) ? target : null;
  const tp1 = external ?? (up ? entry + risk : entry - risk);
  const tp2 = up ? entry + risk * 2 : entry - risk * 2;
  const tp3 = up ? entry + risk * 3 : entry - risk * 3;
  const later = candles.slice(index + 1);
  const invalidated = later.some((item) => (up ? item.low <= stop : item.high >= stop));
  const aligned = (up && bias !== "Bearish") || (!up && bias !== "Bullish");
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
      { label: "Sweep of a swing at least three bars old", ok: true },
      { label: up ? "Sell-side reclaimed on the close" : "Buy-side reclaimed on the close", ok: true },
      { label: "Reclaim candle displaced at least a third of ATR", ok: true },
      { label: aligned ? "With the active structure" : "Counter to the active structure", ok: aligned },
      { label: "Stop still beyond the sweep wick", ok: !invalidated },
    ],
    invalidation: up ? "Price through the sweep low" : "Price through the sweep high",
    thesis: invalidated
      ? "The sweep reclaimed, then price traded the stop. The idea is finished."
      : `${up ? "Sell-side" : "Buy-side"} was swept and reclaimed with displacement. Entry is the reclaim close. The stop sits beyond the wick${external ? ", and the first objective is the opposing pool" : ""}.`,
  };
}

function setupOrigin(candles: Candle[], setup: Setup) {
  const match = candles.findIndex((candle) => candle.time === Number(setup.id.split("-")[1]));
  return match >= 0 ? match : Math.max(0, candles.length - 12);
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
      if (candles[index].high < candles[index - step].high || candles[index].high < candles[index + step].high) high = false;
      if (candles[index].low > candles[index - step].low || candles[index].low > candles[index + step].low) low = false;
    }
    if (high) swings.push({ kind: "high", time: candles[index].time, price: candles[index].high, index });
    else if (low) swings.push({ kind: "low", time: candles[index].time, price: candles[index].low, index });
  }
  return swings;
}

function separate(swings: Swing[], gap: number) {
  const out: Swing[] = [];
  for (const swing of swings) {
    let prev: Swing | undefined;
    for (let index = out.length - 1; index >= 0; index -= 1) {
      if (out[index].kind === swing.kind) {
        prev = out[index];
        break;
      }
    }
    if (!prev || Math.abs(swing.price - prev.price) >= gap) out.push(swing);
  }
  return out;
}
