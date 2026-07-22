// ============================================================================
// SESSIONS MODULE
// Asian, London, New York, Sydney sessions with automatic highs/lows
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  TradingSession,
  SessionType,
} from '../../core/types';
import { TradingEventBus } from '../../core/event-bus';

export interface SessionConfig {
  sessions: Record<SessionType, { startHourUTC: number; endHourUTC: number }>;
  trackPreviousSessions: number; // How many past sessions to keep
  highlightActive: boolean;
}

const DEFAULT_CONFIG: SessionConfig = {
  sessions: {
    SYDNEY: { startHourUTC: 21, endHourUTC: 6 },
    ASIAN: { startHourUTC: 0, endHourUTC: 9 },
    LONDON: { startHourUTC: 7, endHourUTC: 16 },
    NEW_YORK: { startHourUTC: 12, endHourUTC: 21 },
  },
  trackPreviousSessions: 10,
  highlightActive: true,
};

export class SessionAnalyzer {
  private config: SessionConfig;
  private eventBus?: TradingEventBus;
  private sessions: TradingSession[] = [];
  private currentSessions: Map<SessionType, TradingSession> = new Map();

  constructor(config: Partial<SessionConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Analyze candles and identify session boundaries with highs/lows
   */
  analyze(candles: Candle[]): TradingSession[] {
    this.sessions = [];
    if (candles.length === 0) return [];

    // Group candles by session
    const sessionGroups = this.groupCandlesBySessions(candles);

    // Calculate highs/lows for each session
    for (const [key, group] of sessionGroups) {
      const [type, dateStr] = key.split('_') as [SessionType, string];
      
      if (group.length === 0) continue;

      const high = Math.max(...group.map(c => c.high));
      const low = Math.min(...group.map(c => c.low));
      const open = group[0].open;
      const close = group[group.length - 1].close;

      const session: TradingSession = {
        type,
        startHourUTC: this.config.sessions[type].startHourUTC,
        endHourUTC: this.config.sessions[type].endHourUTC,
        high,
        low,
        open,
        close,
        range: high - low,
        timestamp: group[0].timestamp,
        isActive: false,
      };

      this.sessions.push(session);
    }

    // Mark current active sessions
    if (candles.length > 0) {
      const lastTimestamp = candles[candles.length - 1].timestamp;
      this.updateActiveSessions(lastTimestamp);
    }

    return [...this.sessions];
  }

  /**
   * Update on new candle - track live session highs/lows
   */
  updateOnNewCandle(candle: Candle): void {
    const activeSessions = this.getActiveSessions(candle.timestamp);

    for (const type of activeSessions) {
      let session = this.currentSessions.get(type);

      if (!session) {
        // New session starting
        session = {
          type,
          startHourUTC: this.config.sessions[type].startHourUTC,
          endHourUTC: this.config.sessions[type].endHourUTC,
          high: candle.high,
          low: candle.low,
          open: candle.open,
          close: candle.close,
          range: candle.high - candle.low,
          timestamp: candle.timestamp,
          isActive: true,
        };
        this.currentSessions.set(type, session);
        this.eventBus?.emit({ type: 'SESSION_CHANGE', data: session });
      } else {
        // Update existing session
        session.high = Math.max(session.high, candle.high);
        session.low = Math.min(session.low, candle.low);
        session.close = candle.close;
        session.range = session.high - session.low;
      }
    }

    // Check for ended sessions
    for (const [type, session] of this.currentSessions) {
      if (!activeSessions.includes(type)) {
        session.isActive = false;
        this.sessions.push(session);
        this.currentSessions.delete(type);
        this.eventBus?.emit({ type: 'SESSION_CHANGE', data: session });
      }
    }
  }

  /**
   * Get which sessions are active at a given timestamp
   */
  getActiveSessions(timestamp: number): SessionType[] {
    const date = new Date(timestamp);
    const hour = date.getUTCHours();
    const active: SessionType[] = [];

    for (const [type, times] of Object.entries(this.config.sessions)) {
      if (this.isHourInSession(hour, times.startHourUTC, times.endHourUTC)) {
        active.push(type as SessionType);
      }
    }

    return active;
  }

  /**
   * Check if an hour falls within a session (handles midnight wrap)
   */
  private isHourInSession(hour: number, start: number, end: number): boolean {
    if (start <= end) {
      return hour >= start && hour < end;
    }
    // Wraps midnight
    return hour >= start || hour < end;
  }

  /**
   * Group candles by session
   */
  private groupCandlesBySessions(candles: Candle[]): Map<string, Candle[]> {
    const groups = new Map<string, Candle[]>();

    for (const candle of candles) {
      const date = new Date(candle.timestamp);
      const hour = date.getUTCHours();
      const dateStr = date.toISOString().split('T')[0];

      for (const [type, times] of Object.entries(this.config.sessions)) {
        if (this.isHourInSession(hour, times.startHourUTC, times.endHourUTC)) {
          const key = `${type}_${dateStr}`;
          if (!groups.has(key)) groups.set(key, []);
          groups.get(key)!.push(candle);
        }
      }
    }

    return groups;
  }

  /**
   * Update which sessions are currently active
   */
  private updateActiveSessions(timestamp: number): void {
    const activeSessions = this.getActiveSessions(timestamp);
    
    for (const session of this.sessions) {
      session.isActive = activeSessions.includes(session.type);
    }
  }

  /**
   * Get previous session high/low for a given session type
   */
  getPreviousSession(type: SessionType): TradingSession | null {
    const typeSessions = this.sessions
      .filter(s => s.type === type && !s.isActive)
      .sort((a, b) => b.timestamp - a.timestamp);
    
    return typeSessions.length > 0 ? typeSessions[0] : null;
  }

  /**
   * Get all session highs and lows as key levels
   */
  getSessionLevels(): { type: SessionType; high: number; low: number; timestamp: number }[] {
    return this.sessions.map(s => ({
      type: s.type,
      high: s.high,
      low: s.low,
      timestamp: s.timestamp,
    }));
  }

  /**
   * Get current active sessions with live H/L
   */
  getLiveSessionData(): TradingSession[] {
    return Array.from(this.currentSessions.values());
  }

  reset(): void {
    this.sessions = [];
    this.currentSessions.clear();
  }
}
