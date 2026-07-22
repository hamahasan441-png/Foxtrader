// ============================================================================
// ENHANCED REPLAY ENGINE with AI Commentary
// Replay: Tick, 1M, 5M, 15M, Daily | Pause/Forward/Backward | Speed Control
// AI narrates market structure events as they unfold during replay.
// No look-ahead: AI only comments on data visible up to the replay cursor.
// ============================================================================

import { Candle, Tick, Timeframe, Direction, Bias } from '../core/types';
import { TradingEventBus } from '../core/event-bus';

export type ReplayMode = 'TICK' | 'M1' | 'M5' | 'M15' | 'D1';
export type ReplayState = 'STOPPED' | 'PLAYING' | 'PAUSED';

export interface ReplayConfig {
  mode: ReplayMode;
  speed: number; // 0.25x to 100x
  aiCommentary: boolean;
  commentaryVerbosity: 'MINIMAL' | 'NORMAL' | 'DETAILED';
}

const DEFAULT_CONFIG: ReplayConfig = {
  mode: 'M15',
  speed: 1,
  aiCommentary: true,
  commentaryVerbosity: 'NORMAL',
};

export interface ReplayCommentary {
  timestamp: number;
  cursorIndex: number;
  type: 'STRUCTURE' | 'LIQUIDITY' | 'SETUP' | 'MOMENTUM' | 'SESSION' | 'GENERAL';
  message: string;
  importance: 'LOW' | 'MEDIUM' | 'HIGH';
}

/** Callback that provides AI analysis for the candles visible so far */
export type CommentaryProvider = (visibleCandles: Candle[], cursorIndex: number) => ReplayCommentary[];

export class ReplayEngine {
  private config: ReplayConfig;
  private eventBus?: TradingEventBus;

  private allCandles: Candle[] = [];
  private allTicks: Tick[] = [];
  private cursorIndex: number = 0;
  private tickCursorIndex: number = 0;
  private state: ReplayState = 'STOPPED';
  private timer: number = 0;

  private commentaryProvider?: CommentaryProvider;
  private commentaryLog: ReplayCommentary[] = [];
  private lastCommentaryIndex: number = -1;

  // Callbacks
  private onCandleCallback?: (candle: Candle, index: number) => void;
  private onTickCallback?: (tick: Tick) => void;
  private onCommentaryCallback?: (commentary: ReplayCommentary) => void;
  private onStateChangeCallback?: (state: ReplayState) => void;

  constructor(config: Partial<ReplayConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  // =========================================================================
  // DATA LOADING
  // =========================================================================

  loadCandles(candles: Candle[]): void {
    this.allCandles = [...candles].sort((a, b) => a.timestamp - b.timestamp);
    this.cursorIndex = 0;
  }

  loadTicks(ticks: Tick[]): void {
    this.allTicks = [...ticks].sort((a, b) => a.timestamp - b.timestamp);
    this.tickCursorIndex = 0;
  }

  setCommentaryProvider(provider: CommentaryProvider): void {
    this.commentaryProvider = provider;
  }

  // =========================================================================
  // PLAYBACK CONTROL
  // =========================================================================

  /**
   * Start replay from a given index (default: current cursor)
   */
  play(fromIndex?: number): void {
    if (fromIndex !== undefined) this.cursorIndex = fromIndex;
    this.state = 'PLAYING';
    this.notifyStateChange();
    this.scheduleNext();
  }

  pause(): void {
    this.state = 'PAUSED';
    this.clearTimer();
    this.notifyStateChange();
  }

  stop(): void {
    this.state = 'STOPPED';
    this.clearTimer();
    this.cursorIndex = 0;
    this.tickCursorIndex = 0;
    this.commentaryLog = [];
    this.lastCommentaryIndex = -1;
    this.notifyStateChange();
  }

  /**
   * Step forward one unit (candle or tick depending on mode)
   */
  stepForward(): void {
    if (this.config.mode === 'TICK') {
      this.advanceTick();
    } else {
      this.advanceCandle();
    }
  }

  /**
   * Step backward one unit
   */
  stepBackward(): void {
    if (this.config.mode === 'TICK') {
      if (this.tickCursorIndex > 0) {
        this.tickCursorIndex--;
        this.emitTickState();
      }
    } else {
      if (this.cursorIndex > 0) {
        this.cursorIndex--;
        this.emitCandleState();
      }
    }
  }

  /**
   * Jump to a specific index
   */
  seekTo(index: number): void {
    this.cursorIndex = Math.max(0, Math.min(index, this.allCandles.length - 1));
    this.emitCandleState();
  }

  /**
   * Jump to a specific timestamp
   */
  seekToTime(timestamp: number): void {
    const idx = this.allCandles.findIndex(c => c.timestamp >= timestamp);
    if (idx >= 0) this.seekTo(idx);
  }

  setSpeed(speed: number): void {
    this.config.speed = Math.max(0.25, Math.min(100, speed));
    if (this.state === 'PLAYING') {
      this.clearTimer();
      this.scheduleNext();
    }
  }

  setMode(mode: ReplayMode): void {
    this.config.mode = mode;
  }

  toggleCommentary(enabled: boolean): void {
    this.config.aiCommentary = enabled;
  }

  // =========================================================================
  // PLAYBACK ENGINE
  // =========================================================================

  private scheduleNext(): void {
    if (this.state !== 'PLAYING') return;

    // Base interval: 1000ms at 1x speed for candles, faster for ticks
    const baseInterval = this.config.mode === 'TICK' ? 100 : 1000;
    const interval = baseInterval / this.config.speed;

    this.timer = window.setTimeout(() => {
      this.stepForward();
      if (this.hasMore()) {
        this.scheduleNext();
      } else {
        this.state = 'STOPPED';
        this.notifyStateChange();
      }
    }, interval);
  }

  private advanceCandle(): void {
    if (this.cursorIndex >= this.allCandles.length) return;

    const candle = this.allCandles[this.cursorIndex];
    this.onCandleCallback?.(candle, this.cursorIndex);
    this.eventBus?.emit({ type: 'NEW_CANDLE', data: { timeframe: this.modeToTimeframe(), candle } });

    // Generate AI commentary on visible data only (no look-ahead)
    if (this.config.aiCommentary && this.commentaryProvider) {
      this.generateCommentary();
    }

    this.cursorIndex++;
  }

  private advanceTick(): void {
    if (this.tickCursorIndex >= this.allTicks.length) return;
    const tick = this.allTicks[this.tickCursorIndex];
    this.onTickCallback?.(tick);
    this.eventBus?.emit({ type: 'TICK', data: tick });
    this.tickCursorIndex++;
  }

  /**
   * Generate AI commentary using only candles up to the cursor
   */
  private generateCommentary(): void {
    if (!this.commentaryProvider) return;

    // CRITICAL: only pass visible candles (no future data)
    const visibleCandles = this.allCandles.slice(0, this.cursorIndex + 1);
    const commentaries = this.commentaryProvider(visibleCandles, this.cursorIndex);

    for (const commentary of commentaries) {
      // Deduplicate by cursor index + type
      if (commentary.cursorIndex > this.lastCommentaryIndex) {
        this.commentaryLog.push(commentary);
        this.onCommentaryCallback?.(commentary);
        this.eventBus?.emit({ type: 'REPLAY_COMMENTARY', data: commentary });
      }
    }
    this.lastCommentaryIndex = this.cursorIndex;
  }

  private emitCandleState(): void {
    const candle = this.allCandles[this.cursorIndex];
    if (candle) this.onCandleCallback?.(candle, this.cursorIndex);
  }

  private emitTickState(): void {
    const tick = this.allTicks[this.tickCursorIndex];
    if (tick) this.onTickCallback?.(tick);
  }

  private hasMore(): boolean {
    return this.config.mode === 'TICK'
      ? this.tickCursorIndex < this.allTicks.length
      : this.cursorIndex < this.allCandles.length;
  }

  private modeToTimeframe(): Timeframe {
    const map: Record<ReplayMode, Timeframe> = {
      'TICK': 'TICK', 'M1': 'M1', 'M5': 'M5', 'M15': 'M15', 'D1': 'D1',
    };
    return map[this.config.mode];
  }

  private clearTimer(): void {
    if (this.timer) { clearTimeout(this.timer); this.timer = 0; }
  }

  private notifyStateChange(): void {
    this.onStateChangeCallback?.(this.state);
  }

  // =========================================================================
  // CALLBACKS & GETTERS
  // =========================================================================

  onCandle(cb: (candle: Candle, index: number) => void): void { this.onCandleCallback = cb; }
  onTick(cb: (tick: Tick) => void): void { this.onTickCallback = cb; }
  onCommentary(cb: (commentary: ReplayCommentary) => void): void { this.onCommentaryCallback = cb; }
  onStateChange(cb: (state: ReplayState) => void): void { this.onStateChangeCallback = cb; }

  getState(): ReplayState { return this.state; }
  getCursorIndex(): number { return this.cursorIndex; }
  getProgress(): number {
    const total = this.config.mode === 'TICK' ? this.allTicks.length : this.allCandles.length;
    const cursor = this.config.mode === 'TICK' ? this.tickCursorIndex : this.cursorIndex;
    return total > 0 ? (cursor / total) * 100 : 0;
  }
  getCommentaryLog(): ReplayCommentary[] { return [...this.commentaryLog]; }
  getVisibleCandles(): Candle[] { return this.allCandles.slice(0, this.cursorIndex + 1); }
  getConfig(): ReplayConfig { return { ...this.config }; }

  destroy(): void {
    this.stop();
    this.allCandles = [];
    this.allTicks = [];
    this.commentaryLog = [];
  }
}

// ============================================================================
// AI COMMENTARY GENERATOR - Default provider
// Narrates structure breaks, sweeps, momentum shifts as they appear
// ============================================================================

import { calculateEMA, calculateRSI, calculateMomentum } from '../indicators/technical';
import { findSwingPoints, calculateATR } from '../core/utils';

export class ReplayCommentator {
  private lastReportedBreakIndex: number = -1;
  private lastBias: Bias = 'NEUTRAL';

  /**
   * Build a commentary provider function to feed into the replay engine
   */
  createProvider(): CommentaryProvider {
    return (candles: Candle[], cursorIndex: number): ReplayCommentary[] => {
      const commentaries: ReplayCommentary[] = [];
      if (candles.length < 20) return commentaries;

      const last = candles.length - 1;
      const current = candles[last];

      // Momentum / candle analysis
      const rsi = calculateRSI(candles);
      const momentum = calculateMomentum(candles);
      const ema20 = calculateEMA(candles, 20);
      const atr = calculateATR(candles, 14);

      // Detect strong candle (displacement)
      const body = Math.abs(current.close - current.open);
      const currentATR = atr[last] || 1;
      if (body > currentATR * 1.5) {
        const dir: Direction = current.close > current.open ? 'BULLISH' : 'BEARISH';
        commentaries.push({
          timestamp: current.timestamp, cursorIndex,
          type: 'MOMENTUM',
          message: `Strong ${dir === 'BULLISH' ? 'bullish' : 'bearish'} displacement candle - institutional participation likely. Watch for follow-through.`,
          importance: 'HIGH',
        });
      }

      // RSI extremes
      if (rsi[last] > 75) {
        commentaries.push({
          timestamp: current.timestamp, cursorIndex, type: 'MOMENTUM',
          message: `RSI overbought (${rsi[last].toFixed(0)}). Momentum stretched - potential for pullback or liquidity grab above.`,
          importance: 'MEDIUM',
        });
      } else if (rsi[last] < 25) {
        commentaries.push({
          timestamp: current.timestamp, cursorIndex, type: 'MOMENTUM',
          message: `RSI oversold (${rsi[last].toFixed(0)}). Selling exhausted - watch for reversal setups at demand.`,
          importance: 'MEDIUM',
        });
      }

      // Swing structure narration
      const swings = findSwingPoints(candles.slice(-50), 3, 3);
      if (swings.length >= 2) {
        const lastSwing = swings[swings.length - 1];
        if (lastSwing.index + (candles.length - 50) > this.lastReportedBreakIndex) {
          commentaries.push({
            timestamp: current.timestamp, cursorIndex, type: 'STRUCTURE',
            message: `New swing ${lastSwing.type.toLowerCase()} formed at ${lastSwing.price.toFixed(5)}. ${lastSwing.type === 'HIGH' ? 'Watch for BSL above' : 'SSL resting below'}.`,
            importance: 'LOW',
          });
          this.lastReportedBreakIndex = lastSwing.index + (candles.length - 50);
        }
      }

      // Price vs EMA trend context
      const aboveEMA = current.close > ema20[last];
      const bias: Bias = aboveEMA ? 'BULLISH' : 'BEARISH';
      if (bias !== this.lastBias) {
        commentaries.push({
          timestamp: current.timestamp, cursorIndex, type: 'STRUCTURE',
          message: `Price crossed ${aboveEMA ? 'above' : 'below'} EMA20 - short-term bias shifting ${bias.toLowerCase()}.`,
          importance: 'MEDIUM',
        });
        this.lastBias = bias;
      }

      return commentaries;
    };
  }

  reset(): void {
    this.lastReportedBreakIndex = -1;
    this.lastBias = 'NEUTRAL';
  }
}
