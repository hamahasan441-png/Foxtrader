package com.foxtrader.app.domain.model

/**
 * Multi-Agent AI reasoning models.
 *
 * FoxTrader's AI layer is a multi-agent, confluence-gated reasoning system:
 * independent single-concern agents each analyze the market and emit an
 * [AgentOutput]; the orchestrator aggregates them; the Master Decision Engine
 * gates the result on required-confluence count, confidence, and a risk /
 * psychology veto.
 *
 * These models are a native-Kotlin port of the reference `agents/types.ts` and
 * `master-decision-engine.ts`.
 *
 * DESIGN CONTRACT (see Engineering Bible Section 7):
 * - Deterministic core: the confluence math is pure and reproducible offline.
 * - Non-repainting: agents read only confirmed history (candles `[0..last]`).
 * - No single-agent signal: approval requires MULTIPLE aligned confluences.
 * - Risk/Psychology veto overrides everything.
 */

// ============================================================================
// AGENTS
// ============================================================================

/** The specialized reasoning agents. */
enum class AgentName {
    MARKET_STRUCTURE,
    SMART_MONEY,
    ICT,
    LIT,
    VOLUME,
    TREND,
    RISK,
    NEWS,
    PSYCHOLOGY,
    STRATEGY,
}

/** Lifecycle status of an agent analysis. */
enum class AgentStatus { IDLE, ANALYZING, COMPLETE, ERROR }

/** A rectangular price zone (e.g. an order block or FVG band). */
data class PriceZone(val high: Double, val low: Double)

/**
 * A single finding from an agent (e.g. a detected BOS, order block, or a
 * risk block). Insights are the atoms the decision engine inspects when
 * checking for required confluences.
 */
data class AgentInsight(
    val id: String,
    val agentName: AgentName,
    /** Machine-readable finding type, e.g. "BOS", "BULLISH_OB", "DELTA", "KILL_ZONE", "BLOCK". */
    val type: String,
    val direction: Direction?,
    /** Confidence in this specific insight, 0..100. */
    val confidence: Double,
    val price: Double? = null,
    val timestamp: Long = 0L,
    val barIndex: Int? = null,
    val zone: PriceZone? = null,
    /** Human-readable description. */
    val detail: String = "",
    /** How strongly this insight contributes to the aggregate signal. */
    val weight: Double = 1.0,
    /** Free-form tags used for confluence matching (e.g. "SWEEP", "BLOCK"). */
    val tags: List<String> = emptyList(),
)

/** The result an agent returns after analyzing an [AgentContext]. */
data class AgentOutput(
    val agentName: AgentName,
    val status: AgentStatus,
    val bias: Bias,
    /** Overall agent confidence, 0..100. */
    val confidence: Double,
    val insights: List<AgentInsight>,
    /** Summary narrative from this agent. */
    val narrative: String,
    val processingTimeMs: Long = 0L,
    val timestamp: Long = 0L,
)

/**
 * All market data + account/psychology context passed to every agent.
 * Optional fields are only needed by specific agents (RISK/PSYCHOLOGY/NEWS).
 */
data class AgentContext(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    /** Current market price; defaults to the last candle close. */
    val currentPrice: Double = candles.lastOrNull()?.close ?: 0.0,
    /** Optional higher-timeframe candles keyed by timeframe. */
    val mtfCandles: Map<Timeframe, List<Candle>> = emptyMap(),
    /** Outputs from agents that already ran this cycle (inter-agent communication). */
    val previousOutputs: Map<AgentName, AgentOutput> = emptyMap(),
    // --- Risk agent inputs ---
    val accountBalance: Double? = null,
    val openPositionCount: Int? = null,
    val dailyPnL: Double? = null,
    // --- Psychology agent inputs ---
    val recentTradeResults: List<Boolean> = emptyList(), // true = win, false = loss
    val tradeCountToday: Int = 0,
    // --- News agent inputs ---
    val minutesToHighImpactNews: Int? = null,
    val inNewsBlackout: Boolean = false,
)

/** The orchestrator's combined result across all agents. */
data class OrchestratorResult(
    val timestamp: Long,
    val symbol: String,
    val timeframe: Timeframe,
    val agentOutputs: Map<AgentName, AgentOutput>,
    val aggregateBias: Bias,
    /** Aggregate confidence, 0..100. */
    val aggregateConfidence: Double,
    /** Number of insights supporting the aggregate direction. */
    val alignedInsightCount: Int,
    /** How many agents agree with the aggregate direction (confidence >= 50). */
    val agentConsensus: Int,
    val totalProcessingMs: Long,
    /** Whether the combined signal passes the orchestrator threshold. */
    val signalApproved: Boolean,
    val signalDirection: Direction?,
)

// ============================================================================
// MASTER DECISION ENGINE
// ============================================================================

/** Grade of an approved signal, scaling with confluence count + confidence. */
enum class SignalGrade { NO_SIGNAL, WEAK, MODERATE, STRONG, VERY_STRONG, INSTITUTIONAL }

/** The nine institutional confluences the decision engine looks for. */
enum class RequiredConfluence {
    LIQUIDITY_SWEEP,
    BOS_OR_CHOCH,
    FVG,
    ORDER_BLOCK,
    SMT,
    SESSION,
    HTF_BIAS,
    TREND,
    VOLUME;

    companion object {
        fun all(): List<RequiredConfluence> = RequiredConfluence.entries.toList()
    }
}

/** Tunable thresholds for the Master Decision Engine. */
data class DecisionConfig(
    /** Minimum number of the 9 required confluences to approve a signal. */
    val minRequiredConfluences: Int = 5,
    /** Minimum aggregate confidence (0..100) to approve. */
    val minConfidence: Double = 55.0,
    /** If a Risk or Psychology agent blocks, override everything. */
    val respectRiskBlock: Boolean = true,
)

/** The final go/no-go decision — the ultimate gatekeeper before any trade. */
data class DecisionResult(
    val approved: Boolean,
    val direction: Direction?,
    val confidence: Double,
    val grade: SignalGrade,
    val confluencePresent: List<RequiredConfluence>,
    val confluenceMissing: List<RequiredConfluence>,
    /** Reasons a signal was blocked/rejected (empty when approved). */
    val blockReasons: List<String>,
    /** Which agent (if any) vetoed the decision. */
    val vetoedBy: AgentName? = null,
    /** Human-readable narrative explanation. */
    val explanation: String,
    val timestamp: Long,
)
