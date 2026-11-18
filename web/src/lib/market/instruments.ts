import type { Instrument, TimeframeId } from "./types";

export const ANCHOR = Math.floor(Date.UTC(2026, 10, 17, 10, 0, 0) / 1000);

export const INSTRUMENTS: readonly Instrument[] = [
  {
    symbol: "EURUSD",
    name: "Euro / US Dollar",
    venue: "FX",
    cls: "fx",
    price: 1.08642,
    digits: 5,
    unit: 0.00072,
    narrative: "long",
  },
  {
    symbol: "GBPUSD",
    name: "Sterling / US Dollar",
    venue: "FX",
    cls: "fx",
    price: 1.2718,
    digits: 5,
    unit: 0.00095,
    narrative: "short",
  },
  {
    symbol: "USDJPY",
    name: "US Dollar / Yen",
    venue: "FX",
    cls: "fx",
    price: 154.32,
    digits: 3,
    unit: 0.145,
    narrative: "range",
  },
  {
    symbol: "USDCHF",
    name: "US Dollar / Franc",
    venue: "FX",
    cls: "fx",
    price: 0,
    digits: 5,
    unit: 0.0007,
    narrative: "range",
  },
  {
    symbol: "XAUUSD",
    name: "Gold",
    venue: "Metal",
    cls: "metal",
    price: 2674.3,
    digits: 2,
    unit: 4.8,
    narrative: "long",
  },
  {
    symbol: "XAGUSD",
    name: "Silver",
    venue: "Metal",
    cls: "metal",
    price: 31.42,
    digits: 3,
    unit: 0.072,
    narrative: "invalid",
  },
  {
    symbol: "US30",
    name: "Wall Street 30",
    venue: "Index",
    cls: "cme",
    price: 43582,
    digits: 1,
    unit: 36,
    narrative: "long",
  },
  {
    symbol: "NAS100",
    name: "US Tech 100",
    venue: "Index",
    cls: "cme",
    price: 20738,
    digits: 1,
    unit: 28,
    narrative: "short",
  },
  {
    symbol: "SPX500",
    name: "US 500",
    venue: "Index",
    cls: "cme",
    price: 5919.4,
    digits: 2,
    unit: 4.6,
    narrative: "range",
  },
  {
    symbol: "GER40",
    name: "Germany 40",
    venue: "Index",
    cls: "cme",
    price: 0,
    digits: 1,
    unit: 18,
    narrative: "range",
  },
];

export const TIMEFRAMES: readonly { id: TimeframeId; label: string; sec: number }[] = [
  { id: "M1", label: "1m", sec: 60 },
  { id: "M5", label: "5m", sec: 300 },
  { id: "M15", label: "15m", sec: 900 },
  { id: "H1", label: "1H", sec: 3600 },
  { id: "H4", label: "4H", sec: 14400 },
  { id: "D1", label: "D", sec: 86400 },
];

export function instrumentOf(symbol: string): Instrument {
  const found = INSTRUMENTS.find((item) => item.symbol === symbol);
  return found ?? INSTRUMENTS[0];
}

export function timeframeOf(id: TimeframeId) {
  const found = TIMEFRAMES.find((item) => item.id === id);
  return found ?? TIMEFRAMES[3];
}

export type SessionState = { label: string; open: boolean };

export function sessionState(cls: Instrument["cls"], now: Date): SessionState {
  const day = now.getUTCDay();
  const mins = now.getUTCHours() * 60 + now.getUTCMinutes();
  if (cls === "crypto") return { label: "Open", open: true };

  if (cls === "fx" || cls === "metal") {
    const closed = day === 6 || (day === 0 && mins < 22 * 60) || (day === 5 && mins >= 22 * 60);
    return closed ? { label: "Closed", open: false } : { label: "Open", open: true };
  }

  const weekend = day === 6 || (day === 0 && mins < 23 * 60) || (day === 5 && mins >= 22 * 60);
  if (weekend) return { label: "Closed", open: false };
  if (mins >= 21 * 60 && mins < 22 * 60) return { label: "Halt", open: false };
  return { label: "Open", open: true };
}
