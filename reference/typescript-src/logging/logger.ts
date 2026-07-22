// ============================================================================
// CENTRALIZED LOGGING — Structured, leveled, context-aware
// Logging | Crash Reports | Performance Monitoring | Audit Logs
// ============================================================================

export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';
export type LogCategory =
  | 'SYSTEM' | 'AUTH' | 'TRADE' | 'MARKET' | 'AGENT'
  | 'RISK' | 'BROKER' | 'API' | 'WS' | 'PERF' | 'AUDIT' | 'SECURITY';

export interface LogEntry {
  level: LogLevel;
  category: LogCategory;
  message: string;
  timestamp: number;
  /** Structured metadata */
  meta?: Record<string, unknown>;
  /** User ID if applicable */
  userId?: string;
  /** Request ID for tracing */
  requestId?: string;
  /** Duration in ms (for perf logs) */
  durationMs?: number;
  /** Error stack trace */
  stack?: string;
  /** Source file/module */
  source?: string;
}

export interface LoggerConfig {
  minLevel: LogLevel;
  enableConsole: boolean;
  enableRemote: boolean;
  remoteEndpoint?: string;
  /** Max entries to buffer before flush */
  bufferSize: number;
  /** Flush interval ms */
  flushIntervalMs: number;
  /** Include stack traces for errors */
  captureStacks: boolean;
  /** Performance threshold (log if > this ms) */
  perfThresholdMs: number;
}

const DEFAULT_CONFIG: LoggerConfig = {
  minLevel: 'INFO',
  enableConsole: true,
  enableRemote: false,
  bufferSize: 100,
  flushIntervalMs: 5000,
  captureStacks: true,
  perfThresholdMs: 100,
};

const LEVEL_ORDER: LogLevel[] = ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];

export class Logger {
  private config: LoggerConfig;
  private buffer: LogEntry[] = [];
  private flushTimer: number = 0;
  private static instance: Logger | null = null;

  constructor(config: Partial<LoggerConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    if (this.config.enableRemote && this.config.flushIntervalMs > 0) {
      this.flushTimer = (typeof setInterval !== 'undefined')
        ? (setInterval(() => this.flush(), this.config.flushIntervalMs) as unknown as number)
        : 0;
    }
  }

  static getInstance(config?: Partial<LoggerConfig>): Logger {
    if (!Logger.instance) Logger.instance = new Logger(config);
    return Logger.instance;
  }

  // --- Convenience methods ---
  debug(msg: string, meta?: Record<string, unknown>) { this.log('DEBUG', 'SYSTEM', msg, meta); }
  info(msg: string, meta?: Record<string, unknown>) { this.log('INFO', 'SYSTEM', msg, meta); }
  warn(msg: string, meta?: Record<string, unknown>) { this.log('WARN', 'SYSTEM', msg, meta); }
  error(msg: string, error?: Error, meta?: Record<string, unknown>) {
    this.log('ERROR', 'SYSTEM', msg, { ...meta, error: error?.message, stack: error?.stack });
  }
  fatal(msg: string, error?: Error, meta?: Record<string, unknown>) {
    this.log('FATAL', 'SYSTEM', msg, { ...meta, error: error?.message, stack: error?.stack });
    this.flush(); // Immediately flush fatal errors
  }

  // --- Category-specific ---
  trade(msg: string, meta?: Record<string, unknown>) { this.log('INFO', 'TRADE', msg, meta); }
  agent(msg: string, meta?: Record<string, unknown>) { this.log('INFO', 'AGENT', msg, meta); }
  security(msg: string, meta?: Record<string, unknown>) { this.log('WARN', 'SECURITY', msg, meta); }
  audit(msg: string, meta?: Record<string, unknown>) { this.log('INFO', 'AUDIT', msg, meta); }

  /**
   * Performance timing helper
   */
  perf(label: string, startTime: number, meta?: Record<string, unknown>): void {
    const duration = performance.now() - startTime;
    if (duration >= this.config.perfThresholdMs) {
      this.log('WARN', 'PERF', `${label} took ${duration.toFixed(1)}ms (threshold: ${this.config.perfThresholdMs}ms)`, { ...meta, durationMs: duration });
    } else {
      this.log('DEBUG', 'PERF', `${label}: ${duration.toFixed(1)}ms`, { ...meta, durationMs: duration });
    }
  }

  /**
   * Core log method
   */
  log(level: LogLevel, category: LogCategory, message: string, meta?: Record<string, unknown>): void {
    if (LEVEL_ORDER.indexOf(level) < LEVEL_ORDER.indexOf(this.config.minLevel)) return;

    const entry: LogEntry = {
      level, category, message, timestamp: Date.now(), meta,
      stack: level === 'ERROR' || level === 'FATAL' ? new Error().stack : undefined,
      source: meta?.source as string,
      userId: meta?.userId as string,
      requestId: meta?.requestId as string,
      durationMs: meta?.durationMs as number,
    };

    // Console output
    if (this.config.enableConsole) {
      const prefix = `[${level}][${category}]`;
      const fn = level === 'ERROR' || level === 'FATAL' ? console.error
        : level === 'WARN' ? console.warn : console.log;
      fn(`${prefix} ${message}`, meta || '');
    }

    // Buffer for remote shipping
    if (this.config.enableRemote) {
      this.buffer.push(entry);
      if (this.buffer.length >= this.config.bufferSize) this.flush();
    }
  }

  /**
   * Flush buffered logs to remote endpoint
   */
  async flush(): Promise<void> {
    if (this.buffer.length === 0 || !this.config.remoteEndpoint) return;
    const batch = [...this.buffer];
    this.buffer = [];

    try {
      await fetch(this.config.remoteEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ logs: batch }),
      });
    } catch (err) {
      // Re-buffer on failure (with cap)
      this.buffer = [...batch.slice(-50), ...this.buffer];
    }
  }

  /** Get recent logs (for UI display) */
  getRecent(count: number = 50): LogEntry[] {
    return this.buffer.slice(-count);
  }

  /** Crash reporter — captures unhandled errors */
  installGlobalHandler(): void {
    if (typeof window !== 'undefined') {
      window.onerror = (msg, src, line, col, err) => {
        this.fatal(`Unhandled error: ${msg}`, err || undefined, { source: `${src}:${line}:${col}` });
      };
      window.onunhandledrejection = (event) => {
        this.fatal(`Unhandled promise rejection: ${event.reason}`, undefined, { reason: String(event.reason) });
      };
    }
  }

  destroy(): void {
    if (this.flushTimer) clearInterval(this.flushTimer);
    this.flush();
  }
}

/** Global logger singleton */
export const logger = Logger.getInstance();
