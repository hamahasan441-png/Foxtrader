import { ANCHOR } from "./instruments";

export type Fill = {
  id: string;
  time: number;
  symbol: string;
  side: "Buy" | "Sell";
  entry: number;
  exit: number;
  r: number;
};

export const FILLS: readonly Fill[] = [
  { id: "f1", time: ANCHOR - 3600 * 5, symbol: "EURUSD", side: "Buy", entry: 1.0814, exit: 1.0869, r: 1.7 },
  { id: "f2", time: ANCHOR - 3600 * 9, symbol: "XAUUSD", side: "Buy", entry: 2651.2, exit: 2644, r: -1 },
  { id: "f3", time: ANCHOR - 3600 * 14, symbol: "NAS100", side: "Sell", entry: 20840, exit: 20710, r: 2.1 },
  { id: "f4", time: ANCHOR - 3600 * 22, symbol: "GBPUSD", side: "Sell", entry: 1.2785, exit: 1.2812, r: -1 },
  { id: "f5", time: ANCHOR - 3600 * 30, symbol: "USDJPY", side: "Buy", entry: 153.42, exit: 153.98, r: 1.4 },
  { id: "f6", time: ANCHOR - 3600 * 40, symbol: "BTCUSD", side: "Buy", entry: 94880, exit: 96120, r: 1.9 },
  { id: "f7", time: ANCHOR - 3600 * 52, symbol: "US30", side: "Buy", entry: 43210, exit: 43140, r: -1 },
  { id: "f8", time: ANCHOR - 3600 * 70, symbol: "ETHUSD", side: "Sell", entry: 3560, exit: 3494, r: 2.4 },
];

export function netR(): number {
  return FILLS.reduce((sum, fill) => sum + fill.r, 0);
}
