import { ANCHOR, INSTRUMENTS, instrumentOf, timeframeOf } from "./instruments";
import { clamp, hashString, mulberry32, roundTo } from "./format";
import type { Candle, Instrument, LineSpec, Narrative, Setup, Tape, TimeframeId, ZoneSpec } from "./types";

const BARS = 220;

type YBar = { o: number; h: number; l: number; c: number };

type ShapeIdx = {
  bsl: number;
  bos: number;
  ssl1: number;
  ssl2: number;
  ssl3: number;
  induce: number;
  sweep: number;
  ob: number;
};

function ybar(o: number, c: number, h: number, l: number): YBar {
  return {
    o,
    c,
    h: Math.max(h, o, c, l),
    l: Math.min(l, o, c, h),
  };
}

function drift(
  from: number,
  to: number,
  n: number,
  rand: () => number,
  jitter: number,
  wick: number,
): YBar[] {
  const bars: YBar[] = [];
  let prev = from;
  for (let i = 0; i < n; i += 1) {
    const t = (i + 1) / n;
    const s = t * t * (3 - 2 * t);
    const close = from + (to - from) * s + (rand() - 0.5) * jitter;
    const open = prev;
    bars.push(ybar(open, close, Math.max(open, close) + rand() * wick, Math.min(open, close) - rand() * wick));
    prev = close;
  }
  return bars;
}

function buildDirectional(rand: () => number): { bars: YBar[]; idx: ShapeIdx } {
  const bars: YBar[] = [];
  const add = (chunk: YBar[]) => {
    bars.push(...chunk);
  };

  add(drift(4.2, 12.2, 67, rand, 0.2, 0.22));
  const bsl = bars.length - 1;

  add(drift(bars[bsl].c, 6.6, 28, rand, 0.16, 0.18));
  add(drift(bars[bars.length - 1].c, 7.4, 18, rand, 0.12, 0.14));
  const bos = bars.length - 1;

  add(drift(bars[bos].c, 0.7, 36, rand, 0.14, 0.16));
  const ssl3 = bars.length - 1;
  const ssl2 = ssl3 - 8;
  const ssl1 = ssl3 - 16;

  add(drift(bars[ssl3].c, 3.9, 16, rand, 0.1, 0.12));
  const induce = bars.length - 1;

  add(drift(bars[induce].c, 1.4, 8, rand, 0.08, 0.1));
  const sweep = bars.length;
  bars.push(ybar(2.2, 0.9, 2.35, -1.65));

  bars.push(ybar(0.9, 2.45, 2.6, 0.75));
  const ob = bars.length;
  bars.push(ybar(3.15, 2.22, 3.3, 2.12));

  add(drift(2.22, 7.9, 14, rand, 0.12, 0.14));
  const impulseTop = bars.length - 1;

  const remaining = BARS - bars.length;
  add(drift(bars[impulseTop].c, 2.58, Math.max(8, remaining), rand, 0.08, 0.1));
  if (bars.length > BARS) bars.length = BARS;
  while (bars.length < BARS) {
    const prev = bars[bars.length - 1];
    bars.push(ybar(prev.c, 2.58, 2.8, 2.35));
  }

  bars[bsl] = ybar(12.05, 11.55, 13.2, 11.35);
  bars[bos] = ybar(7.15, 7.32, 7.7, 7.02);

  for (const i of [ssl1, ssl2, ssl3]) {
    bars[i] = ybar(0.95, 0.48, 1.08, 0.02);
    for (const j of [i - 2, i - 1, i + 1, i + 2]) {
      if (j < bos || j >= induce || j === ssl1 || j === ssl2 || j === ssl3) continue;
      const b = bars[j];
      bars[j] = ybar(b.o, Math.max(b.c, 0.72), Math.max(b.h, 0.9), Math.max(b.l, 0.36));
    }
  }

  for (let i = bos + 1; i < sweep; i += 1) {
    if (i === ssl1 || i === ssl2 || i === ssl3 || i === induce) continue;
    const b = bars[i];
    const cap = i > induce ? 3.5 : 4.05;
    bars[i] = ybar(Math.min(b.o, cap), Math.min(Math.max(b.c, 0.4), cap), Math.min(b.h, cap), Math.max(b.l, 0.28));
  }

  bars[induce] = ybar(3.62, 4.02, 4.45, 3.48);
  bars[sweep] = ybar(2.15, 0.85, 2.32, -1.65);
  bars[ob] = ybar(3.15, 2.22, 3.28, 2.12);

  for (let i = ob + 1; i <= impulseTop; i += 1) {
    const prog = (i - ob) / Math.max(1, impulseTop - ob);
    const close = 2.3 + (7.9 - 2.3) * prog;
    bars[i] = ybar(i === ob + 1 ? 2.22 : bars[i - 1].c, close, close + 0.22, Math.max(1.9, close - 0.28));
  }
  bars[impulseTop] = ybar(7.35, 7.82, 8.15, 7.15);

  for (let i = impulseTop + 1; i < bars.length; i += 1) {
    const prog = (i - impulseTop) / Math.max(1, bars.length - 1 - impulseTop);
    const close = 7.82 + (2.58 - 7.82) * prog;
    const open = bars[i - 1].c;
    bars[i] = ybar(open, close, Math.max(open, close) + 0.12, Math.min(open, close) - 0.1);
  }

  for (let i = 0; i < bars.length; i += 1) {
    if (i === sweep) continue;
    const b = bars[i];
    if (b.l < 0.02) bars[i] = ybar(b.o, Math.max(b.c, 0.2), b.h, 0.02);
    if (i !== bsl && bars[i].h > 12.5) {
      const c = bars[i];
      bars[i] = ybar(Math.min(c.o, 12.2), Math.min(c.c, 12.1), 12.4, c.l);
    }
  }

  bars[bsl] = ybar(12.05, 11.55, 13.2, 11.35);
  bars[bos] = ybar(7.15, 7.32, 7.7, 7.02);
  bars[induce] = ybar(3.62, 4.02, 4.45, 3.48);
  bars[sweep] = ybar(2.15, 0.85, 2.32, -1.65);
  bars[ob] = ybar(3.15, 2.22, 3.28, 2.12);
  bars[bars.length - 1] = ybar(2.72, 2.58, 2.86, 2.34);

  return { bars, idx: { bsl, bos, ssl1, ssl2, ssl3, induce, sweep, ob } };
}

function invalidate(bars: YBar[]) {
  for (let k = 0; k < 8; k += 1) {
    const i = bars.length - 8 + k;
    const close = 2.3 - (k + 1) * 0.72;
    const open = bars[i - 1].c;
    bars[i] = ybar(open, close, Math.max(open, close) + 0.06, Math.min(open, close) - 0.08);
  }
}

function buildRange(rand: () => number): { bars: YBar[]; hi: number[]; lo: number[] } {
  const bars: YBar[] = [];
  let prev = 3;
  for (let i = 0; i < BARS; i += 1) {
    const wave = Math.sin(i / 11) * 2.15;
    const close = 3 + wave + (rand() - 0.5) * 0.12;
    bars.push(ybar(prev, close, Math.max(prev, close) + 0.1, Math.min(prev, close) - 0.1));
    prev = close;
  }
  const hi = [28, 97, 166];
  const lo = [63, 132, 201];
  for (const i of hi) bars[i] = ybar(5.35, 5.5, 6, 5.15);
  for (const i of lo) bars[i] = ybar(0.72, 0.42, 0.88, 0);
  for (let i = 0; i < bars.length; i += 1) {
    if (hi.includes(i) || lo.includes(i)) continue;
    const b = bars[i];
    bars[i] = ybar(clamp(b.o, 0.85, 5.25), clamp(b.c, 0.85, 5.25), Math.min(b.h, 5.5), Math.max(b.l, 0.32));
  }
  bars[BARS - 1] = ybar(3.12, 3.04, 3.26, 2.9);
  return { bars, hi, lo };
}

function toCandles(bars: YBar[], unit: number, anchor: number, digits: number, barSec: number): Candle[] {
  const lastY = bars[bars.length - 1].c;
  const start = ANCHOR - (bars.length - 1) * barSec;
  const px = (y: number) => roundTo(anchor + (y - lastY) * unit, digits);
  return bars.map((bar, i) => {
    const open = px(bar.o);
    const close = px(bar.c);
    const high = px(bar.h);
    const low = px(bar.l);
    return {
      time: start + i * barSec,
      open,
      close,
      high: Math.max(high, open, close, low),
      low: Math.min(low, open, close, high),
    };
  });
}

function mirrorCandles(candles: Candle[], digits: number): Candle[] {
  const pivot = candles[candles.length - 1].close;
  return candles.map((candle) => {
    const open = roundTo(2 * pivot - candle.open, digits);
    const close = roundTo(2 * pivot - candle.close, digits);
    let high = roundTo(2 * pivot - candle.low, digits);
    let low = roundTo(2 * pivot - candle.high, digits);
    if (high < low) [high, low] = [low, high];
    return {
      time: candle.time,
      open,
      close,
      high: Math.max(high, open, close),
      low: Math.min(low, open, close),
    };
  });
}

function rr(entry: number, stop: number, target: number, up: boolean): number {
  const risk = Math.abs(entry - stop);
  const reward = up ? target - entry : entry - target;
  if (risk <= 0 || reward <= 0) return 0;
  return reward / risk;
}

function describe(
  inst: Instrument,
  direction: "long" | "short",
  valid: boolean,
  stop: number,
): Pick<Setup, "checklist" | "invalidation" | "thesis"> {
  const swept =
    direction === "long"
      ? "Sell-side liquidity swept and reclaimed"
      : "Buy-side liquidity swept and reclaimed";
  const displacement =
    direction === "long"
      ? "Bullish displacement through inducement"
      : "Bearish displacement through inducement";
  const checklist = [
    { label: "Dealing range and external pool marked", ok: true },
    { label: "Inducement printed before the sweep", ok: true },
    { label: swept, ok: true },
    { label: displacement, ok: true },
    { label: "Entry is the unmitigated opposing close", ok: true },
    { label: "Stop sits beyond the sweep", ok: true },
    { label: "At least 2R to the external pool", ok: true },
    { label: "Sweep extreme still holds on a closing basis", ok: valid },
  ];
  const side = direction === "long" ? "sell-side" : "buy-side";
  const bias = direction === "long" ? "long" : "short";
  const thesis = valid
    ? `${inst.symbol} swept ${side} liquidity and displaced through inducement. The ${bias} is the unmitigated opposing close, valid while the sweep holds.`
    : `${inst.symbol} swept ${side} liquidity and displaced, then closed through the stop. The ${bias} is invalid until a new sweep forms.`;
  return {
    checklist,
    invalidation: `Close beyond ${stop.toLocaleString("en-US", {
      minimumFractionDigits: inst.digits,
      maximumFractionDigits: inst.digits,
    })}.`,
    thesis,
  };
}

function directionalTape(
  inst: Instrument,
  candles: Candle[],
  idx: ShapeIdx,
  narrative: Exclude<Narrative, "range">,
  stepRng: () => number,
): Tape {
  const up = narrative !== "short";
  const direction = up ? "long" : "short";
  const valid = narrative !== "invalid";
  const gap = Math.max(roundTo(inst.unit * 0.4, inst.digits), roundTo(2 * 10 ** -inst.digits, inst.digits));
  const sweep = up ? candles[idx.sweep].low : candles[idx.sweep].high;
  const stop = roundTo(up ? sweep - gap : sweep + gap, inst.digits);
  const ob = candles[idx.ob];
  const entryLow = roundTo(Math.min(ob.open, ob.close), inst.digits);
  const entryHigh = roundTo(Math.max(ob.open, ob.close), inst.digits);
  const entry = roundTo((entryLow + entryHigh) / 2, inst.digits);
  const bos = up ? candles[idx.bos].high : candles[idx.bos].low;
  const choch = up ? candles[idx.induce].high : candles[idx.induce].low;
  const target = up ? candles[idx.bsl].high : candles[idx.bsl].low;
  const tp1 = roundTo(bos, inst.digits);
  const tp2 = roundTo(target, inst.digits);
  const tp3 = roundTo(up ? entry + (tp2 - entry) * 1.4 : entry - (entry - tp2) * 1.4, inst.digits);
  const sweptPool = up
    ? Math.min(candles[idx.ssl1].low, candles[idx.ssl2].low, candles[idx.ssl3].low)
    : Math.max(candles[idx.ssl1].high, candles[idx.ssl2].high, candles[idx.ssl3].high);
  const origin = candles[idx.ob].time;
  const tone = up ? "bull" : "bear";
  const copy = describe(inst, direction, valid, stop);
  const setup: Setup = {
    id: `${inst.symbol}-lit`,
    direction,
    status: valid ? "active" : "invalidated",
    entry,
    entryLow,
    entryHigh,
    stop,
    tp1,
    tp2,
    tp3,
    sweep: roundTo(sweep, inst.digits),
    rr1: rr(entry, stop, tp1, up),
    rr2: rr(entry, stop, tp2, up),
    rr3: rr(entry, stop, tp3, up),
    ...copy,
  };

  const lines: LineSpec[] = [
    {
      id: "choch",
      price: roundTo(choch, inst.digits),
      label: "CHoCH",
      tone,
      layer: "structure",
      from: origin,
      priority: 2,
    },
    {
      id: "bos",
      price: tp1,
      label: "BOS",
      tone,
      layer: "structure",
      from: origin,
      priority: 2,
    },
    {
      id: "ssl",
      price: roundTo(up ? sweptPool : target, inst.digits),
      label: "SSL",
      tone: "muted",
      layer: "liquidity",
      from: candles[up ? idx.ssl1 : idx.bsl].time,
      priority: 1,
      dashed: true,
    },
    {
      id: "bsl",
      price: roundTo(up ? target : sweptPool, inst.digits),
      label: "BSL",
      tone: "muted",
      layer: "liquidity",
      from: candles[up ? idx.bsl : idx.ssl1].time,
      priority: 1,
      dashed: true,
    },
    {
      id: "entry",
      price: entry,
      label: "Entry",
      tone: "accent",
      layer: "levels",
      from: origin,
      priority: 3,
      clickable: true,
      dashed: !valid,
    },
    {
      id: "sl",
      price: stop,
      label: "SL",
      tone: "bear",
      layer: "levels",
      from: origin,
      priority: 3,
      dashed: !valid,
    },
    {
      id: "tp1",
      price: tp1,
      label: "TP1",
      tone,
      layer: "levels",
      from: origin,
      priority: 3,
      dashed: !valid,
    },
    {
      id: "tp2",
      price: tp2,
      label: "TP2",
      tone,
      layer: "levels",
      from: origin,
      priority: 3,
      dashed: !valid,
    },
    {
      id: "tp3",
      price: tp3,
      label: "TP3",
      tone,
      layer: "levels",
      from: origin,
      priority: 3,
      dashed: !valid,
    },
  ];

  const zone: ZoneSpec = {
    id: "entry-zone",
    low: entryLow,
    high: entryHigh,
    from: origin,
  };

  const last = candles[candles.length - 1].close;
  let breatheLow = Math.min(entryLow, entryHigh);
  let breatheHigh = Math.max(entryLow, entryHigh);
  if (!valid) {
    breatheLow = up ? Math.min(last, stop) - gap : stop + gap * 0.25;
    breatheHigh = up ? stop - gap * 0.2 : Math.max(last, stop) + gap;
    if (breatheLow > breatheHigh) [breatheLow, breatheHigh] = [breatheHigh, breatheLow];
  }

  return {
    candles,
    lines,
    zone,
    setup,
    bias: valid ? (up ? "Bullish" : "Bearish") : "Neutral",
    biasDetail: valid ? "CHoCH confirmed, price in the zone" : "Prior idea closed through the stop",
    liquidityDetail: up ? "SSL swept · BSL resting" : "BSL swept · SSL resting",
    structureDetail: up ? "Bullish CHoCH and BOS" : "Bearish CHoCH and BOS",
    entryDetail: valid ? "Valid" : "Invalid",
    breatheLow,
    breatheHigh,
    unit: inst.unit,
    digits: inst.digits,
    reference: candles[candles.length - 30].close,
    rng: stepRng,
  };
}

function rangeTape(
  inst: Instrument,
  candles: Candle[],
  hi: number[],
  lo: number[],
  stepRng: () => number,
): Tape {
  const bsl = Math.max(...hi.map((i) => candles[i].high));
  const ssl = Math.min(...lo.map((i) => candles[i].low));
  const lines: LineSpec[] = [
    {
      id: "ssl",
      price: roundTo(ssl, inst.digits),
      label: "SSL",
      tone: "muted",
      layer: "liquidity",
      from: candles[lo[0]].time,
      priority: 1,
      dashed: true,
    },
    {
      id: "bsl",
      price: roundTo(bsl, inst.digits),
      label: "BSL",
      tone: "muted",
      layer: "liquidity",
      from: candles[hi[0]].time,
      priority: 1,
      dashed: true,
    },
  ];
  const mid = (bsl + ssl) / 2;
  const span = (bsl - ssl) * 0.22;
  return {
    candles,
    lines,
    zone: null,
    setup: null,
    bias: "Neutral",
    biasDetail: "No displacement, range intact",
    liquidityDetail: "Both pools resting",
    structureDetail: "No fresh break",
    entryDetail: "Not armed",
    breatheLow: mid - span,
    breatheHigh: mid + span,
    unit: inst.unit,
    digits: inst.digits,
    reference: candles[candles.length - 30].close,
    rng: stepRng,
  };
}

export function buildTape(symbol: string, tf: TimeframeId): Tape {
  const inst = instrumentOf(symbol);
  const frame = timeframeOf(tf);
  const shapeRng = mulberry32(hashString(`${symbol}|${tf}|shape`));
  const stepRng = mulberry32(hashString(`${symbol}|${tf}|step`));

  if (inst.narrative === "range") {
    const shape = buildRange(shapeRng);
    const candles = toCandles(shape.bars, inst.unit, inst.price, inst.digits, frame.sec);
    return rangeTape(inst, candles, shape.hi, shape.lo, stepRng);
  }

  const shape = buildDirectional(shapeRng);
  if (inst.narrative === "invalid") invalidate(shape.bars);
  let candles = toCandles(shape.bars, inst.unit, inst.price, inst.digits, frame.sec);
  if (inst.narrative === "short") candles = mirrorCandles(candles, inst.digits);
  return directionalTape(inst, candles, shape.idx, inst.narrative, stepRng);
}

export function stepTape(tape: Tape) {
  const candle = tape.candles[tape.candles.length - 1];
  const mid = (tape.breatheLow + tape.breatheHigh) / 2;
  let next = candle.close + (tape.rng() - 0.5) * tape.unit * 0.18 + (mid - candle.close) * 0.2;
  next = clamp(next, tape.breatheLow, tape.breatheHigh);
  next = roundTo(next, tape.digits);
  candle.close = next;
  candle.high = Math.max(candle.open, candle.close, Math.min(candle.high, tape.breatheHigh));
  candle.low = Math.min(candle.open, candle.close, Math.max(candle.low, tape.breatheLow));
  candle.high = Math.max(candle.high, candle.open, candle.close);
  candle.low = Math.min(candle.low, candle.open, candle.close);
}

export function changeOf(tape: Tape): number {
  const candle = tape.candles[tape.candles.length - 1];
  if (!candle || !tape.reference) return 0;
  return ((candle.close - tape.reference) / tape.reference) * 100;
}

export function lastOf(tape: Tape): number | null {
  const candle = tape.candles[tape.candles.length - 1];
  return candle ? candle.close : null;
}

export function allSymbols(): string[] {
  return INSTRUMENTS.map((item) => item.symbol);
}
