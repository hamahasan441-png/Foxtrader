import { create } from "zustand";
import { putTape, getTape } from "./cache";
import { loadMarket } from "./feed";
import { applyAsiaSweep } from "./asiaSweep";
import { INSTRUMENTS, TIMEFRAMES } from "./instruments";
import { tapeFromCandles } from "./structure";
import type { Layers, TimeframeId } from "./types";

export type MobilePane = "markets" | "analysis" | "blotter" | null;
export type FeedStatus = "loading" | "live" | "down";

const DEFAULT_LAYERS: Layers = {
  structure: true,
  liquidity: true,
  lit: true,
  levels: true,
  invalidated: false,
  grid: true,
};

const TF_IDS = new Set<string>(TIMEFRAMES.map((frame) => frame.id));
const SYMBOLS = new Set<string>(INSTRUMENTS.map((item) => item.symbol));

type Saved = Partial<Pick<Desk, "symbol" | "timeframe" | "layers" | "blotterOpen">>;

type Desk = {
  symbol: string;
  timeframe: TimeframeId;
  layers: Layers;
  paused: boolean;
  revision: number;
  viewNonce: number;
  detailOpen: boolean;
  settingsOpen: boolean;
  mobile: MobilePane;
  blotterOpen: boolean;
  feed: FeedStatus;
  feedNote: string | null;
  setSymbol: (symbol: string) => void;
  setTimeframe: (tf: TimeframeId) => void;
  toggleLayer: (key: keyof Layers) => void;
  setMeridian: (on: boolean) => void;
  setPaused: (paused: boolean) => void;
  setDetail: (open: boolean) => void;
  setSettings: (open: boolean) => void;
  setMobile: (pane: MobilePane) => void;
  setBlotterOpen: (open: boolean) => void;
  resetView: () => void;
  refresh: (symbol?: string, timeframe?: TimeframeId) => Promise<void>;
  hydrate: (saved: Saved) => void;
};

let request = 0;

async function pull(symbol: string, timeframe: TimeframeId) {
  const result = await withTimeout(loadMarket({ data: { symbol, timeframe } }), 25_000);
  if (result.candles.length > 0) {
    try {
      putTape(symbol, timeframe, applyAsiaSweep(tapeFromCandles(symbol, timeframe, result.candles)));
    } catch (error) {
      return {
        candles: [],
        error: error instanceof Error ? error.message : "Could not read the Dukascopy tape",
      };
    }
  }
  return result;
}

function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Dukascopy timed out")), ms);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error("Dukascopy request failed"));
      },
    );
  });
}

export const useDesk = create<Desk>((set, get) => ({
  symbol: "EURUSD",
  timeframe: "H1",
  layers: DEFAULT_LAYERS,
  paused: false,
  revision: 0,
  viewNonce: 0,
  detailOpen: false,
  settingsOpen: false,
  mobile: null,
  blotterOpen: false,
  feed: "loading",
  feedNote: null,
  setSymbol: (symbol) => set({ symbol, mobile: null }),
  setTimeframe: (timeframe) => set({ timeframe }),
  toggleLayer: (key) => set({ layers: { ...get().layers, [key]: !get().layers[key] } }),
  setMeridian: (on) =>
    set({
      layers: {
        ...get().layers,
        structure: on,
        liquidity: on,
        lit: on,
        levels: on,
      },
    }),
  setPaused: (paused) => set({ paused }),
  setDetail: (detailOpen) => set({ detailOpen }),
  setSettings: (settingsOpen) => set({ settingsOpen }),
  setMobile: (mobile) => set({ mobile }),
  setBlotterOpen: (blotterOpen) => set({ blotterOpen }),
  resetView: () => set((state) => ({ viewNonce: state.viewNonce + 1 })),
  refresh: async (symbol = get().symbol, timeframe = get().timeframe) => {
    const ticket = ++request;
    const known = Boolean(getTape(symbol, timeframe));
    if (!known) set({ feed: "loading", feedNote: null });
    try {
      const active = await pull(symbol, timeframe);
      if (ticket !== request) return;
      const live = active.candles.length > 0;
      set((state) => ({
        revision: state.revision + 1,
        feed: live ? "live" : known ? state.feed : "down",
        feedNote: live ? null : active.error,
      }));
    } catch (error) {
      if (ticket !== request) return;
      if (!getTape(symbol, timeframe)) {
        set({
          feed: "down",
          feedNote: error instanceof Error ? error.message : "Dukascopy request failed",
        });
      }
    }
    const rest = INSTRUMENTS.map((item) => item.symbol).filter(
      (item) => item !== symbol && !getTape(item, timeframe),
    );
    for (let index = 0; index < rest.length; index += 2) {
      if (ticket !== request) return;
      const batch = rest.slice(index, index + 2);
      await Promise.all(
        batch.map(async (item) => {
          try {
            const result = await pull(item, timeframe);
            if (ticket === request && result.candles.length > 0) {
              set((state) => ({ revision: state.revision + 1 }));
            }
          } catch {
            /* leave that row blank */
          }
        }),
      );
    }
  },
  hydrate: (saved) => {
    const next: Partial<Desk> = {};
    if (saved.symbol && SYMBOLS.has(saved.symbol)) next.symbol = saved.symbol;
    if (saved.timeframe && TF_IDS.has(saved.timeframe)) next.timeframe = saved.timeframe;
    if (saved.layers && typeof saved.layers === "object") {
      next.layers = { ...DEFAULT_LAYERS, ...saved.layers };
    }
    if (typeof saved.blotterOpen === "boolean") next.blotterOpen = saved.blotterOpen;
    set(next);
  },
}));
