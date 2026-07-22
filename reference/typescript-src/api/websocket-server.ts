// ============================================================================
// WEBSOCKET SERVER — Real-time streaming channels
// Channels: market/{symbol}, user, agents/{symbol}
// Handles auth via JWT token in query string or first message
// ============================================================================

export type WSChannel = 'MARKET' | 'USER' | 'AGENTS';

export interface WSMessage {
  type: 'SUBSCRIBE' | 'UNSUBSCRIBE' | 'AUTH' | 'PING' | 'DATA' | 'ERROR';
  channel?: string;
  symbol?: string;
  data?: unknown;
  token?: string;
  timestamp: number;
}

export interface WSClient {
  id: string;
  userId: string;
  subscriptions: Set<string>;
  authenticated: boolean;
  connectedAt: number;
  lastPing: number;
}

/**
 * WebSocket Server specification.
 *
 * Connection flow:
 * 1. Client connects to ws://host/ws/v1/market/EURUSD?token=JWT
 * 2. Server validates JWT, creates WSClient
 * 3. Server streams data:
 *    - market/{symbol}: tick updates, new candles
 *    - user: order fills, position updates, alerts
 *    - agents/{symbol}: real-time agent analysis results
 *
 * Heartbeat: server sends PING every 30s, expects PONG within 10s.
 * Reconnection: client should reconnect with exponential backoff.
 *
 * Rate limit: max 100 messages/sec per client.
 * Max subscriptions: 20 channels per client.
 * Max clients per user: 5 concurrent connections.
 */

export const WS_CONFIG = {
  heartbeatIntervalMs: 30000,
  heartbeatTimeoutMs: 10000,
  maxSubscriptionsPerClient: 20,
  maxClientsPerUser: 5,
  maxMessagesPerSecond: 100,
  tokenQueryParam: 'token',
};

/**
 * Message types sent TO the client:
 */
export const WS_EVENTS = {
  // Market channel
  TICK: 'tick',
  CANDLE_UPDATE: 'candle_update',
  CANDLE_CLOSE: 'candle_close',

  // User channel
  ORDER_FILL: 'order_fill',
  ORDER_CANCELLED: 'order_cancelled',
  POSITION_UPDATE: 'position_update',
  POSITION_CLOSED: 'position_closed',
  ALERT: 'alert',
  RISK_HALT: 'risk_halt',

  // Agents channel
  AGENT_INSIGHT: 'agent_insight',
  DECISION_RESULT: 'decision_result',
  MTF_UPDATE: 'mtf_update',
  PATTERN_DETECTED: 'pattern_detected',

  // System
  CONNECTED: 'connected',
  ERROR: 'error',
  PONG: 'pong',
};
