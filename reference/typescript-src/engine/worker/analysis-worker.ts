// ============================================================================
// ANALYSIS WEB WORKER
// Runs ALL heavy computation off the main thread:
// - Agent analysis (10 agents)
// - Indicator calculations (ATR, EMA, ADX, RSI, etc.)
// - Pattern detection (classic + harmonic + candle)
// - Backtesting
//
// Communication: structured messages with transferable ArrayBuffers
// for zero-copy candle data passing.
// ============================================================================

// This file is the WORKER entry point (runs in a separate thread).
// It receives candle data, runs analysis, and posts results back.

import type { Candle, Timeframe, Bias } from '../../core/types';

// ============================================================================
// MESSAGE TYPES (worker ↔ main thread protocol)
// ============================================================================

export type WorkerRequestType =
  | 'ANALYZE_STRUCTURE'
  | 'ANALYZE_INDICATORS'
  | 'DETECT_PATTERNS'
  | 'RUN_AGENTS'
  | 'BACKTEST'
  | 'CALCULATE_PROBABILITY';

export type WorkerResponseType =
  | 'STRUCTURE_RESULT'
  | 'INDICATORS_RESULT'
  | 'PATTERNS_RESULT'
  | 'AGENTS_RESULT'
  | 'BACKTEST_RESULT'
  | 'PROBABILITY_RESULT'
  | 'ERROR';

export interface WorkerRequest {
  id: string;          // Unique request ID for correlating responses
  type: WorkerRequestType;
  payload: {
    /** Candle data as flat Float64Array: [timestamp, open, high, low, close, volume, ...] */
    candleBuffer?: ArrayBuffer;
    candleCount?: number;
    symbol?: string;
    timeframe?: Timeframe;
    /** Additional params (agent config, backtest config, etc.) */
    params?: Record<string, unknown>;
  };
}

export interface WorkerResponse {
  id: string;
  type: WorkerResponseType;
  payload: Record<string, unknown>;
  processingTimeMs: number;
}

// ============================================================================
// CANDLE BUFFER FORMAT
// Float64Array: each candle = 6 doubles (48 bytes)
// [timestamp, open, high, low, close, volume, timestamp, open, ...]
// ============================================================================

const FLOATS_PER_CANDLE = 6;

function bufferToCandles(buffer: ArrayBuffer, count: number): Candle[] {
  const view = new Float64Array(buffer);
  const candles: Candle[] = new Array(count);
  for (let i = 0; i < count; i++) {
    const off = i * FLOATS_PER_CANDLE;
    candles[i] = {
      timestamp: view[off],
      open: view[off + 1],
      high: view[off + 2],
      low: view[off + 3],
      close: view[off + 4],
      volume: view[off + 5],
    };
  }
  return candles;
}

export function candlesToBuffer(candles: Candle[]): ArrayBuffer {
  const buffer = new ArrayBuffer(candles.length * FLOATS_PER_CANDLE * 8);
  const view = new Float64Array(buffer);
  for (let i = 0; i < candles.length; i++) {
    const off = i * FLOATS_PER_CANDLE;
    view[off] = candles[i].timestamp;
    view[off + 1] = candles[i].open;
    view[off + 2] = candles[i].high;
    view[off + 3] = candles[i].low;
    view[off + 4] = candles[i].close;
    view[off + 5] = candles[i].volume;
  }
  return buffer;
}

// ============================================================================
// WORKER MESSAGE HANDLER (runs inside the worker thread)
// ============================================================================

// Note: This code only executes when loaded as a Worker.
// In the main thread, it's imported only for the type definitions above.

const isWorkerContext = typeof self !== 'undefined' && typeof (self as unknown as { importScripts: unknown }).importScripts === 'function';

if (isWorkerContext) {
  self.onmessage = async (event: MessageEvent<WorkerRequest>) => {
    const { id, type, payload } = event.data;
    const start = performance.now();

    try {
      let result: Record<string, unknown> = {};

      // Deserialize candle data
      const candles = payload.candleBuffer && payload.candleCount
        ? bufferToCandles(payload.candleBuffer, payload.candleCount)
        : [];

      switch (type) {
        case 'ANALYZE_INDICATORS': {
          // Import dynamically to keep worker bundle lean
          const { calculateATR } = await import('../../core/utils');
          const { calculateEMA, calculateADX, calculateRSI, calculateMomentum, calculateVWAP }
            = await import('../../indicators/technical');

          result = {
            atr: calculateATR(candles, 14),
            ema20: calculateEMA(candles, 20),
            ema50: calculateEMA(candles, 50),
            ema200: calculateEMA(candles, 200),
            adx: calculateADX(candles),
            rsi: calculateRSI(candles),
            momentum: calculateMomentum(candles),
            vwap: calculateVWAP(candles),
          };
          break;
        }

        case 'ANALYZE_STRUCTURE': {
          const { MarketStructureAnalyzer } = await import('../../modules/market-structure');
          const analyzer = new MarketStructureAnalyzer();
          const structure = analyzer.analyze(candles);
          result = {
            bias: structure.currentBias,
            breaks: structure.structureBreaks.slice(-10),
            swingHighs: structure.swingHighs.slice(-5),
            swingLows: structure.swingLows.slice(-5),
          };
          break;
        }

        case 'DETECT_PATTERNS': {
          const { PatternScanner } = await import('../../patterns');
          const scanner = new PatternScanner();
          const patterns = scanner.scan(candles);
          result = { patterns: patterns.slice(0, 20) }; // Top 20 by confidence
          break;
        }

        case 'RUN_AGENTS': {
          // Full 10-agent orchestration
          const { AgentOrchestrator } = await import('../../agents/orchestrator');
          const { MarketStructureAgent } = await import('../../agents/impl/market-structure-agent');
          const { SmartMoneyAgent } = await import('../../agents/impl/smart-money-agent');
          const { ICTAgent } = await import('../../agents/impl/ict-agent');
          const { LITAgent } = await import('../../agents/impl/lit-agent');
          const { VolumeAgent } = await import('../../agents/impl/volume-agent');
          const { TrendAgent } = await import('../../agents/impl/trend-agent');
          const { RiskAgent } = await import('../../agents/impl/risk-agent');
          const { NewsAgent } = await import('../../agents/impl/news-agent');
          const { PsychologyAgent } = await import('../../agents/impl/psychology-agent');
          const { StrategyAgent } = await import('../../agents/impl/strategy-agent');

          const orchestrator = new AgentOrchestrator();
          orchestrator.registerAgent(new MarketStructureAgent());
          orchestrator.registerAgent(new SmartMoneyAgent());
          orchestrator.registerAgent(new ICTAgent());
          orchestrator.registerAgent(new LITAgent());
          orchestrator.registerAgent(new VolumeAgent());
          orchestrator.registerAgent(new TrendAgent());
          orchestrator.registerAgent(new RiskAgent());
          orchestrator.registerAgent(new NewsAgent());
          orchestrator.registerAgent(new PsychologyAgent());
          orchestrator.registerAgent(new StrategyAgent());

          const agentResult = orchestrator.analyze({
            symbol: payload.symbol || 'UNKNOWN',
            timeframe: payload.timeframe || 'M15',
            candles,
            currentPrice: candles.length > 0 ? candles[candles.length - 1].close : 0,
          });

          // Serialize the Map to a plain object for transfer
          const outputs: Record<string, unknown> = {};
          for (const [name, output] of agentResult.agentOutputs) {
            outputs[name] = output;
          }

          result = {
            aggregateBias: agentResult.aggregateBias,
            aggregateConfidence: agentResult.aggregateConfidence,
            signalApproved: agentResult.signalApproved,
            signalDirection: agentResult.signalDirection,
            alignedInsightCount: agentResult.alignedInsightCount,
            totalProcessingMs: agentResult.totalProcessingMs,
            agentOutputs: outputs,
          };
          break;
        }

        case 'CALCULATE_PROBABILITY': {
          const { ProbabilityEngine } = await import('../../ai/probability-engine');
          const engine = new ProbabilityEngine();
          const direction = (payload.params?.direction as 'BULLISH' | 'BEARISH') || 'BULLISH';
          const probResult = engine.calculate({
            candles,
            direction,
            currentPrice: candles.length > 0 ? candles[candles.length - 1].close : 0,
            structureBreaks: [],
            orderBlocks: [],
            fvgs: [],
            sweeps: [],
            liquidityLevels: [],
            smtDivergences: [],
            litSetups: [],
            htfBias: (payload.params?.htfBias as Bias) || 'NEUTRAL',
          });
          result = probResult;
          break;
        }

        default:
          result = { error: `Unknown request type: ${type}` };
      }

      const response: WorkerResponse = {
        id,
        type: `${type.replace('ANALYZE_', '').replace('RUN_', '').replace('DETECT_', '').replace('CALCULATE_', '')}_RESULT` as WorkerResponseType,
        payload: result,
        processingTimeMs: performance.now() - start,
      };

      (self as unknown as Worker).postMessage(response);

    } catch (err) {
      const response: WorkerResponse = {
        id,
        type: 'ERROR',
        payload: { error: String(err), stack: (err as Error)?.stack },
        processingTimeMs: performance.now() - start,
      };
      (self as unknown as Worker).postMessage(response);
    }
  };
}
