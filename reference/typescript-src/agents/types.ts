// ============================================================================
// MULTI-AI AGENT SYSTEM — Type Definitions
// 10 Specialized Agents with a central Orchestrator
// ============================================================================

import { Candle, Direction, Bias, Timeframe } from '../core/types';

/**
 * Every agent must produce an AgentOutput when analyzing market data.
 * The orchestrator collects all outputs and feeds them to the Master Decision Engine.
 */

export type AgentName =
  | 'MARKET_STRUCTURE'
  | 'SMART_MONEY'
  | 'ICT'
  | 'LIT'
  | 'VOLUME'
  | 'TREND'
  | 'RISK'
  | 'NEWS'
  | 'PSYCHOLOGY'
  | 'STRATEGY';

export type AgentStatus = 'IDLE' | 'ANALYZING' | 'COMPLETE' | 'ERROR';

/** A single insight/finding from an agent */
export interface AgentInsight {
  id: string;
  agentName: AgentName;
  type: string;           // e.g. 'BOS', 'ORDER_BLOCK', 'FOMO_DETECTED'
  direction: Direction | null;
  confidence: number;     // 0-100
  price?: number;
  timestamp: number;
  barIndex?: number;
  zone?: { high: number; low: number };
  detail: string;
  weight: number;         // How much this insight contributes to the final signal
  tags: string[];
}

/** The output every agent returns after analysis */
export interface AgentOutput {
  agentName: AgentName;
  status: AgentStatus;
  bias: Bias;
  confidence: number;     // Overall agent confidence 0-100
  insights: AgentInsight[];
  /** Summary narrative from this agent */
  narrative: string;
  /** Processing time in ms */
  processingTimeMs: number;
  timestamp: number;
}

/** Context passed to every agent — all available market data */
export interface AgentContext {
  symbol: string;
  timeframe: Timeframe;
  candles: Candle[];
  /** Multi-timeframe data (if available) */
  mtfCandles?: Map<Timeframe, Candle[]>;
  /** Current market price */
  currentPrice: number;
  /** Previous agent outputs (for inter-agent communication) */
  previousOutputs?: Map<AgentName, AgentOutput>;
  /** Account state for Risk Agent */
  accountBalance?: number;
  openPositionCount?: number;
  dailyPnL?: number;
  /** User psychology state for Psychology Agent */
  recentTradeResults?: ('WIN' | 'LOSS')[];
  tradeCountToday?: number;
  /** News context */
  minutesToHighImpactNews?: number;
  inNewsBlackout?: boolean;
}

/** Base interface every agent must implement */
export interface TradingAgent {
  readonly name: AgentName;
  readonly description: string;
  readonly version: string;

  /**
   * Analyze the given context and produce output.
   * MUST be non-repainting: only uses candles up to the last CLOSED bar.
   */
  analyze(context: AgentContext): AgentOutput;

  /** Reset internal state */
  reset(): void;
}

/** The orchestrator's combined result from all agents */
export interface OrchestratorResult {
  timestamp: number;
  symbol: string;
  timeframe: Timeframe;
  /** Individual agent outputs */
  agentOutputs: Map<AgentName, AgentOutput>;
  /** Aggregate bias from all agents */
  aggregateBias: Bias;
  /** Aggregate confidence 0-100 */
  aggregateConfidence: number;
  /** Total number of aligned insights (supporting aggregate direction) */
  alignedInsightCount: number;
  /** Total processing time across all agents */
  totalProcessingMs: number;
  /** Whether the combined signal passes the decision engine threshold */
  signalApproved: boolean;
  /** Final trade direction (null if no signal) */
  signalDirection: Direction | null;
}
