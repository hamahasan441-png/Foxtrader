// ============================================================================
// AGENT ORCHESTRATOR
// Coordinates all 10 AI agents, collects outputs, resolves conflicts,
// and feeds the Master Decision Engine.
// ============================================================================

import { Candle, Direction, Bias, Timeframe } from '../core/types';
import { TradingEventBus } from '../core/event-bus';
import {
  TradingAgent, AgentName, AgentContext, AgentOutput, AgentInsight,
  OrchestratorResult, AgentStatus,
} from './types';

export interface OrchestratorConfig {
  /** Run agents in parallel (Promise.all) or sequential */
  parallel: boolean;
  /** Minimum agents that must agree for a signal */
  minAgentConsensus: number;
  /** Minimum aggregate confidence to approve signal */
  minConfidence: number;
  /** Agent weight multipliers (override defaults) */
  agentWeights: Partial<Record<AgentName, number>>;
  /** Timeout per agent analysis (ms) */
  agentTimeoutMs: number;
}

const DEFAULT_CONFIG: OrchestratorConfig = {
  parallel: true,
  minAgentConsensus: 5,
  minConfidence: 60,
  agentWeights: {
    MARKET_STRUCTURE: 1.5,
    SMART_MONEY: 1.4,
    ICT: 1.3,
    LIT: 1.3,
    VOLUME: 1.0,
    TREND: 1.2,
    RISK: 1.1,
    NEWS: 0.8,
    PSYCHOLOGY: 0.7,
    STRATEGY: 1.5,
  },
  agentTimeoutMs: 5000,
};

export class AgentOrchestrator {
  private config: OrchestratorConfig;
  private eventBus?: TradingEventBus;
  private agents: Map<AgentName, TradingAgent> = new Map();
  private lastResult: OrchestratorResult | null = null;

  constructor(config: Partial<OrchestratorConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  // =========================================================================
  // AGENT REGISTRATION
  // =========================================================================

  registerAgent(agent: TradingAgent): void {
    this.agents.set(agent.name, agent);
  }

  unregisterAgent(name: AgentName): void {
    this.agents.delete(name);
  }

  getRegisteredAgents(): AgentName[] {
    return Array.from(this.agents.keys());
  }

  // =========================================================================
  // ANALYSIS ORCHESTRATION
  // =========================================================================

  /**
   * Run all registered agents on the given context and produce a combined result.
   * Agents can optionally see each other's outputs (inter-agent communication).
   */
  analyze(context: AgentContext): OrchestratorResult {
    const startTime = performance.now();
    const outputs = new Map<AgentName, AgentOutput>();

    // Phase 1: Run foundational agents first (they inform others)
    const phase1: AgentName[] = ['MARKET_STRUCTURE', 'VOLUME', 'TREND', 'NEWS'];
    const phase2: AgentName[] = ['SMART_MONEY', 'ICT', 'LIT', 'RISK', 'PSYCHOLOGY'];
    const phase3: AgentName[] = ['STRATEGY']; // Strategy runs last with all info

    for (const phase of [phase1, phase2, phase3]) {
      for (const name of phase) {
        const agent = this.agents.get(name);
        if (!agent) continue;

        // Provide previous outputs for inter-agent communication
        const enrichedContext: AgentContext = {
          ...context,
          previousOutputs: outputs,
        };

        try {
          const output = agent.analyze(enrichedContext);
          outputs.set(name, output);
        } catch (err) {
          outputs.set(name, this.errorOutput(name, err));
        }
      }
    }

    // Phase 4: Aggregate results
    const result = this.aggregateOutputs(outputs, context, startTime);
    this.lastResult = result;

    this.eventBus?.emit({ type: 'AGENTS_COMPLETE' as any, data: result });
    return result;
  }

  // =========================================================================
  // AGGREGATION LOGIC
  // =========================================================================

  private aggregateOutputs(
    outputs: Map<AgentName, AgentOutput>,
    context: AgentContext,
    startTime: number
  ): OrchestratorResult {
    let bullishScore = 0;
    let bearishScore = 0;
    let totalWeight = 0;
    let alignedInsights = 0;

    for (const [name, output] of outputs) {
      if (output.status === 'ERROR') continue;

      const weight = this.config.agentWeights[name] ?? 1.0;
      totalWeight += weight;

      const confidenceContribution = (output.confidence / 100) * weight;

      if (output.bias === 'BULLISH') {
        bullishScore += confidenceContribution;
      } else if (output.bias === 'BEARISH') {
        bearishScore += confidenceContribution;
      }
      // NEUTRAL agents don't vote

      // Count aligned insights per direction
      for (const insight of output.insights) {
        if (insight.direction === 'BULLISH') bullishScore += (insight.confidence / 100) * insight.weight * 0.1;
        else if (insight.direction === 'BEARISH') bearishScore += (insight.confidence / 100) * insight.weight * 0.1;
      }
    }

    // Determine aggregate bias
    const maxScore = Math.max(bullishScore, bearishScore);
    const aggregateBias: Bias = bullishScore > bearishScore * 1.15 ? 'BULLISH'
      : bearishScore > bullishScore * 1.15 ? 'BEARISH'
      : 'NEUTRAL';

    // Normalize confidence to 0-100
    const aggregateConfidence = totalWeight > 0
      ? Math.round((maxScore / totalWeight) * 100)
      : 0;

    // Count how many agents agree with the aggregate direction
    let agentConsensus = 0;
    const signalDir: Direction | null = aggregateBias === 'NEUTRAL' ? null
      : aggregateBias === 'BULLISH' ? 'BULLISH' : 'BEARISH';

    for (const output of outputs.values()) {
      if (output.bias === aggregateBias && output.confidence >= 50) {
        agentConsensus++;
      }
      // Count aligned insights
      for (const i of output.insights) {
        if (i.direction === signalDir) alignedInsights++;
      }
    }

    // Signal approval requires minimum consensus + confidence
    const signalApproved = signalDir !== null &&
      agentConsensus >= this.config.minAgentConsensus &&
      aggregateConfidence >= this.config.minConfidence;

    const totalProcessingMs = performance.now() - startTime;

    return {
      timestamp: Date.now(),
      symbol: context.symbol,
      timeframe: context.timeframe,
      agentOutputs: outputs,
      aggregateBias,
      aggregateConfidence,
      alignedInsightCount: alignedInsights,
      totalProcessingMs,
      signalApproved,
      signalDirection: signalApproved ? signalDir : null,
    };
  }

  // =========================================================================
  // UTILITIES
  // =========================================================================

  private errorOutput(name: AgentName, err: unknown): AgentOutput {
    return {
      agentName: name,
      status: 'ERROR',
      bias: 'NEUTRAL',
      confidence: 0,
      insights: [],
      narrative: `Agent ${name} failed: ${err}`,
      processingTimeMs: 0,
      timestamp: Date.now(),
    };
  }

  /** Get the last orchestration result */
  getLastResult(): OrchestratorResult | null { return this.lastResult; }

  /** Get a specific agent's last output */
  getAgentOutput(name: AgentName): AgentOutput | undefined {
    return this.lastResult?.agentOutputs.get(name);
  }

  /** Reset all agents */
  resetAll(): void {
    for (const agent of this.agents.values()) agent.reset();
    this.lastResult = null;
  }

  updateConfig(config: Partial<OrchestratorConfig>): void {
    this.config = { ...this.config, ...config };
  }
}
