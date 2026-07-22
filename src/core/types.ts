// ============================================================================
// INSTITUTIONAL TRADING PLATFORM - CORE TYPE DEFINITIONS
// ============================================================================

// --- Base Market Data Types ---

export interface Candle {
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  tickVolume?: number;
}

export interface Tick {
  timestamp: number;
  bid: number;
  ask: number;
  bidVolume?: number;
  askVolume?: number;
}

export type Timeframe =
  | 'TICK' | 'M1' | 'M3' | 'M5' | 'M15' | 'M30'
  | 'H1' | 'H4' | 'D1' | 'W1' | 'MN';

export type Direction = 'BULLISH' | 'BEARISH';
export type Bias = 'BULLISH' | 'BEARISH' | 'NEUTRAL';
export type StructureType = 'INTERNAL' | 'EXTERNAL' | 'SWING' | 'FRACTAL';

export interface PriceLevel {
  price: number;
  timestamp: number;
  strength: number; // 0-100
}

export interface PriceZone {
  high: number;
  low: number;
  startTime: number;
  endTime?: number;
  mitigated: boolean;
}

// --- Market Structure Types ---

export interface SwingPoint {
  type: 'HIGH' | 'LOW';
  price: number;
  timestamp: number;
  index: number;
  structureType: StructureType;
  significance: number; // 0-100
}

export interface StructureBreak {
  type: 'BOS' | 'CHOCH' | 'MSS' | 'IDM';
  direction: Direction;
  breakPrice: number;
  breakTimestamp: number;
  breakIndex: number;
  swingPoint: SwingPoint;
  structureType: StructureType;
  confirmed: boolean;
  confirmationCandle?: Candle;
}

export interface MarketStructure {
  currentBias: Bias;
  swingHighs: SwingPoint[];
  swingLows: SwingPoint[];
  structureBreaks: StructureBreak[];
  internalStructure: StructureBreak[];
  externalStructure: StructureBreak[];
  fractalStructure: StructureBreak[];
}

// --- Liquidity Types ---

export type LiquidityType =
  | 'BSL' // Buy Side Liquidity
  | 'SSL' // Sell Side Liquidity
  | 'EQH' // Equal Highs
  | 'EQL' // Equal Lows
  | 'POOL'
  | 'SWEEP'
  | 'ENGINEERED'
  | 'RESTING'
  | 'SESSION';

export interface LiquidityLevel {
  type: LiquidityType;
  price: number;
  startTimestamp: number;
  endTimestamp?: number;
  startIndex: number;
  endIndex?: number;
  touches: number;
  swept: boolean;
  sweepTimestamp?: number;
  sweepCandle?: Candle;
  strength: number; // 0-100
  session?: SessionType;
}

export interface LiquiditySweep {
  level: LiquidityLevel;
  sweepCandle: Candle;
  sweepIndex: number;
  sweepTimestamp: number;
  depth: number; // How far price went beyond the level
  recovered: boolean; // Did price return
  recoveryTime?: number;
}

export interface LiquidityPool {
  levels: LiquidityLevel[];
  zone: PriceZone;
  side: 'BUY' | 'SELL';
  totalLiquidity: number; // Estimated
}

// --- Order Block Types ---

export type OrderBlockType =
  | 'BULLISH_OB'
  | 'BEARISH_OB'
  | 'MITIGATION'
  | 'BREAKER'
  | 'REJECTION'
  | 'FLIP_ZONE';

export interface OrderBlock {
  type: OrderBlockType;
  direction: Direction;
  zone: PriceZone;
  originCandle: Candle;
  originIndex: number;
  timestamp: number;
  mitigated: boolean;
  mitigationTimestamp?: number;
  mitigationPercentage: number; // 0-100 how much has been filled
  tested: boolean;
  testCount: number;
  strength: number; // 0-100
  volumeProfile?: number;
  imbalanceRatio?: number;
}

// --- Fair Value Gap Types ---

export type FVGType =
  | 'FVG'
  | 'IFVG' // Inverse FVG
  | 'BPR' // Balanced Price Range
  | 'VI' // Volume Imbalance
  | 'LV'; // Liquidity Void

export interface FairValueGap {
  type: FVGType;
  direction: Direction;
  zone: PriceZone;
  timestamp: number;
  index: number;
  candles: [Candle, Candle, Candle]; // Three candles forming the gap
  fillPercentage: number; // 0-100
  filled: boolean;
  consequentialEncroachment: boolean;
  size: number; // In price units
  relativeSize: number; // Percentage of ATR
}

// --- ICT Concepts Types ---

export interface OTE {
  direction: Direction;
  fibLevels: {
    level: number; // 0.618, 0.705, 0.786
    price: number;
  }[];
  swingHigh: SwingPoint;
  swingLow: SwingPoint;
  zone: PriceZone;
  timestamp: number;
  confluences: string[];
}

export interface JudasSwing {
  direction: Direction; // Direction of the fake move
  trueDirection: Direction; // Actual intended direction
  sweepLevel: LiquidityLevel;
  timestamp: number;
  session: SessionType;
  magnitude: number;
}

export type KillZoneType = 'ASIAN' | 'LONDON_OPEN' | 'NY_OPEN' | 'LONDON_CLOSE' | 'NY_CLOSE';

export interface KillZone {
  type: KillZoneType;
  startTime: string; // HH:MM UTC
  endTime: string; // HH:MM UTC
  high: number;
  low: number;
  bias: Bias;
  timestamp: number;
}

export interface SilverBullet {
  session: 'LONDON' | 'NY_AM' | 'NY_PM';
  direction: Direction;
  fvg: FairValueGap;
  timestamp: number;
  window: { start: string; end: string };
}

export interface PowerOfThree {
  phase: 'ACCUMULATION' | 'MANIPULATION' | 'DISTRIBUTION';
  direction: Direction;
  accumRange: PriceZone;
  manipLevel: PriceLevel;
  distTarget: PriceLevel;
  timestamp: number;
  session: SessionType;
}

export interface TurtleSoup {
  direction: Direction;
  level: LiquidityLevel;
  sweepCandle: Candle;
  entryPrice: number;
  stopLoss: number;
  timestamp: number;
}

export interface OpeningGap {
  type: 'NDOG' | 'WOG'; // New Day / Weekly Opening Gap
  high: number;
  low: number;
  midpoint: number;
  filled: boolean;
  fillPercentage: number;
  timestamp: number;
}

export type PDAType = 'PREMIUM' | 'DISCOUNT';

export interface PremiumDiscount {
  equilibrium: number;
  premiumZone: PriceZone;
  discountZone: PriceZone;
  currentPosition: PDAType;
  percentageFromEquilibrium: number;
}

// --- LIT Concepts Types ---

export type LITSignalType =
  | 'LIQUIDITY_TRAP'
  | 'LIQUIDITY_INDUCEMENT'
  | 'LIQUIDITY_SHIFT'
  | 'LIQUIDITY_SWEEP'
  | 'INSTITUTIONAL_TRAP'
  | 'BOS_CONFIRMATION'
  | 'CHOCH_CONFIRMATION'
  | 'FVG_CONFIRMATION'
  | 'OB_CONFIRMATION'
  | 'ENTRY_CONFIRMATION'
  | 'TARGET_PROJECTION';

export interface LITSetup {
  type: LITSignalType;
  direction: Direction;
  timestamp: number;
  index: number;
  price: number;
  zone?: PriceZone;
  confidence: number; // 0-100
  confirmations: LITConfirmation[];
  entry?: number;
  stopLoss?: number;
  takeProfit?: number[];
  riskReward?: number;
  invalidationPrice?: number;
}

export interface LITConfirmation {
  type: LITSignalType;
  confirmed: boolean;
  timestamp: number;
  details: string;
}

// --- SMT Types ---

export interface SMTDivergence {
  symbol1: string;
  symbol2: string;
  direction: Direction;
  timestamp: number;
  timeframe: Timeframe;
  symbol1Action: 'HIGHER_HIGH' | 'LOWER_LOW' | 'EQUAL';
  symbol2Action: 'HIGHER_HIGH' | 'LOWER_LOW' | 'EQUAL';
  strength: number; // 0-100
  priceLevel1: number;
  priceLevel2: number;
}

export interface SMTScanResult {
  divergences: SMTDivergence[];
  correlationCoefficient: number;
  symbols: string[];
  timeframe: Timeframe;
  scanTimestamp: number;
}

// --- Session Types ---

export type SessionType = 'ASIAN' | 'LONDON' | 'NEW_YORK' | 'SYDNEY';

export interface TradingSession {
  type: SessionType;
  startHourUTC: number;
  endHourUTC: number;
  high: number;
  low: number;
  open: number;
  close: number;
  range: number;
  timestamp: number;
  isActive: boolean;
}

// --- Scanner Types ---

export type ScannerSignalType =
  | 'BOS' | 'CHOCH' | 'ORDER_BLOCK' | 'FVG' | 'SMT'
  | 'LIT_SETUP' | 'BREAKOUT' | 'REVERSAL' | 'TREND_CONTINUATION';

export interface ScannerAlert {
  id: string;
  type: ScannerSignalType;
  symbol: string;
  timeframe: Timeframe;
  direction: Direction;
  price: number;
  timestamp: number;
  confidence: number; // 0-100
  message: string;
  details: Record<string, unknown>;
  acknowledged: boolean;
}

// --- AI Assistant Types ---

export interface AIAnalysis {
  setupType: string;
  explanation: string;
  liquidityAnalysis: string;
  structureAnalysis: string;
  entry: number;
  stopLoss: number;
  takeProfit: number[];
  riskReward: number;
  confidenceScore: number; // 0-100
  probabilityScore: number; // 0-100
  reasoning: string[];
  confluences: string[];
  invalidation: string;
  warnings: string[];
}

// --- Template Types ---

export type TemplateType =
  | 'SCALP_1M' | 'SCALP_3M' | 'SCALP_5M'
  | 'INTRADAY_15M' | 'INTRADAY_30M'
  | 'SWING_1H' | 'SWING_4H'
  | 'DAILY' | 'WEEKLY' | 'LONG_TERM';

export interface TradingTemplate {
  name: string;
  type: TemplateType;
  timeframe: Timeframe;
  indicators: IndicatorConfig[];
  colors: ColorScheme;
  riskSettings: RiskSettings;
  alerts: AlertConfig[];
  drawingTools: string[];
  tradingRules: TradingRule[];
}

export interface IndicatorConfig {
  name: string;
  enabled: boolean;
  params: Record<string, number | string | boolean>;
}

export interface ColorScheme {
  background: string;
  candles: { bullish: string; bearish: string; wick: string };
  structure: { bos: string; choch: string; mss: string };
  liquidity: { bsl: string; ssl: string; sweep: string };
  orderBlocks: { bullish: string; bearish: string; breaker: string; mitigation: string };
  fvg: { bullish: string; bearish: string; ifvg: string };
  sessions: { asian: string; london: string; newYork: string; sydney: string };
  premium: string;
  discount: string;
  entry: string;
  stopLoss: string;
  takeProfit: string;
}

export interface RiskSettings {
  maxRiskPerTrade: number; // Percentage
  maxDailyLoss: number;
  maxDrawdown: number;
  defaultRiskReward: number;
  positionSizing: 'FIXED' | 'PERCENTAGE' | 'ATR_BASED';
  accountBalance?: number;
}

export interface AlertConfig {
  type: ScannerSignalType;
  enabled: boolean;
  sound: boolean;
  popup: boolean;
  minConfidence: number;
}

export interface TradingRule {
  name: string;
  description: string;
  condition: string;
  action: string;
  enabled: boolean;
}

// --- Chart Engine Types ---

export interface ChartConfig {
  width: number;
  height: number;
  timeframe: Timeframe;
  symbol: string;
  theme: 'dark' | 'light';
  showGrid: boolean;
  showVolume: boolean;
  annotations: ChartAnnotation[];
}

export interface ChartAnnotation {
  id: string;
  type: ChartAnnotationType;
  data: Record<string, unknown>;
  visible: boolean;
  interactive: boolean;
  zIndex: number;
}

export type ChartAnnotationType =
  | 'BOS_LINE' | 'CHOCH_LINE' | 'STRUCTURE_LINE'
  | 'ORDER_BLOCK_ZONE' | 'BREAKER_ZONE' | 'MITIGATION_ZONE'
  | 'FVG_ZONE' | 'IFVG_ZONE'
  | 'LIQUIDITY_LINE' | 'SWEEP_MARKER'
  | 'SMT_MARKER' | 'LIT_MARKER'
  | 'PREMIUM_ZONE' | 'DISCOUNT_ZONE'
  | 'KILL_ZONE' | 'OTE_ZONE'
  | 'ENTRY_LINE' | 'SL_LINE' | 'TP_LINE'
  | 'TARGET_ZONE' | 'INVALIDATION_LINE'
  | 'SESSION_BOX';

// --- Data Provider Types ---

export interface DataProviderConfig {
  provider: 'DUKASCOPY';
  symbol: string;
  timeframes: Timeframe[];
  cachePath: string;
  autoSync: boolean;
  syncInterval: number; // ms
}

export interface CachedData {
  symbol: string;
  timeframe: Timeframe;
  candles: Candle[];
  lastSync: number;
  startDate: number;
  endDate: number;
}

// --- Event System ---

// ============================================================================
// TYPE-SAFE EVENT MAP — Eliminates all 'as any' event casts
// Every event type maps to its exact payload type.
// Adding a new event: add one line here, get full type inference everywhere.
// ============================================================================

export interface EventMap {
  // Part 1 — Core Analysis
  STRUCTURE_BREAK: StructureBreak;
  LIQUIDITY_SWEEP: LiquiditySweep;
  ORDER_BLOCK_FORMED: OrderBlock;
  FVG_FORMED: FairValueGap;
  SMT_DIVERGENCE: SMTDivergence;
  LIT_SETUP: LITSetup;
  SCANNER_ALERT: ScannerAlert;
  AI_ANALYSIS: AIAnalysis;
  SESSION_CHANGE: TradingSession;
  NEW_CANDLE: { timeframe: Timeframe; candle: Candle };
  TICK: Tick;
  // Part 2 — Execution & Risk
  ORDER_UPDATE: Record<string, unknown>;
  POSITION_UPDATE: Record<string, unknown>;
  RISK_HALT: { reason: string; timestamp: number };
  JOURNAL_ENTRY: Record<string, unknown>;
  REPLAY_COMMENTARY: Record<string, unknown>;
  NEWS_RELEASE: Record<string, unknown>;
  VOICE_COMMAND: Record<string, unknown>;
  SYNC_COMPLETE: Record<string, unknown>;
  SYNC_STATUS: { status: string };
  SYNC_ONLINE: Record<string, unknown>;
  THEME_CHANGED: Record<string, unknown>;
  WORKSPACE_CHANGED: Record<string, unknown>;
  AUTH_SUCCESS: { method: string };
  SECURITY_THREAT: Record<string, unknown>;
  // Part 3 — Agents & Enterprise
  AGENTS_COMPLETE: Record<string, unknown>;
  MTF_ANALYSIS: Record<string, unknown>;
  ALERT_SENT: Record<string, unknown>;
}

/** All valid event type strings */
export type EventType = keyof EventMap;

/** A platform event: discriminated union built from EventMap */
export type PlatformEvent = {
  [K in EventType]: { type: K; data: EventMap[K] };
}[EventType];

/** Type-safe event bus interface */
export interface EventBus {
  emit<K extends EventType>(event: { type: K; data: EventMap[K] }): void;
  on<K extends EventType>(type: K, handler: (data: EventMap[K]) => void): () => void;
  off<K extends EventType>(type: K, handler: (data: EventMap[K]) => void): void;
}
