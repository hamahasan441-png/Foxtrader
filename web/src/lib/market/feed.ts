import type { Candle, TimeframeId } from "./types";

export type MarketLoad = { candles: Candle[]; error: string | null };

export async function loadMarket(input: { data: { symbol: string; timeframe: TimeframeId } }): Promise<MarketLoad> {
  const params = new URLSearchParams({
    symbol: input.data.symbol,
    timeframe: input.data.timeframe,
  });
  const response = await fetch(`/api/market?${params.toString()}`);
  if (!response.ok) throw new Error("Dukascopy request failed");
  const body = (await response.json()) as Partial<MarketLoad> | null;
  if (!body || !Array.isArray(body.candles)) throw new Error("Dukascopy request failed");
  return { candles: body.candles, error: body.error ?? null };
}
