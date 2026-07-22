// ============================================================================
// REDIS SCHEMA — Cache layer for real-time data
// Keys, TTLs, and data structures for the Redis cache
// ============================================================================

/**
 * Redis key patterns and TTLs used by the platform.
 * Redis provides: market data cache, session store, rate limiting,
 * real-time pub/sub, and ephemeral state.
 */

export const REDIS_KEYS = {
  // --- Market Data Cache ---
  /** Latest tick for a symbol: ticker:{symbol} */
  TICKER: (symbol: string) => `ticker:${symbol}`,
  /** Cached candles: candles:{symbol}:{timeframe}:{date} */
  CANDLES: (symbol: string, tf: string, date: string) => `candles:${symbol}:${tf}:${date}`,
  /** Provider health: provider:{name}:status */
  PROVIDER_STATUS: (name: string) => `provider:${name}:status`,

  // --- Sessions & Auth ---
  /** User session: session:{userId}:{deviceId} */
  SESSION: (userId: string, deviceId: string) => `session:${userId}:${deviceId}`,
  /** Rate limit counter: ratelimit:{userId}:{endpoint} */
  RATE_LIMIT: (userId: string, endpoint: string) => `ratelimit:${userId}:${endpoint}`,
  /** JWT blacklist (revoked tokens): jwt:blacklist:{jti} */
  JWT_BLACKLIST: (jti: string) => `jwt:blacklist:${jti}`,

  // --- Real-time State ---
  /** Open positions cache: positions:{userId} */
  POSITIONS: (userId: string) => `positions:${userId}`,
  /** Working orders cache: orders:{userId} */
  ORDERS: (userId: string) => `orders:${userId}`,
  /** Active alerts: alerts:{userId} */
  ALERTS: (userId: string) => `alerts:${userId}`,

  // --- Pub/Sub Channels ---
  /** Market data stream: stream:market:{symbol} */
  STREAM_MARKET: (symbol: string) => `stream:market:${symbol}`,
  /** User notifications: stream:user:{userId} */
  STREAM_USER: (userId: string) => `stream:user:${userId}`,
  /** Scanner results: stream:scanner */
  STREAM_SCANNER: 'stream:scanner',
  /** Agent analysis: stream:agents:{symbol} */
  STREAM_AGENTS: (symbol: string) => `stream:agents:${symbol}`,

  // --- Locking ---
  /** Distributed lock: lock:{resource} */
  LOCK: (resource: string) => `lock:${resource}`,
};

export const REDIS_TTLS = {
  TICKER: 5,              // 5 seconds
  CANDLES: 300,           // 5 minutes
  PROVIDER_STATUS: 60,    // 1 minute
  SESSION: 1800,          // 30 minutes
  RATE_LIMIT: 60,         // 1 minute window
  JWT_BLACKLIST: 86400,   // 24 hours (match JWT expiry)
  POSITIONS: 10,          // 10 seconds (frequently updated)
  ORDERS: 10,
  ALERTS: 3600,           // 1 hour
  LOCK: 30,               // 30 second lock TTL
};

/**
 * Redis data structures overview:
 *
 * STRING:  ticker:{symbol} → JSON {bid, ask, last, timestamp}
 * STRING:  session:{userId}:{deviceId} → JSON {token, expiresAt}
 * STRING:  jwt:blacklist:{jti} → "revoked"
 * STRING:  lock:{resource} → ownerUUID
 *
 * HASH:    candles:{symbol}:{tf}:{date} → {ts1: OHLCV, ts2: OHLCV, ...}
 * HASH:    positions:{userId} → {posId1: JSON, posId2: JSON, ...}
 * HASH:    orders:{userId} → {ordId1: JSON, ordId2: JSON, ...}
 *
 * SORTED SET: ratelimit:{userId}:{endpoint} → score=timestamp, member=requestId
 *
 * LIST:    alerts:{userId} → [JSON alert1, alert2, ...]
 *
 * STREAM:  stream:market:{symbol} → time-series market events
 * STREAM:  stream:user:{userId} → user notifications
 * STREAM:  stream:scanner → scanner results
 */
