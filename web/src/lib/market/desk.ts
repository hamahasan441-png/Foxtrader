import { create } from "zustand";
import { prime, stepAll } from "./cache";
import { INSTRUMENTS, TIMEFRAMES } from "./instruments";
import type { Layers, TimeframeId } from "./types";

export type MobilePane = "markets" | "analysis" | "blotter" | null;

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
  setSymbol: (symbol: string) => void;
  setTimeframe: (tf: TimeframeId) => void;
  toggleLayer: (key: keyof Layers) => void;
  setPaused: (paused: boolean) => void;
  setDetail: (open: boolean) => void;
  setSettings: (open: boolean) => void;
  setMobile: (pane: MobilePane) => void;
  setBlotterOpen: (open: boolean) => void;
  resetView: () => void;
  tick: () => void;
  hydrate: (saved: Saved) => void;
};

prime("H1");

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
  setSymbol: (symbol) => set({ symbol, mobile: null }),
  setTimeframe: (timeframe) => {
    prime(timeframe);
    set({ timeframe });
  },
  toggleLayer: (key) => set({ layers: { ...get().layers, [key]: !get().layers[key] } }),
  setPaused: (paused) => set({ paused }),
  setDetail: (detailOpen) => set({ detailOpen }),
  setSettings: (settingsOpen) => set({ settingsOpen }),
  setMobile: (mobile) => set({ mobile }),
  setBlotterOpen: (blotterOpen) => set({ blotterOpen }),
  resetView: () => set((state) => ({ viewNonce: state.viewNonce + 1 })),
  tick: () => {
    if (get().paused) return;
    stepAll();
    set((state) => ({ revision: state.revision + 1 }));
  },
  hydrate: (saved) => {
    const next: Partial<Desk> = {};
    if (saved.symbol && SYMBOLS.has(saved.symbol)) next.symbol = saved.symbol;
    if (saved.timeframe && TF_IDS.has(saved.timeframe)) {
      prime(saved.timeframe);
      next.timeframe = saved.timeframe;
    }
    if (saved.layers && typeof saved.layers === "object") {
      next.layers = { ...DEFAULT_LAYERS, ...saved.layers };
    }
    if (typeof saved.blotterOpen === "boolean") next.blotterOpen = saved.blotterOpen;
    set(next);
  },
}));
