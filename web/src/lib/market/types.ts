export type TimeframeId = "M1" | "M5" | "M15" | "H1" | "H4" | "D1";

export type Narrative = "long" | "short" | "range" | "invalid";

export type AssetClass = "fx" | "metal" | "cme" | "crypto";

export type Instrument = {
  symbol: string;
  name: string;
  venue: string;
  cls: AssetClass;
  price: number;
  digits: number;
  unit: number;
  narrative: Narrative;
};

export type Candle = {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
};

export type Tone = "bull" | "bear" | "accent" | "muted";

export type LineLayer = "structure" | "liquidity" | "levels";

export type LineSpec = {
  id: string;
  price: number;
  label: string;
  tone: Tone;
  layer: LineLayer;
  from: number;
  priority: number;
  dashed?: boolean;
  clickable?: boolean;
};

export type ZoneSpec = {
  id: string;
  low: number;
  high: number;
  from: number;
};

export type CheckItem = { label: string; ok: boolean };

export type Setup = {
  id: string;
  direction: "long" | "short";
  status: "active" | "invalidated";
  entry: number;
  entryLow: number;
  entryHigh: number;
  stop: number;
  tp1: number;
  tp2: number;
  tp3: number;
  sweep: number;
  rr1: number;
  rr2: number;
  rr3: number;
  checklist: CheckItem[];
  invalidation: string;
  thesis: string;
};

export type Tape = {
  candles: Candle[];
  lines: LineSpec[];
  zone: ZoneSpec | null;
  setup: Setup | null;
  bias: "Bullish" | "Bearish" | "Neutral";
  biasDetail: string;
  liquidityDetail: string;
  structureDetail: string;
  entryDetail: string;
  breatheLow: number;
  breatheHigh: number;
  unit: number;
  digits: number;
  reference: number;
  rng: () => number;
};

export type Layers = {
  structure: boolean;
  liquidity: boolean;
  lit: boolean;
  levels: boolean;
  invalidated: boolean;
  grid: boolean;
};
