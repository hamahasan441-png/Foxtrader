// ============================================================================
// MASTER DECISION ENGINE
// No single indicator may generate signals alone.
// Every signal requires confirmation from multiple modules.
//
// Required confluence for a valid signal:
//   Liquidity Sweep + BOS + FVG + Order Block + SMT + Session + HTF Bias +
//   Trend + Volume => Generate Buy/Sell Signal with Confidence Score
// ============================================================================

import { Direction, Bias } from '../core/types';
import { AgentOutput, AgentName, OrchestratorResult } from './types';

export type SignalGrade =
  | 'NO_SIGNAL'
  | 'WEAK'
  | 'MODERATE'
  | 'STRONG'
  | 'VERY_STRONG'
  | 'INSTITUTIONAL';

/** Required confluences that MUST be present for signal approval */
export type RequiredConfluence =
  | 'LIQUIDITY_SWEEP'
  | 'BOS_OR_CHOCH'
  | 'FVG'
  | 'ORDER_BLOCK'
  | 'SMT'
  | 'SESSION'
  | 'HTF_BIAS'
  | 'TREND'
  | 'VOLUME';


export interface DecisionConfig {
  /** Minimum required confluences to approve a signal (from the 9 above) */
  minRequiredConfluences: number;
  /** Minimum confidence score from the Strategy Agent */
  minStrategyConfidence: number;
  /** If Risk or Psychology agent blocks, override all */
  respectRiskBlock: boolean;
  /** Minimum total weighted score from all agents */
  minWeightedScore: number;
}

const DEFAULT_CONFIG: DecisionConfig = {
  minRequiredConfluences: 5, // At least 5 of 9 required confluences
  minStrategyConfidence: 55,
  respectRiskBlock: true,
  minWeightedScore: 50,
};

export interface DecisionResult {
  approved: boolean;
  direction: Direction | null;
  confidence: number;
  grade: SignalGrade;
  /** Which required confluences are present */
  confluencePresent: RequiredConfluence[];
  /** Which are missing */
  confluenceMissing: RequiredConfluence[];
  /** Blocking reasons (if not approved) */
  blockReasons: string[];
  /** Narrative explanation */
  explanation: string;
  timestamp: number;
}

export class MasterDecisionEngine {
  private config: DecisionConfig;

  constructor(config: Partial<DecisionConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  /**
   * Evaluate an orchestrator result and produce a final go/no-go decision.
   * This is the ULTIMATE gatekeeper — no trade without its approval.
   */
  evaluate(result: OrchestratorResult): DecisionResult {
    const { agentOutputs, aggregateConfidence, signalDirection } = result;

    // 1. Check Risk/Psychology blocks
    const blockReasons: string[] = [];
    if (this.config.respectRiskBlock) {
      const riskOut = agentOutputs.get('RISK');
      const psychOut = agentOutputs.get('PSYCHOLOGY');

      if (riskOut?.insights.some(i => i.tags.includes('BLOCK'))) {
        blockReasons.push(`Risk Agent BLOCKED: ${riskOut.insights.filter(i => i.tags.includes('BLOCK')).map(i => i.type).join(', ')}`);
      }
      if (psychOut?.insights.some(i => i.tags.includes('BLOCK'))) {
        blockReasons.push(`Psychology Agent BLOCKED: ${psychOut.insights.filter(i => i.tags.includes('BLOCK')).map(i => i.type).join(', ')}`);
      }
    }

    if (blockReasons.length > 0) {
      return {
        approved: false, direction: null, confidence: 0, grade: 'NO_SIGNAL',
        confluencePresent: [], confluenceMissing: this.allConfluences(),
        blockReasons,
        explanation: `DECISION: NO TRADE. ${blockReasons.join(' | ')}`,
        timestamp: Date.now(),
      };
    }

    // 2. If no direction from orchestrator, no signal
    if (!signalDirection) {
      return {
        approved: false, direction: null, confidence: aggregateConfidence, grade: 'NO_SIGNAL',
        confluencePresent: [], confluenceMissing: this.allConfluences(),
        blockReasons: ['No directional consensus from agents'],
        explanation: 'DECISION: No consensus — stand aside.',
        timestamp: Date.now(),
      };
    }

    // 3. Check required confluences
    const present: RequiredConfluence[] = [];
    const missing: RequiredConfluence[] = [];
    const allInsights = Array.from(agentOutputs.values()).flatMap(o => o.insights);
    const dirInsights = allInsights.filter(i => i.direction === signalDirection);

    // Check each required confluence
    this.checkConfluence('LIQUIDITY_SWEEP', dirInsights, present, missing, ['LIQUIDITY_SWEEP', 'SWEEP']);
    this.checkConfluence('BOS_OR_CHOCH', dirInsights, present, missing, ['BOS', 'CHOCH', 'MSS']);
    this.checkConfluence('FVG', dirInsights, present, missing, ['FVG', 'IFVG', 'BPR']);
    this.checkConfluence('ORDER_BLOCK', dirInsights, present, missing, ['BULLISH_OB', 'BEARISH_OB', 'BREAKER', 'MITIGATION', 'ORDER_BLOCK']);
    this.checkConfluence('SMT', dirInsights, present, missing, ['SMT']);

    // Session from ICT agent
    const ictOut = agentOutputs.get('ICT');
    if (ictOut?.insights.some(i => i.type === 'KILL_ZONE')) {
      present.push('SESSION');
    } else { missing.push('SESSION'); }

    // HTF Bias — check if Market Structure agent agrees with direction
    const structOut = agentOutputs.get('MARKET_STRUCTURE');
    if (structOut?.bias === signalDirection) { present.push('HTF_BIAS'); } else { missing.push('HTF_BIAS'); }

    // Trend — check trend agent alignment
    const trendOut = agentOutputs.get('TREND');
    if (trendOut?.bias === signalDirection) { present.push('TREND'); } else { missing.push('TREND'); }

    // Volume — check volume agent delta alignment
    const volOut = agentOutputs.get('VOLUME');
    const deltaInsight = volOut?.insights.find(i => i.type === 'DELTA');
    if (deltaInsight?.direction === signalDirection) { present.push('VOLUME'); } else { missing.push('VOLUME'); }

    // 4. Decision
    const meetsConfluence = present.length >= this.config.minRequiredConfluences;
    const meetsConfidence = aggregateConfidence >= this.config.minStrategyConfidence;
    const approved = meetsConfluence && meetsConfidence;

    if (!meetsConfluence) {
      blockReasons.push(`Only ${present.length}/${this.config.minRequiredConfluences} required confluences present. Missing: ${missing.join(', ')}`);
    }
    if (!meetsConfidence) {
      blockReasons.push(`Confidence ${aggregateConfidence}% below minimum ${this.config.minStrategyConfidence}%`);
    }

    const grade = this.gradeSignal(present.length, aggregateConfidence);

    return {
      approved, direction: approved ? signalDirection : null,
      confidence: aggregateConfidence, grade,
      confluencePresent: present, confluenceMissing: missing,
      blockReasons,
      explanation: approved
        ? `DECISION: ${signalDirection} APPROVED (${grade}) — ${present.length}/9 confluences, ${aggregateConfidence}% confidence.`
        : `DECISION: REJECTED — ${blockReasons.join('; ')}`,
      timestamp: Date.now(),
    };
  }

  private checkConfluence(
    name: RequiredConfluence,
    insights: { type: string; tags: string[] }[],
    present: RequiredConfluence[],
    missing: RequiredConfluence[],
    matchTypes: string[]
  ): void {
    const found = insights.some(i =>
      matchTypes.includes(i.type) || i.tags.some(t => matchTypes.includes(t))
    );
    if (found) present.push(name); else missing.push(name);
  }

  private gradeSignal(confluenceCount: number, confidence: number): SignalGrade {
    if (confluenceCount >= 8 && confidence >= 85) return 'INSTITUTIONAL';
    if (confluenceCount >= 7 && confidence >= 75) return 'VERY_STRONG';
    if (confluenceCount >= 6 && confidence >= 65) return 'STRONG';
    if (confluenceCount >= 5 && confidence >= 55) return 'MODERATE';
    if (confluenceCount >= 4) return 'WEAK';
    return 'NO_SIGNAL';
  }

  private allConfluences(): RequiredConfluence[] {
    return ['LIQUIDITY_SWEEP', 'BOS_OR_CHOCH', 'FVG', 'ORDER_BLOCK', 'SMT', 'SESSION', 'HTF_BIAS', 'TREND', 'VOLUME'];
  }

  updateConfig(config: Partial<DecisionConfig>): void {
    this.config = { ...this.config, ...config };
  }
}
