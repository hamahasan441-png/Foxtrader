import type { Tape, TimeframeId } from "./types";

const cache = new Map<string, Tape>();

export function getTape(symbol: string, tf: TimeframeId): Tape | null {
  return cache.get(`${symbol}|${tf}`) ?? null;
}

export function putTape(symbol: string, tf: TimeframeId, tape: Tape) {
  cache.set(`${symbol}|${tf}`, tape);
}
