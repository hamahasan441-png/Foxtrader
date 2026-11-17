import { INSTRUMENTS } from "./instruments";
import { buildTape, stepTape } from "./engine";
import type { Tape, TimeframeId } from "./types";

const cache = new Map<string, Tape>();

export function getTape(symbol: string, tf: TimeframeId): Tape {
  const key = `${symbol}|${tf}`;
  let tape = cache.get(key);
  if (!tape) {
    tape = buildTape(symbol, tf);
    cache.set(key, tape);
  }
  return tape;
}

export function prime(tf: TimeframeId) {
  for (const inst of INSTRUMENTS) getTape(inst.symbol, tf);
}

export function stepAll() {
  for (const tape of cache.values()) stepTape(tape);
}
