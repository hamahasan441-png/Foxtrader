import type { Candle, LineSpec, Setup, Tape } from "./types";

type Side = "long" | "short";

type Asia = { high: number; low: number; from: number };

type Hit = { side: Side; extreme: number; swept: number };

type Mark = {
  index: number;
  time: number;
  side: Side;
  entry: number;
  stop: number;
  target: number;
  open: number;
  close: number;
  sweep: number;
  status: "open" | "win" | "loss" | "flat";
};

const BUFFER_PIPS = 0.8;
const MIN_BODY_ATR = 0.35;
const RR = 1.5;
const MAX_HOLD = 24;

/** Confirmed Asia Sweep Reclaim marks. Only closed bars are read. */
export function asiaSweepMarks(candles: Candle[], pip: number): Mark[] {
  const closed = closedBars(candles);
  const marks: Mark[] = [];
  const used = new Set<string>();
  for (let i = 16; i < closed.length; i += 1) {
    const bar = closed[i];
    const hour = utcHour(bar.time);
    if (hour < 7 || hour >= 16) continue;
    const asia = asiaBefore(closed, i);
    if (!asia) continue;
    const atr = atrAt(closed, i);
    const hit = classify(bar, asia, atr, pip);
    if (!hit) continue;
    const key = `${utcDay(bar.time)}:${hit.side}`;
    if (used.has(key)) continue;
    const buf = BUFFER_PIPS * pip;
    const entry = bar.close;
    const stop = hit.side === "short" ? hit.extreme + buf : hit.extreme - buf;
    const risk = Math.abs(entry - stop);
    if (risk < pip * 0.4 || (atr > 0 && risk > atr * 5)) continue;
    used.add(key);
    const target = hit.side === "short" ? entry - risk * RR : entry + risk * RR;
    const mark: Mark = {
      index: i,
      time: bar.time,
      side: hit.side,
      entry,
      stop,
      target,
      open: bar.open,
      close: bar.close,
      sweep: hit.swept,
      status: "open",
    };
    const last = Math.min(closed.length - 1, i + MAX_HOLD);
    for (let k = i + 1; k <= last; k += 1) {
      const next = closed[k];
      const hitStop = hit.side === "long" ? next.low <= stop : next.high >= stop;
      const hitTp = hit.side === "long" ? next.high >= target : next.low <= target;
      if (hitStop) {
        mark.status = "loss";
        break;
      }
      if (hitTp) {
        mark.status = "win";
        break;
      }
      if (k === i + MAX_HOLD) mark.status = "flat";
    }
    marks.push(mark);
  }
  return marks;
}

export function applyAsiaSweep(tape: Tape): Tape {
  const pip = pipFromDigits(tape.digits);
  const marks = asiaSweepMarks(tape.candles, pip);
  const latest = marks.at(-1) ?? null;
  const day = utcDay((latest ? latest.time : tape.candles[tape.candles.length - 1].time));
  const asia = asiaOfDay(closedBars(tape.candles), day);
  let lines = tape.lines.filter((line) => !line.id.startsWith("asia-"));
  if (asia) {
    lines.push(asiaLine("asia-high", asia.high, "Asia Hi", asia.from), asiaLine("asia-low", asia.low, "Asia Lo", asia.from));
  }
  if (!latest) {
    return {
      ...tape,
      lines,
      liquidityDetail: asia
        ? `${tape.liquidityDetail} · Asia ${asia.low.toFixed(tape.digits)}–${asia.high.toFixed(tape.digits)}`
        : tape.liquidityDetail,
    };
  }
  lines = lines.filter((line) => !["entry", "stop", "tp1", "tp2", "tp3"].includes(line.id));
  lines.push(
    { id: "entry", price: latest.entry, label: "Entry", tone: "accent", layer: "levels", from: latest.time, priority: 5, clickable: true },
    { id: "stop", price: latest.stop, label: "SL", tone: "bear", layer: "levels", from: latest.time, priority: 4 },
    {
      id: "tp1",
      price: latest.target,
      label: "TP",
      tone: latest.side === "long" ? "bull" : "bear",
      layer: "levels",
      from: latest.time,
      priority: 4,
    },
  );
  const up = latest.side === "long";
  const setup: Setup = {
    id: `asia-${latest.time}`,
    direction: latest.side,
    status: latest.status === "loss" ? "invalidated" : "active",
    entry: latest.entry,
    entryLow: Math.min(latest.open, latest.close),
    entryHigh: Math.max(latest.open, latest.close),
    stop: latest.stop,
    tp1: latest.target,
    tp2: latest.target,
    tp3: latest.target,
    sweep: latest.sweep,
    rr1: RR,
    rr2: RR,
    rr3: RR,
    checklist: [
      { label: "Asia range from closed 00:00–07:00 UTC bars", ok: true },
      { label: up ? "London/NY swept the Asia low and closed back inside" : "London/NY swept the Asia high and closed back inside", ok: true },
      { label: "Reclaim body is large enough versus ATR", ok: true },
      { label: "One signal on this side for the UTC day", ok: true },
      { label: "Stop still beyond the sweep wick", ok: latest.status !== "loss" },
    ],
    invalidation: up ? "Price through the sweep low" : "Price through the sweep high",
    thesis:
      latest.status === "loss"
        ? "Asia Sweep Reclaim confirmed, then price traded the stop. The marker itself does not move."
        : latest.status === "win"
          ? "Asia Sweep Reclaim reached the target. The entry was the reclaim close."
          : `Asia Sweep Reclaim ${up ? "long" : "short"}. Entry is the closed reclaim candle. The stop sits beyond the sweep wick, target is ${RR}R.`,
  };
  return {
    ...tape,
    lines,
    setup,
    zone: {
      id: "entry-zone",
      low: Math.min(latest.open, latest.close),
      high: Math.max(latest.open, latest.close),
      from: latest.time,
    },
    bias: up ? "Bullish" : "Bearish",
    biasDetail: "Asia range swept and reclaimed",
    liquidityDetail: `Asia Sweep Reclaim · ${up ? "long" : "short"}`,
    structureDetail: "Closed bar only · no repaint",
    entryDetail: latest.status === "loss" ? "Stop traded" : latest.status === "win" ? "Target traded" : "Valid",
  };
}

function asiaLine(id: string, price: number, label: string, from: number): LineSpec {
  return { id, price, label, tone: "accent", layer: "liquidity", from, priority: 3, dashed: true };
}

function pipFromDigits(digits: number) {
  if (digits >= 5) return 0.0001;
  if (digits === 3) return 0.01;
  if (digits === 2) return 0.1;
  if (digits <= 1) return 1;
  return 10 ** -digits;
}

function closedBars(candles: Candle[]) {
  if (candles.length < 2) return candles;
  const step = candles[candles.length - 1].time - candles[candles.length - 2].time;
  if (step <= 0) return candles;
  const last = candles[candles.length - 1];
  if (last.time + step > Math.floor(Date.now() / 1000)) return candles.slice(0, -1);
  return candles;
}

function utcHour(sec: number) {
  return new Date(sec * 1000).getUTCHours();
}

function utcDay(sec: number) {
  return new Date(sec * 1000).toISOString().slice(0, 10);
}

function atrAt(bars: Candle[], i: number, n = 14) {
  const from = Math.max(1, i - n);
  let sum = 0;
  let k = 0;
  for (let j = from; j < i; j += 1) {
    const prev = bars[j - 1].close;
    const bar = bars[j];
    sum += Math.max(bar.high - bar.low, Math.abs(bar.high - prev), Math.abs(bar.low - prev));
    k += 1;
  }
  return k ? sum / k : 0;
}

function asiaBefore(bars: Candle[], i: number): Asia | null {
  const day = utcDay(bars[i].time);
  let high = -Infinity;
  let low = Infinity;
  let count = 0;
  let from = bars[i].time;
  for (let j = i - 1; j >= 0; j -= 1) {
    const bar = bars[j];
    if (utcDay(bar.time) !== day) break;
    if (utcHour(bar.time) >= 7) continue;
    high = Math.max(high, bar.high);
    low = Math.min(low, bar.low);
    from = bar.time;
    count += 1;
  }
  if (count < 4 || !(high > low)) return null;
  return { high, low, from };
}

function asiaOfDay(bars: Candle[], day: string): Asia | null {
  let high = -Infinity;
  let low = Infinity;
  let count = 0;
  let from = 0;
  for (const bar of bars) {
    if (utcDay(bar.time) !== day || utcHour(bar.time) >= 7) continue;
    if (count === 0) from = bar.time;
    high = Math.max(high, bar.high);
    low = Math.min(low, bar.low);
    count += 1;
  }
  if (count < 4 || !(high > low)) return null;
  return { high, low, from };
}

function classify(bar: Candle, asia: Asia, atr: number, pip: number): Hit | null {
  if (asia.high - asia.low < pip * 4) return null;
  const buf = BUFFER_PIPS * pip;
  const body = Math.abs(bar.close - bar.open);
  if (!(atr > 0) || body < MIN_BODY_ATR * atr) return null;
  if (bar.high > asia.high + buf && bar.close < asia.high && bar.close < bar.open) {
    return { side: "short", extreme: bar.high, swept: asia.high };
  }
  if (bar.low < asia.low - buf && bar.close > asia.low && bar.close > bar.open) {
    return { side: "long", extreme: bar.low, swept: asia.low };
  }
  return null;
}
