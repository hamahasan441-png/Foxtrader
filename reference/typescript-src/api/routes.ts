// ============================================================================
// API LAYER — Route Definitions (FastAPI-style)
// REST + WebSocket | JWT/OAuth2 | Role-Based Access | Rate Limiting
// ============================================================================

/**
 * API Route Map — documents all endpoints, methods, auth, and rate limits.
 * Implementation uses a generic router pattern compatible with Express/Fastify/Hono.
 */

export interface RouteDefinition {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'WS';
  path: string;
  handler: string;
  auth: 'PUBLIC' | 'JWT' | 'ADMIN';
  rateLimit: { requests: number; windowSec: number };
  description: string;
  tags: string[];
}

export const API_ROUTES: RouteDefinition[] = [
  // --- Auth ---
  { method: 'POST', path: '/api/v1/auth/register', handler: 'authRegister', auth: 'PUBLIC', rateLimit: { requests: 5, windowSec: 60 }, description: 'Register new user', tags: ['auth'] },
  { method: 'POST', path: '/api/v1/auth/login', handler: 'authLogin', auth: 'PUBLIC', rateLimit: { requests: 10, windowSec: 60 }, description: 'Login (returns JWT + refresh token)', tags: ['auth'] },
  { method: 'POST', path: '/api/v1/auth/refresh', handler: 'authRefresh', auth: 'PUBLIC', rateLimit: { requests: 20, windowSec: 60 }, description: 'Refresh access token', tags: ['auth'] },
  { method: 'POST', path: '/api/v1/auth/logout', handler: 'authLogout', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Logout (blacklist token)', tags: ['auth'] },
  { method: 'POST', path: '/api/v1/auth/pin/verify', handler: 'verifyPin', auth: 'JWT', rateLimit: { requests: 5, windowSec: 60 }, description: 'Verify PIN', tags: ['auth'] },

  // --- Market Data ---
  { method: 'GET', path: '/api/v1/market/candles/:symbol/:timeframe', handler: 'getCandles', auth: 'JWT', rateLimit: { requests: 60, windowSec: 60 }, description: 'Get historical candles', tags: ['market'] },
  { method: 'GET', path: '/api/v1/market/ticker/:symbol', handler: 'getTicker', auth: 'JWT', rateLimit: { requests: 120, windowSec: 60 }, description: 'Get latest ticker', tags: ['market'] },
  { method: 'GET', path: '/api/v1/market/symbols', handler: 'getSymbols', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'List available symbols', tags: ['market'] },

  // --- Analysis ---
  { method: 'POST', path: '/api/v1/analysis/run', handler: 'runAnalysis', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Run full agent analysis on a symbol', tags: ['analysis'] },
  { method: 'GET', path: '/api/v1/analysis/mtf/:symbol', handler: 'getMTFAnalysis', auth: 'JWT', rateLimit: { requests: 20, windowSec: 60 }, description: 'Get multi-timeframe analysis', tags: ['analysis'] },
  { method: 'GET', path: '/api/v1/analysis/patterns/:symbol', handler: 'getPatterns', auth: 'JWT', rateLimit: { requests: 20, windowSec: 60 }, description: 'Detect chart patterns', tags: ['analysis'] },
  { method: 'POST', path: '/api/v1/analysis/mentor', handler: 'askMentor', auth: 'JWT', rateLimit: { requests: 20, windowSec: 60 }, description: 'Ask AI mentor a question', tags: ['analysis'] },
  { method: 'GET', path: '/api/v1/analysis/plan/:symbol', handler: 'getTradePlan', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Generate AI trade plan', tags: ['analysis'] },

  // --- Scanner / Screener ---
  { method: 'POST', path: '/api/v1/scanner/run', handler: 'runScanner', auth: 'JWT', rateLimit: { requests: 5, windowSec: 60 }, description: 'Run full watchlist scan', tags: ['scanner'] },
  { method: 'GET', path: '/api/v1/scanner/results', handler: 'getScanResults', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get latest scan results', tags: ['scanner'] },

  // --- Trading ---
  { method: 'POST', path: '/api/v1/trading/order', handler: 'placeOrder', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Place an order', tags: ['trading'] },
  { method: 'DELETE', path: '/api/v1/trading/order/:id', handler: 'cancelOrder', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Cancel an order', tags: ['trading'] },
  { method: 'POST', path: '/api/v1/trading/close/:positionId', handler: 'closePosition', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Close a position', tags: ['trading'] },
  { method: 'POST', path: '/api/v1/trading/close-all', handler: 'closeAll', auth: 'JWT', rateLimit: { requests: 5, windowSec: 60 }, description: 'Close all positions', tags: ['trading'] },
  { method: 'GET', path: '/api/v1/trading/positions', handler: 'getPositions', auth: 'JWT', rateLimit: { requests: 60, windowSec: 60 }, description: 'Get open positions', tags: ['trading'] },
  { method: 'GET', path: '/api/v1/trading/orders', handler: 'getOrders', auth: 'JWT', rateLimit: { requests: 60, windowSec: 60 }, description: 'Get working orders', tags: ['trading'] },
  { method: 'GET', path: '/api/v1/trading/account', handler: 'getAccount', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get account summary', tags: ['trading'] },

  // --- Journal ---
  { method: 'GET', path: '/api/v1/journal', handler: 'getJournal', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get journal entries', tags: ['journal'] },
  { method: 'POST', path: '/api/v1/journal', handler: 'createJournalEntry', auth: 'JWT', rateLimit: { requests: 20, windowSec: 60 }, description: 'Create journal entry', tags: ['journal'] },
  { method: 'PATCH', path: '/api/v1/journal/:id', handler: 'updateJournalEntry', auth: 'JWT', rateLimit: { requests: 20, windowSec: 60 }, description: 'Update journal entry', tags: ['journal'] },
  { method: 'GET', path: '/api/v1/journal/stats', handler: 'getJournalStats', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Get journal statistics', tags: ['journal'] },
  { method: 'GET', path: '/api/v1/journal/insights', handler: 'getJournalInsights', auth: 'JWT', rateLimit: { requests: 5, windowSec: 60 }, description: 'Get AI improvement insights', tags: ['journal'] },

  // --- Backtesting ---
  { method: 'POST', path: '/api/v1/backtest/run', handler: 'runBacktest', auth: 'JWT', rateLimit: { requests: 3, windowSec: 60 }, description: 'Run a backtest', tags: ['backtest'] },
  { method: 'POST', path: '/api/v1/backtest/optimize', handler: 'runOptimization', auth: 'JWT', rateLimit: { requests: 1, windowSec: 120 }, description: 'Run AI optimization', tags: ['backtest'] },
  { method: 'GET', path: '/api/v1/backtest/results/:id', handler: 'getBacktestResult', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get backtest results', tags: ['backtest'] },

  // --- Settings & Sync ---
  { method: 'GET', path: '/api/v1/settings', handler: 'getSettings', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get user settings', tags: ['settings'] },
  { method: 'PUT', path: '/api/v1/settings', handler: 'updateSettings', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Update user settings', tags: ['settings'] },
  { method: 'POST', path: '/api/v1/sync/push', handler: 'syncPush', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Push sync data', tags: ['sync'] },
  { method: 'GET', path: '/api/v1/sync/pull', handler: 'syncPull', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Pull sync data', tags: ['sync'] },

  // --- Alerts ---
  { method: 'GET', path: '/api/v1/alerts', handler: 'getAlerts', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get alerts', tags: ['alerts'] },
  { method: 'POST', path: '/api/v1/alerts/:id/ack', handler: 'ackAlert', auth: 'JWT', rateLimit: { requests: 60, windowSec: 60 }, description: 'Acknowledge alert', tags: ['alerts'] },

  // --- News ---
  { method: 'GET', path: '/api/v1/news/calendar', handler: 'getCalendar', auth: 'JWT', rateLimit: { requests: 30, windowSec: 60 }, description: 'Get economic calendar', tags: ['news'] },
  { method: 'GET', path: '/api/v1/news/impact/:id', handler: 'getNewsImpact', auth: 'JWT', rateLimit: { requests: 20, windowSec: 60 }, description: 'Get AI news impact analysis', tags: ['news'] },

  // --- Admin ---
  { method: 'GET', path: '/api/v1/admin/users', handler: 'listUsers', auth: 'ADMIN', rateLimit: { requests: 10, windowSec: 60 }, description: 'List all users', tags: ['admin'] },
  { method: 'GET', path: '/api/v1/admin/audit', handler: 'getAuditLog', auth: 'ADMIN', rateLimit: { requests: 10, windowSec: 60 }, description: 'Get audit log', tags: ['admin'] },
  { method: 'GET', path: '/api/v1/admin/health', handler: 'healthCheck', auth: 'PUBLIC', rateLimit: { requests: 60, windowSec: 60 }, description: 'System health check', tags: ['admin'] },
  { method: 'GET', path: '/api/v1/admin/metrics', handler: 'getMetrics', auth: 'ADMIN', rateLimit: { requests: 10, windowSec: 60 }, description: 'Performance metrics', tags: ['admin'] },

  // --- WebSocket ---
  { method: 'WS', path: '/ws/v1/market/:symbol', handler: 'wsMarketStream', auth: 'JWT', rateLimit: { requests: 10, windowSec: 60 }, description: 'Real-time market data stream', tags: ['websocket'] },
  { method: 'WS', path: '/ws/v1/user', handler: 'wsUserStream', auth: 'JWT', rateLimit: { requests: 5, windowSec: 60 }, description: 'User notifications, orders, positions', tags: ['websocket'] },
  { method: 'WS', path: '/ws/v1/agents/:symbol', handler: 'wsAgentStream', auth: 'JWT', rateLimit: { requests: 5, windowSec: 60 }, description: 'Real-time agent analysis stream', tags: ['websocket'] },
];

/** Total route count */
export const TOTAL_ROUTES = API_ROUTES.length;
