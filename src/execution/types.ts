// ============================================================================
// EXECUTION ENGINE - Type Definitions
// ============================================================================

import { Direction } from '../core/types';

export type OrderType = 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT' | 'OCO';
export type OrderSide = 'BUY' | 'SELL';
export type OrderStatus =
  | 'PENDING' | 'WORKING' | 'PARTIALLY_FILLED' | 'FILLED'
  | 'CANCELLED' | 'REJECTED' | 'EXPIRED';
export type TimeInForce = 'GTC' | 'IOC' | 'FOK' | 'DAY' | 'GTD';
export type PositionStatus = 'OPEN' | 'CLOSED' | 'PARTIALLY_CLOSED';

export interface TakeProfitLevel {
  id: string;
  price: number;
  /** Percentage of the position to close at this level (0-100) */
  volumePercent: number;
  filled: boolean;
  filledAt?: number;
}

export interface StopLossLevel {
  id: string;
  price: number;
  /** Percentage of the position this SL applies to (0-100) */
  volumePercent: number;
  triggered: boolean;
  triggeredAt?: number;
}

export interface TrailingStopConfig {
  enabled: boolean;
  /** Distance from price in price units */
  distance: number;
  /** Activate trailing only after this profit (price units) */
  activationProfit: number;
  /** Step size for trailing adjustments */
  step: number;
  currentStopPrice?: number;
  activated: boolean;
}

export interface BreakEvenConfig {
  enabled: boolean;
  /** Move SL to break-even after this profit (price units) */
  triggerProfit: number;
  /** Offset from entry (e.g. +2 pips to cover commission) */
  offset: number;
  triggered: boolean;
}

export interface Order {
  id: string;
  clientOrderId?: string;
  symbol: string;
  type: OrderType;
  side: OrderSide;
  status: OrderStatus;
  /** Requested volume in lots/units */
  volume: number;
  /** Filled volume */
  filledVolume: number;
  /** Limit price (for LIMIT, STOP_LIMIT) */
  limitPrice?: number;
  /** Stop trigger price (for STOP, STOP_LIMIT) */
  stopPrice?: number;
  /** Average fill price */
  avgFillPrice?: number;
  timeInForce: TimeInForce;
  /** Expiry timestamp for GTD */
  expiryTime?: number;
  createdAt: number;
  updatedAt: number;
  filledAt?: number;
  /** Linked orders for OCO */
  linkedOrderIds: string[];
  /** Parent position (if this order manages a position) */
  positionId?: string;
  takeProfits: TakeProfitLevel[];
  stopLosses: StopLossLevel[];
  rejectReason?: string;
  /** Commission charged */
  commission: number;
  /** Slippage experienced */
  slippage: number;
}

export interface Position {
  id: string;
  symbol: string;
  side: OrderSide;
  direction: Direction;
  status: PositionStatus;
  /** Total volume currently open */
  volume: number;
  /** Original opened volume */
  originalVolume: number;
  /** Volume-weighted average entry price */
  avgEntryPrice: number;
  /** Current market price */
  currentPrice: number;
  /** Realized P&L from partial closes */
  realizedPnL: number;
  /** Unrealized P&L on remaining volume */
  unrealizedPnL: number;
  takeProfits: TakeProfitLevel[];
  stopLosses: StopLossLevel[];
  trailingStop: TrailingStopConfig;
  breakEven: BreakEvenConfig;
  openedAt: number;
  closedAt?: number;
  /** Fills that built this position (for scaling in) */
  fills: PositionFill[];
  commission: number;
  swap: number;
  /** Group tag for hedge / basket management */
  groupId?: string;
  /** Maximum favorable/adverse excursion tracking */
  maxFavorable: number;
  maxAdverse: number;
  comment?: string;
}

export interface PositionFill {
  price: number;
  volume: number;
  timestamp: number;
  type: 'SCALE_IN' | 'SCALE_OUT' | 'INITIAL' | 'TP' | 'SL';
}

export interface ExecutionReport {
  orderId: string;
  positionId?: string;
  status: OrderStatus;
  filledVolume: number;
  avgFillPrice: number;
  commission: number;
  slippage: number;
  timestamp: number;
  message: string;
}

export interface OrderRequest {
  symbol: string;
  type: OrderType;
  side: OrderSide;
  volume: number;
  limitPrice?: number;
  stopPrice?: number;
  timeInForce?: TimeInForce;
  expiryTime?: number;
  takeProfits?: { price: number; volumePercent: number }[];
  stopLosses?: { price: number; volumePercent: number }[];
  trailingStop?: Partial<TrailingStopConfig>;
  breakEven?: Partial<BreakEvenConfig>;
  /** For OCO - the secondary order */
  ocoSecondary?: Omit<OrderRequest, 'ocoSecondary'>;
  groupId?: string;
  comment?: string;
}

export interface MarketConditions {
  bid: number;
  ask: number;
  spread: number;
  timestamp: number;
  /** Available liquidity for slippage modeling */
  liquidity?: number;
}
