// ============================================================================
// BROKER CONNECTIVITY — Modular Adapter Interface
// Design: each broker adapter implements BrokerAdapter, registered in
// BrokerRegistry. Core never imports broker-specific code directly.
// New brokers can be added without touching the core architecture.
// ============================================================================

export type BrokerName = 'BINANCE' | 'BYBIT' | 'OKX' | 'OANDA' | 'INTERACTIVE_BROKERS' | 'ALPACA';
export type BrokerStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'AUTHENTICATING';
export type OrderSide = 'BUY' | 'SELL';

export interface BrokerCredentials {
  apiKey: string;
  apiSecret: string;
  passphrase?: string;    // OKX
  accountId?: string;     // OANDA
  sandbox?: boolean;      // paper trading
}

export interface BrokerPosition {
  symbol: string;
  side: OrderSide;
  volume: number;
  entryPrice: number;
  currentPrice: number;
  unrealizedPnL: number;
  realizedPnL: number;
  openedAt: number;
}

export interface BrokerOrder {
  id: string;
  symbol: string;
  side: OrderSide;
  type: 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT';
  volume: number;
  price?: number;
  stopPrice?: number;
  status: 'OPEN' | 'FILLED' | 'CANCELLED' | 'REJECTED';
  filledVolume: number;
  avgFillPrice: number;
  createdAt: number;
}

export interface BrokerBalance {
  currency: string;
  total: number;
  available: number;
  margin: number;
}

export interface BrokerTicker {
  symbol: string;
  bid: number;
  ask: number;
  last: number;
  volume24h: number;
  timestamp: number;
}

/**
 * Every broker adapter MUST implement this interface.
 * The core platform interacts with brokers exclusively through this.
 */
export interface BrokerAdapter {
  readonly name: BrokerName;
  status: BrokerStatus;

  /** Connect and authenticate */
  connect(credentials: BrokerCredentials): Promise<boolean>;

  /** Disconnect */
  disconnect(): Promise<void>;

  // --- Account ---
  getBalance(): Promise<BrokerBalance[]>;
  getPositions(): Promise<BrokerPosition[]>;
  getOpenOrders(): Promise<BrokerOrder[]>;

  // --- Trading ---
  placeOrder(params: {
    symbol: string; side: OrderSide; type: BrokerOrder['type'];
    volume: number; price?: number; stopPrice?: number;
  }): Promise<BrokerOrder>;

  cancelOrder(orderId: string): Promise<boolean>;
  closePosition(symbol: string, volume?: number): Promise<BrokerOrder>;

  // --- Market Data ---
  getTicker(symbol: string): Promise<BrokerTicker>;
  getSymbols(): Promise<string[]>;

  // --- Streaming ---
  subscribeTicker(symbol: string, cb: (ticker: BrokerTicker) => void): () => void;
}
