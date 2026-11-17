import type { Instrument } from "./types";

export function roundTo(n: number, digits: number): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

export function formatPx(n: number, digits: number): string {
  return n.toLocaleString("en-US", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function formatPct(n: number): string {
  const sign = n > 0 ? "+" : "";
  return `${sign}${n.toFixed(2)}%`;
}

export function formatRMultiple(r: number): string {
  if (!Number.isFinite(r) || r <= 0) return "—";
  return `1 : ${r.toFixed(1)}`;
}

export function formatSignedR(r: number): string {
  const sign = r > 0 ? "+" : "";
  return `${sign}${r.toFixed(1)}R`;
}

export function formatCompact(n: number): string {
  return n.toLocaleString("en-US", {
    maximumFractionDigits: n >= 1000 ? 0 : 2,
  });
}

export function formatUtc(unix: number): string {
  const d = new Date(unix * 1000);
  const mon = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][
    d.getUTCMonth()
  ];
  const dd = String(d.getUTCDate()).padStart(2, "0");
  const hh = String(d.getUTCHours()).padStart(2, "0");
  const mm = String(d.getUTCMinutes()).padStart(2, "0");
  return `${dd} ${mon} ${hh}:${mm}`;
}

export function formatClock(now: Date): string {
  const hh = String(now.getUTCHours()).padStart(2, "0");
  const mm = String(now.getUTCMinutes()).padStart(2, "0");
  const ss = String(now.getUTCSeconds()).padStart(2, "0");
  return `${hh}:${mm}:${ss} UTC`;
}

export function distanceLabel(inst: Instrument, a: number, b: number): string {
  const d = Math.abs(a - b);
  if (inst.cls === "fx" && inst.symbol.endsWith("JPY")) {
    return `${(d * 100).toFixed(1)} pips`;
  }
  if (inst.cls === "fx") return `${(d * 10000).toFixed(1)} pips`;
  return `${formatPx(d, inst.digits)} pts`;
}

export function hashString(value: string): number {
  let h = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export function mulberry32(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}
