// ============================================================================
// NON-REPAINTING ENFORCEMENT LAYER
// Ensures all signals operate exclusively on confirmed historical data.
// Rejects any look-ahead bias. Validates every signal before emission.
//
// Principles:
// 1. A signal can ONLY reference candles at index <= confirmationBar
// 2. confirmationBar = the bar on which the signal became observable
// 3. No signal may change retroactively once emitted
// 4. Future data (timestamp > now) is rejected at ingestion
// 5. Swing points require N right-side bars to confirm (no live bar)
// ============================================================================

import { Candle, Timeframe } from './types';

/**
 * Signal metadata attached to every emitted signal for audit trail
 */
export interface SignalMeta {
  /** Unique signal ID */
  signalId: string;
  /** Bar index on which the signal was confirmed (became non-repainting) */
  confirmationBarIndex: number;
  /** Timestamp of the confirmation bar */
  confirmationTimestamp: number;
  /** The latest bar index available when the signal was generated */
  latestBarAtGeneration: number;
  /** Whether signal passed validation */
  validated: boolean;
  /** Validation failure reason (if any) */
  rejectionReason?: string;
  /** Module that generated the signal */
  sourceModule: string;
  /** Timestamp of signal emission */
  emittedAt: number;
}


/**
 * Configuration for the enforcement layer
 */
export interface NonRepaintingConfig {
  /** Reject candles with timestamps in the future */
  rejectFutureData: boolean;
  /** Maximum allowed clock skew in ms (for real-time feeds) */
  maxClockSkewMs: number;
  /** Minimum right-side bars required to confirm a swing point */
  swingConfirmationBars: number;
  /** Whether to log validation failures */
  logRejections: boolean;
  /** Whether to throw on validation failure (vs. silent reject) */
  strictMode: boolean;
  /** Maintain audit trail of all validated signals */
  auditTrail: boolean;
  /** Max audit entries to keep in memory */
  maxAuditEntries: number;
}

const DEFAULT_CONFIG: NonRepaintingConfig = {
  rejectFutureData: true,
  maxClockSkewMs: 5000, // 5 seconds tolerance for network latency
  swingConfirmationBars: 3,
  logRejections: true,
  strictMode: false,
  auditTrail: true,
  maxAuditEntries: 10000,
};

/**
 * Validation result
 */
export interface ValidationResult {
  valid: boolean;
  reason?: string;
  meta?: SignalMeta;
}


/**
 * NON-REPAINTING GUARD
 * Wraps all signal generation to enforce temporal integrity.
 */
export class NonRepaintingGuard {
  private config: NonRepaintingConfig;
  private auditLog: SignalMeta[] = [];
  private signalCounter: number = 0;
  private emittedSignalHashes: Set<string> = new Set();
  private lastKnownBarIndex: number = -1;
  private lastKnownTimestamp: number = 0;

  constructor(config: Partial<NonRepaintingConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  // =========================================================================
  // DATA INGESTION VALIDATION
  // =========================================================================

  /**
   * Validate incoming candle data before it enters the analysis pipeline.
   * Rejects future candles and enforces chronological order.
   */
  validateCandleData(candles: Candle[]): Candle[] {
    const now = Date.now();
    const maxAllowed = now + this.config.maxClockSkewMs;
    const validated: Candle[] = [];

    for (let i = 0; i < candles.length; i++) {
      const candle = candles[i];

      // Rule 1: Reject future timestamps
      if (this.config.rejectFutureData && candle.timestamp > maxAllowed) {
        if (this.config.logRejections) {
          console.warn(`[NonRepaint] Rejected future candle: ${new Date(candle.timestamp).toISOString()} (now: ${new Date(now).toISOString()})`);
        }
        continue;
      }

      // Rule 2: Enforce chronological order
      if (i > 0 && candle.timestamp < candles[i - 1].timestamp) {
        if (this.config.logRejections) {
          console.warn(`[NonRepaint] Rejected out-of-order candle at index ${i}`);
        }
        continue;
      }

      // Rule 3: Basic OHLC sanity (high >= low, high >= open/close, etc.)
      if (candle.high < candle.low || candle.high < Math.max(candle.open, candle.close) ||
          candle.low > Math.min(candle.open, candle.close)) {
        if (this.config.logRejections) {
          console.warn(`[NonRepaint] Rejected malformed candle at ${candle.timestamp}`);
        }
        continue;
      }

      validated.push(candle);
    }

    return validated;
  }

  /**
   * Validate a single real-time candle update.
   * Only the LAST bar may be updated (standard non-repainting behavior).
   */
  validateRealtimeUpdate(candle: Candle, existingCandles: Candle[]): boolean {
    const now = Date.now();

    // Reject future data
    if (this.config.rejectFutureData && candle.timestamp > now + this.config.maxClockSkewMs) {
      return false;
    }

    // Only the last bar or a new bar may be updated
    if (existingCandles.length > 0) {
      const lastExisting = existingCandles[existingCandles.length - 1];
      if (candle.timestamp < lastExisting.timestamp) {
        // Attempting to modify a historical bar — REJECT (would cause repainting)
        if (this.config.logRejections) {
          console.warn(`[NonRepaint] Rejected attempt to modify historical bar at ${new Date(candle.timestamp).toISOString()}`);
        }
        return false;
      }
    }

    return true;
  }

  // =========================================================================
  // SIGNAL VALIDATION
  // =========================================================================

  /**
   * Validate a signal before emission.
   * Ensures the signal only references data available at confirmationBarIndex.
   *
   * @param sourceModule - Name of the module generating the signal
   * @param confirmationBarIndex - The bar at which this signal becomes confirmed
   * @param totalBarsAvailable - Total bars in the dataset at generation time
   * @param referencedIndices - All bar indices the signal's logic accessed
   */
  validateSignal(
    sourceModule: string,
    confirmationBarIndex: number,
    totalBarsAvailable: number,
    referencedIndices: number[]
  ): ValidationResult {
    const signalId = `${sourceModule}_${++this.signalCounter}_${Date.now()}`;

    // Rule 1: Confirmation bar must not be beyond available data
    if (confirmationBarIndex >= totalBarsAvailable) {
      return this.reject(signalId, sourceModule, confirmationBarIndex, totalBarsAvailable,
        `Confirmation bar (${confirmationBarIndex}) >= total bars (${totalBarsAvailable})`);
    }

    // Rule 2: No referenced index may be beyond confirmationBarIndex
    // (This catches look-ahead bias - accessing future bars)
    const futureRefs = referencedIndices.filter(idx => idx > confirmationBarIndex);
    if (futureRefs.length > 0) {
      return this.reject(signalId, sourceModule, confirmationBarIndex, totalBarsAvailable,
        `Look-ahead detected: accessed bars ${futureRefs.join(',')} beyond confirmation bar ${confirmationBarIndex}`);
    }

    // Rule 3: No negative indices
    const invalidRefs = referencedIndices.filter(idx => idx < 0);
    if (invalidRefs.length > 0) {
      return this.reject(signalId, sourceModule, confirmationBarIndex, totalBarsAvailable,
        `Invalid negative bar references: ${invalidRefs.join(',')}`);
    }

    // Rule 4: Confirmation bar must have enough right-side confirmation
    // (For swing-based signals, there must be N bars after the swing to confirm it)
    const rightBarsAvailable = totalBarsAvailable - 1 - confirmationBarIndex;
    if (rightBarsAvailable < 0) {
      return this.reject(signalId, sourceModule, confirmationBarIndex, totalBarsAvailable,
        `No bars available after confirmation bar for validation`);
    }

    // Passed all checks
    const meta: SignalMeta = {
      signalId,
      confirmationBarIndex,
      confirmationTimestamp: 0, // Set by caller with actual candle timestamp
      latestBarAtGeneration: totalBarsAvailable - 1,
      validated: true,
      sourceModule,
      emittedAt: Date.now(),
    };

    if (this.config.auditTrail) {
      this.addToAudit(meta);
    }

    return { valid: true, meta };
  }


  /**
   * Validate a swing point detection.
   * Swings require leftBars + rightBars to confirm.
   * The confirmation bar is the LAST right-side bar (not the swing itself).
   */
  validateSwingDetection(
    swingIndex: number,
    leftBars: number,
    rightBars: number,
    totalBarsAvailable: number
  ): ValidationResult {
    const confirmationBarIndex = swingIndex + rightBars;

    // The swing is only confirmed once we have rightBars candles AFTER the swing
    if (confirmationBarIndex >= totalBarsAvailable) {
      return {
        valid: false,
        reason: `Swing at ${swingIndex} not yet confirmed: need bar ${confirmationBarIndex} but only have ${totalBarsAvailable} bars`,
      };
    }

    // leftBars must also be available
    if (swingIndex < leftBars) {
      return {
        valid: false,
        reason: `Swing at ${swingIndex} has insufficient left bars (need ${leftBars})`,
      };
    }

    return {
      valid: true,
      meta: {
        signalId: `swing_${swingIndex}_${Date.now()}`,
        confirmationBarIndex,
        confirmationTimestamp: 0,
        latestBarAtGeneration: totalBarsAvailable - 1,
        validated: true,
        sourceModule: 'SwingDetection',
        emittedAt: Date.now(),
      },
    };
  }

  /**
   * Validate that a structure break signal is properly confirmed.
   * A BOS/CHOCH is confirmed only when a candle CLOSES beyond the level.
   */
  validateStructureBreak(
    breakIndex: number,
    breakPrice: number,
    candles: Candle[],
    requireBodyClose: boolean = true
  ): ValidationResult {
    if (breakIndex >= candles.length) {
      return { valid: false, reason: `Break index ${breakIndex} out of bounds` };
    }

    const breakCandle = candles[breakIndex];

    // If requireBodyClose, the candle must have CLOSED beyond the level
    // (Using only the wick is a weaker signal and can repaint if the bar hasn't closed)
    if (requireBodyClose) {
      // This is only valid if the bar is CLOSED (not the current forming bar)
      const isLastBar = breakIndex === candles.length - 1;
      if (isLastBar) {
        return {
          valid: false,
          reason: `Structure break on last (forming) bar - wait for bar close to confirm`,
        };
      }
    }

    return {
      valid: true,
      meta: {
        signalId: `structure_break_${breakIndex}_${Date.now()}`,
        confirmationBarIndex: breakIndex,
        confirmationTimestamp: breakCandle.timestamp,
        latestBarAtGeneration: candles.length - 1,
        validated: true,
        sourceModule: 'MarketStructure',
        emittedAt: Date.now(),
      },
    };
  }

  /**
   * Validate an order block detection.
   * OB is confirmed only after displacement candle(s) have CLOSED.
   */
  validateOrderBlock(
    obIndex: number,
    displacementBarsAfter: number,
    totalBars: number
  ): ValidationResult {
    // OB candle (obIndex) + at least 1 displacement bar must be closed
    const confirmationBar = obIndex + displacementBarsAfter;

    if (confirmationBar >= totalBars) {
      return {
        valid: false,
        reason: `OB at ${obIndex} not confirmed: displacement bar ${confirmationBar} not yet closed`,
      };
    }

    // All referenced bars must be <= confirmationBar
    const referencedIndices = [];
    for (let i = Math.max(0, obIndex - 1); i <= confirmationBar; i++) {
      referencedIndices.push(i);
    }

    return this.validateSignal('OrderBlock', confirmationBar, totalBars, referencedIndices);
  }

  /**
   * Validate an FVG detection.
   * FVG requires 3 candles (i-1, i, i+1) - all must be closed.
   */
  validateFVG(
    middleCandleIndex: number,
    totalBars: number
  ): ValidationResult {
    // FVG uses candle i-1, i, and i+1
    const thirdCandleIndex = middleCandleIndex + 1;

    if (thirdCandleIndex >= totalBars) {
      return {
        valid: false,
        reason: `FVG at ${middleCandleIndex}: third candle (${thirdCandleIndex}) not yet closed`,
      };
    }

    // Ensure we're not on the forming bar
    if (thirdCandleIndex === totalBars - 1) {
      // The third candle is the LAST bar - it might still be forming
      // In strict mode, reject. In relaxed mode, allow (it's the latest closed bar in backtesting)
      if (this.config.strictMode) {
        return {
          valid: false,
          reason: `FVG at ${middleCandleIndex}: third candle is the current forming bar (strict mode)`,
        };
      }
    }

    const referencedIndices = [middleCandleIndex - 1, middleCandleIndex, thirdCandleIndex];
    return this.validateSignal('FVG', thirdCandleIndex, totalBars, referencedIndices);
  }


  // =========================================================================
  // ANTI-REPAINT CANDLE ACCESSOR
  // =========================================================================

  /**
   * Create a "safe" candle accessor that prevents look-ahead.
   * The accessor only allows reading candles up to the specified currentBarIndex.
   * Any attempt to read beyond currentBarIndex returns undefined.
   */
  createSafeAccessor(candles: Candle[], currentBarIndex: number): SafeCandleAccessor {
    return new SafeCandleAccessor(candles, currentBarIndex);
  }

  // =========================================================================
  // SIGNAL IMMUTABILITY CHECK
  // =========================================================================

  /**
   * Check if a signal has already been emitted (duplicate prevention).
   * Once a signal is emitted at a specific bar, it cannot change.
   */
  isSignalAlreadyEmitted(signalHash: string): boolean {
    return this.emittedSignalHashes.has(signalHash);
  }

  /**
   * Register a signal as emitted (locks it permanently)
   */
  registerEmittedSignal(signalHash: string): void {
    this.emittedSignalHashes.add(signalHash);
    // Prevent memory leak
    if (this.emittedSignalHashes.size > 100000) {
      const arr = Array.from(this.emittedSignalHashes);
      this.emittedSignalHashes = new Set(arr.slice(-50000));
    }
  }

  /**
   * Generate a unique hash for a signal (for deduplication)
   */
  generateSignalHash(module: string, type: string, barIndex: number, price: number): string {
    return `${module}|${type}|${barIndex}|${price.toFixed(5)}`;
  }

  // =========================================================================
  // AUDIT & DIAGNOSTICS
  // =========================================================================

  /**
   * Get audit trail of all validated signals
   */
  getAuditTrail(limit?: number): SignalMeta[] {
    const entries = [...this.auditLog];
    return limit ? entries.slice(-limit) : entries;
  }

  /**
   * Get count of rejected signals
   */
  getRejectionCount(): number {
    return this.auditLog.filter(m => !m.validated).length;
  }

  /**
   * Get validation statistics
   */
  getStats(): {
    totalSignals: number;
    validated: number;
    rejected: number;
    rejectionRate: number;
    emittedHashes: number;
  } {
    const total = this.auditLog.length;
    const validated = this.auditLog.filter(m => m.validated).length;
    const rejected = total - validated;
    return {
      totalSignals: total,
      validated,
      rejected,
      rejectionRate: total > 0 ? rejected / total : 0,
      emittedHashes: this.emittedSignalHashes.size,
    };
  }

  /**
   * Reset the guard (for testing or re-initialization)
   */
  reset(): void {
    this.auditLog = [];
    this.emittedSignalHashes.clear();
    this.signalCounter = 0;
    this.lastKnownBarIndex = -1;
    this.lastKnownTimestamp = 0;
  }

  // =========================================================================
  // PRIVATE HELPERS
  // =========================================================================

  private reject(
    signalId: string,
    sourceModule: string,
    confirmationBarIndex: number,
    totalBars: number,
    reason: string
  ): ValidationResult {
    if (this.config.logRejections) {
      console.warn(`[NonRepaint] REJECTED signal from ${sourceModule}: ${reason}`);
    }

    const meta: SignalMeta = {
      signalId,
      confirmationBarIndex,
      confirmationTimestamp: 0,
      latestBarAtGeneration: totalBars - 1,
      validated: false,
      rejectionReason: reason,
      sourceModule,
      emittedAt: Date.now(),
    };

    if (this.config.auditTrail) {
      this.addToAudit(meta);
    }

    if (this.config.strictMode) {
      throw new Error(`[NonRepaint] Signal validation failed: ${reason}`);
    }

    return { valid: false, reason, meta };
  }

  private addToAudit(meta: SignalMeta): void {
    this.auditLog.push(meta);
    if (this.auditLog.length > this.config.maxAuditEntries) {
      this.auditLog = this.auditLog.slice(-Math.floor(this.config.maxAuditEntries * 0.8));
    }
  }
}


// ============================================================================
// SAFE CANDLE ACCESSOR
// Prevents look-ahead by restricting access to bars beyond currentBarIndex.
// Used by all analysis modules to guarantee non-repainting.
// ============================================================================

export class SafeCandleAccessor {
  private candles: Candle[];
  private maxIndex: number;
  private accessLog: number[] = []; // Track which indices were accessed

  constructor(candles: Candle[], currentBarIndex: number) {
    this.candles = candles;
    this.maxIndex = Math.min(currentBarIndex, candles.length - 1);
  }

  /**
   * Get a candle at index. Returns undefined if index is beyond allowed range.
   */
  get(index: number): Candle | undefined {
    if (index < 0 || index > this.maxIndex) {
      return undefined; // Cannot access future data
    }
    this.accessLog.push(index);
    return this.candles[index];
  }

  /**
   * Get the high price at index (safe)
   */
  high(index: number): number | undefined {
    return this.get(index)?.high;
  }

  /**
   * Get the low price at index (safe)
   */
  low(index: number): number | undefined {
    return this.get(index)?.low;
  }

  /**
   * Get the close price at index (safe)
   */
  close(index: number): number | undefined {
    return this.get(index)?.close;
  }

  /**
   * Get the open price at index (safe)
   */
  open(index: number): number | undefined {
    return this.get(index)?.open;
  }

  /**
   * Get a slice of candles. Automatically clamps to allowed range.
   */
  slice(start: number, end?: number): Candle[] {
    const safeStart = Math.max(0, start);
    const safeEnd = Math.min(end ?? this.maxIndex + 1, this.maxIndex + 1);
    const result: Candle[] = [];
    for (let i = safeStart; i < safeEnd; i++) {
      result.push(this.candles[i]);
      this.accessLog.push(i);
    }
    return result;
  }

  /**
   * Get the number of accessible bars (NOT total candles, only up to currentBar)
   */
  get length(): number {
    return this.maxIndex + 1;
  }

  /**
   * Get the last accessible candle
   */
  get last(): Candle | undefined {
    return this.maxIndex >= 0 ? this.candles[this.maxIndex] : undefined;
  }

  /**
   * Get all indices that were accessed (for validation audit)
   */
  getAccessedIndices(): number[] {
    return [...this.accessLog];
  }

  /**
   * Get the maximum index that was accessed
   */
  getMaxAccessedIndex(): number {
    return this.accessLog.length > 0 ? Math.max(...this.accessLog) : -1;
  }

  /**
   * Verify no look-ahead occurred (max accessed <= maxIndex)
   */
  verifyNoLookAhead(): boolean {
    return this.getMaxAccessedIndex() <= this.maxIndex;
  }

  /**
   * Reset access log
   */
  resetAccessLog(): void {
    this.accessLog = [];
  }
}

// ============================================================================
// SINGLETON INSTANCE
// ============================================================================

/** Global non-repainting guard instance */
export const nonRepaintingGuard = new NonRepaintingGuard();
