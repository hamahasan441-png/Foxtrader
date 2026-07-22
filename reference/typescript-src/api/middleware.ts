// ============================================================================
// API MIDDLEWARE — JWT Auth, Rate Limiting, RBAC, Request Logging
// ============================================================================

/**
 * Middleware types and implementations for the API server.
 * Compatible with Express/Fastify/Hono middleware patterns.
 */

export interface JWTPayload {
  sub: string;       // user ID
  email: string;
  role: 'admin' | 'trader' | 'viewer';
  iat: number;
  exp: number;
  jti: string;       // unique token ID (for blacklisting)
}

export interface JWTConfig {
  accessSecret: string;
  refreshSecret: string;
  accessExpiryMs: number;    // 15 minutes
  refreshExpiryMs: number;   // 7 days
  issuer: string;
}

export const DEFAULT_JWT_CONFIG: JWTConfig = {
  accessSecret: process.env?.JWT_ACCESS_SECRET || 'CHANGE_ME_IN_PRODUCTION',
  refreshSecret: process.env?.JWT_REFRESH_SECRET || 'CHANGE_ME_REFRESH',
  accessExpiryMs: 15 * 60 * 1000,
  refreshExpiryMs: 7 * 24 * 60 * 60 * 1000,
  issuer: 'institutional-trading-platform',
};

export interface RateLimitEntry {
  count: number;
  windowStart: number;
}

/**
 * In-memory rate limiter (swap with Redis in production)
 */
export class RateLimiter {
  private store: Map<string, RateLimitEntry> = new Map();

  /**
   * Check if a request is within rate limits.
   * Returns { allowed, remaining, resetMs }
   */
  check(key: string, maxRequests: number, windowSec: number): {
    allowed: boolean; remaining: number; resetMs: number;
  } {
    const now = Date.now();
    const windowMs = windowSec * 1000;
    const entry = this.store.get(key);

    if (!entry || now - entry.windowStart >= windowMs) {
      // New window
      this.store.set(key, { count: 1, windowStart: now });
      return { allowed: true, remaining: maxRequests - 1, resetMs: windowMs };
    }

    if (entry.count >= maxRequests) {
      const resetMs = windowMs - (now - entry.windowStart);
      return { allowed: false, remaining: 0, resetMs };
    }

    entry.count++;
    return { allowed: true, remaining: maxRequests - entry.count, resetMs: windowMs - (now - entry.windowStart) };
  }

  /** Cleanup expired entries (call periodically) */
  cleanup(): void {
    const now = Date.now();
    for (const [key, entry] of this.store) {
      if (now - entry.windowStart > 120000) this.store.delete(key);
    }
  }
}

/**
 * CORS configuration
 */
export const CORS_CONFIG = {
  origin: ['http://localhost:3000', 'https://app.trading-platform.com'],
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Request-ID'],
  credentials: true,
  maxAge: 86400,
};

/**
 * OAuth2 provider configuration (for social login)
 */
export interface OAuth2Config {
  google?: { clientId: string; clientSecret: string; callbackUrl: string };
  github?: { clientId: string; clientSecret: string; callbackUrl: string };
}

/**
 * Request context populated by middleware
 */
export interface RequestContext {
  userId: string;
  email: string;
  role: 'admin' | 'trader' | 'viewer';
  jti: string;
  requestId: string;
  ip: string;
  userAgent: string;
  timestamp: number;
}
