// ============================================================================
// EXECUTION ENGINE - Institutional-grade order execution
// Market/Limit/Stop/StopLimit/OCO | Partial TP | Multi TP/SL | Trailing Stop
// Break-even Automation | Scale In/Out | Hedging | Multi-position Management
// ============================================================================

import { Direction } from '../core/types';
import { TradingEventBus } from '../core/event-bus';
import {
  Order, Position, OrderRequest, OrderType, OrderSide, OrderStatus,
  ExecutionReport, MarketConditions, TakeProfitLevel, StopLossLevel,
  TrailingStopConfig, BreakEvenConfig, PositionFill, TimeInForce,
} from './types';

export interface ExecutionConfig {
  /** Simulated commission per lot (round turn) */
  commissionPerLot: number;
  /** Base slippage in price units for market orders */
  baseSlippage: number;
  /** Allow hedging (opposite positions on same symbol) */
  allowHedging: boolean;
  /** Max positions per symbol */
  maxPositionsPerSymbol: number;
  /** Max total positions */
  maxTotalPositions: number;
  /** Instant execution (no latency simulation) */
  instantExecution: boolean;
  /** Slippage model: FIXED | PROPORTIONAL | REALISTIC */
  slippageModel: 'FIXED' | 'PROPORTIONAL' | 'REALISTIC';
}

const DEFAULT_CONFIG: ExecutionConfig = {
  commissionPerLot: 7.0,
  baseSlippage: 0.00002,
  allowHedging: true,
  maxPositionsPerSymbol: 10,
  maxTotalPositions: 50,
  instantExecution: true,
  slippageModel: 'REALISTIC',
};

let idSeq = 0;
function genId(prefix: string): string {
  return `${prefix}_${Date.now()}_${++idSeq}`;
}

export class ExecutionEngine {
  private config: ExecutionConfig;
  private eventBus?: TradingEventBus;
  private orders: Map<string, Order> = new Map();
  private positions: Map<string, Position> = new Map();
  private marketConditions: Map<string, MarketConditions> = new Map();

  constructor(config: Partial<ExecutionConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  // =========================================================================
  // MARKET DATA UPDATES
  // =========================================================================

  /**
   * Update market conditions for a symbol (drives fills, trailing, BE, TP/SL)
   */
  updateMarket(conditions: MarketConditions & { symbol: string }): void {
    this.marketConditions.set(conditions.symbol, conditions);
    this.processWorkingOrders(conditions.symbol);
    this.processPositions(conditions.symbol);
  }

  // =========================================================================
  // ORDER SUBMISSION
  // =========================================================================

  /**
   * Submit an order. Returns execution report.
   */
  submitOrder(request: OrderRequest): ExecutionReport {
    // Validate
    const validation = this.validateOrder(request);
    if (!validation.valid) {
      return this.rejectReport(request, validation.reason || 'Validation failed');
    }

    const order = this.createOrder(request);
    this.orders.set(order.id, order);

    // OCO: create linked secondary order
    if (request.type === 'OCO' && request.ocoSecondary) {
      const secondary = this.createOrder(request.ocoSecondary);
      secondary.linkedOrderIds.push(order.id);
      order.linkedOrderIds.push(secondary.id);
      this.orders.set(secondary.id, secondary);
    }

    // Market order fills instantly
    if (order.type === 'MARKET') {
      return this.fillMarketOrder(order);
    }

    // Pending orders go to working state
    order.status = 'WORKING';
    this.emitOrderUpdate(order);

    // Immediately check if a pending order should trigger at current price
    const market = this.marketConditions.get(order.symbol);
    if (market) this.processWorkingOrders(order.symbol);

    return {
      orderId: order.id,
      status: order.status,
      filledVolume: 0,
      avgFillPrice: 0,
      commission: 0,
      slippage: 0,
      timestamp: Date.now(),
      message: `${order.type} order working`,
    };
  }

  /**
   * Cancel an order (and its OCO links)
   */
  cancelOrder(orderId: string): boolean {
    const order = this.orders.get(orderId);
    if (!order || order.status === 'FILLED' || order.status === 'CANCELLED') return false;

    order.status = 'CANCELLED';
    order.updatedAt = Date.now();
    this.emitOrderUpdate(order);

    // Cancel linked OCO orders
    for (const linkedId of order.linkedOrderIds) {
      const linked = this.orders.get(linkedId);
      if (linked && linked.status === 'WORKING') {
        linked.status = 'CANCELLED';
        linked.updatedAt = Date.now();
        this.emitOrderUpdate(linked);
      }
    }
    return true;
  }

  // =========================================================================
  // WORKING ORDER PROCESSING (Limit/Stop/StopLimit/OCO triggers)
  // =========================================================================

  private processWorkingOrders(symbol: string): void {
    const market = this.marketConditions.get(symbol);
    if (!market) return;

    for (const order of this.orders.values()) {
      if (order.symbol !== symbol || order.status !== 'WORKING') continue;

      const triggerPrice = order.side === 'BUY' ? market.ask : market.bid;
      let shouldFill = false;

      switch (order.type) {
        case 'LIMIT':
          // Buy limit fills when price <= limit; sell limit when price >= limit
          shouldFill = order.side === 'BUY'
            ? triggerPrice <= (order.limitPrice ?? 0)
            : triggerPrice >= (order.limitPrice ?? Infinity);
          break;
        case 'STOP':
          // Buy stop fills when price >= stop; sell stop when price <= stop
          shouldFill = order.side === 'BUY'
            ? triggerPrice >= (order.stopPrice ?? Infinity)
            : triggerPrice <= (order.stopPrice ?? 0);
          break;
        case 'STOP_LIMIT':
          // Stop triggers, then behaves as limit
          if (order.side === 'BUY' && triggerPrice >= (order.stopPrice ?? Infinity)) {
            shouldFill = triggerPrice <= (order.limitPrice ?? 0);
          } else if (order.side === 'SELL' && triggerPrice <= (order.stopPrice ?? 0)) {
            shouldFill = triggerPrice >= (order.limitPrice ?? Infinity);
          }
          break;
        case 'OCO':
          // OCO legs are LIMIT or STOP - evaluated as such
          shouldFill = this.evaluateOcoLeg(order, triggerPrice);
          break;
      }

      if (shouldFill) {
        this.fillPendingOrder(order, market);
      } else if (order.timeInForce === 'GTD' && order.expiryTime && Date.now() > order.expiryTime) {
        order.status = 'EXPIRED';
        this.emitOrderUpdate(order);
      }
    }
  }

  private evaluateOcoLeg(order: Order, price: number): boolean {
    if (order.limitPrice !== undefined) {
      return order.side === 'BUY' ? price <= order.limitPrice : price >= order.limitPrice;
    }
    if (order.stopPrice !== undefined) {
      return order.side === 'BUY' ? price >= order.stopPrice : price <= order.stopPrice;
    }
    return false;
  }

  // =========================================================================
  // FILLS
  // =========================================================================

  private fillMarketOrder(order: Order): ExecutionReport {
    const market = this.marketConditions.get(order.symbol);
    if (!market) {
      order.status = 'REJECTED';
      order.rejectReason = 'No market data';
      this.emitOrderUpdate(order);
      return this.rejectReportFromOrder(order, 'No market data');
    }

    const basePrice = order.side === 'BUY' ? market.ask : market.bid;
    const slippage = this.calculateSlippage(order, market);
    const fillPrice = order.side === 'BUY' ? basePrice + slippage : basePrice - slippage;

    return this.executeFill(order, fillPrice, order.volume, slippage, market);
  }

  private fillPendingOrder(order: Order, market: MarketConditions): ExecutionReport {
    const fillPrice = order.type === 'LIMIT' || order.type === 'STOP_LIMIT'
      ? (order.limitPrice ?? (order.side === 'BUY' ? market.ask : market.bid))
      : (order.side === 'BUY' ? market.ask : market.bid);

    const slippage = order.type === 'STOP' ? this.calculateSlippage(order, market) : 0;
    const actualFill = order.side === 'BUY' ? fillPrice + slippage : fillPrice - slippage;

    // Cancel OCO siblings on fill
    for (const linkedId of order.linkedOrderIds) {
      const linked = this.orders.get(linkedId);
      if (linked && linked.status === 'WORKING') {
        linked.status = 'CANCELLED';
        this.emitOrderUpdate(linked);
      }
    }

    return this.executeFill(order, actualFill, order.volume, slippage, market);
  }

  private executeFill(order: Order, fillPrice: number, volume: number, slippage: number, market: MarketConditions): ExecutionReport {
    const commission = this.config.commissionPerLot * volume;
    order.filledVolume = volume;
    order.avgFillPrice = fillPrice;
    order.status = 'FILLED';
    order.filledAt = Date.now();
    order.commission = commission;
    order.slippage = slippage;
    this.emitOrderUpdate(order);

    // Open or scale position
    const position = this.applyFillToPosition(order, fillPrice, volume, commission);

    const report: ExecutionReport = {
      orderId: order.id,
      positionId: position.id,
      status: 'FILLED',
      filledVolume: volume,
      avgFillPrice: fillPrice,
      commission,
      slippage,
      timestamp: Date.now(),
      message: `Filled ${volume} ${order.symbol} @ ${fillPrice.toFixed(5)}`,
    };
    return report;
  }

  // =========================================================================
  // POSITION MANAGEMENT (open, scale in/out, hedge)
  // =========================================================================

  private applyFillToPosition(order: Order, price: number, volume: number, commission: number): Position {
    // Look for an existing position to scale into (same symbol + side, unless hedging)
    const existing = this.findScalablePosition(order.symbol, order.side);

    if (existing) {
      // Scale in - update volume-weighted average entry
      const totalCost = existing.avgEntryPrice * existing.volume + price * volume;
      existing.volume += volume;
      existing.originalVolume += volume;
      existing.avgEntryPrice = totalCost / existing.volume;
      existing.commission += commission;
      existing.fills.push({ price, volume, timestamp: Date.now(), type: 'SCALE_IN' });
      existing.status = 'OPEN';
      this.mergeTPSL(existing, order);
      this.emitPositionUpdate(existing);
      return existing;
    }

    // Create new position
    const position: Position = {
      id: genId('pos'),
      symbol: order.symbol,
      side: order.side,
      direction: order.side === 'BUY' ? 'BULLISH' : 'BEARISH',
      status: 'OPEN',
      volume,
      originalVolume: volume,
      avgEntryPrice: price,
      currentPrice: price,
      realizedPnL: 0,
      unrealizedPnL: 0,
      takeProfits: order.takeProfits.length ? order.takeProfits : [],
      stopLosses: order.stopLosses.length ? order.stopLosses : [],
      trailingStop: this.defaultTrailing(),
      breakEven: this.defaultBreakEven(),
      openedAt: Date.now(),
      fills: [{ price, volume, timestamp: Date.now(), type: 'INITIAL' }],
      commission,
      swap: 0,
      groupId: order.positionId,
      maxFavorable: 0,
      maxAdverse: 0,
    };
    this.positions.set(position.id, position);
    order.positionId = position.id;
    this.emitPositionUpdate(position);
    return position;
  }

  private findScalablePosition(symbol: string, side: OrderSide): Position | null {
    for (const pos of this.positions.values()) {
      if (pos.symbol === symbol && pos.status !== 'CLOSED') {
        if (pos.side === side) return pos; // same direction -> scale in
        if (!this.config.allowHedging) return pos; // netting mode -> reduce/flip
      }
    }
    return null;
  }

  /**
   * Manually scale into a position
   */
  scaleIn(positionId: string, addVolume: number): ExecutionReport | null {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return null;
    return this.submitOrder({
      symbol: pos.symbol,
      type: 'MARKET',
      side: pos.side,
      volume: addVolume,
    });
  }

  /**
   * Scale out - close part of a position
   */
  scaleOut(positionId: string, closeVolume: number): ExecutionReport | null {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return null;
    const market = this.marketConditions.get(pos.symbol);
    if (!market) return null;

    const vol = Math.min(closeVolume, pos.volume);
    const closePrice = pos.side === 'BUY' ? market.bid : market.ask;
    return this.closePartial(pos, vol, closePrice, 'SCALE_OUT');
  }

  /**
   * Close entire position
   */
  closePosition(positionId: string): ExecutionReport | null {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return null;
    const market = this.marketConditions.get(pos.symbol);
    if (!market) return null;
    const closePrice = pos.side === 'BUY' ? market.bid : market.ask;
    return this.closePartial(pos, pos.volume, closePrice, 'SCALE_OUT');
  }

  private closePartial(pos: Position, volume: number, price: number, type: PositionFill['type']): ExecutionReport {
    const pnl = this.computePnL(pos.side, pos.avgEntryPrice, price, volume);
    const commission = this.config.commissionPerLot * volume;

    pos.volume -= volume;
    pos.realizedPnL += pnl - commission;
    pos.commission += commission;
    pos.fills.push({ price, volume, timestamp: Date.now(), type });

    if (pos.volume <= 1e-9) {
      pos.volume = 0;
      pos.status = 'CLOSED';
      pos.closedAt = Date.now();
      pos.unrealizedPnL = 0;
    } else {
      pos.status = 'PARTIALLY_CLOSED';
    }

    this.emitPositionUpdate(pos);

    return {
      orderId: '',
      positionId: pos.id,
      status: 'FILLED',
      filledVolume: volume,
      avgFillPrice: price,
      commission,
      slippage: 0,
      timestamp: Date.now(),
      message: `Closed ${volume} @ ${price.toFixed(5)} | PnL: ${pnl.toFixed(2)}`,
    };
  }


  // =========================================================================
  // POSITION PROCESSING - TP/SL/Trailing/BreakEven on each market update
  // =========================================================================

  private processPositions(symbol: string): void {
    const market = this.marketConditions.get(symbol);
    if (!market) return;

    for (const pos of this.positions.values()) {
      if (pos.symbol !== symbol || pos.status === 'CLOSED') continue;

      const currentPrice = pos.side === 'BUY' ? market.bid : market.ask;
      pos.currentPrice = currentPrice;
      pos.unrealizedPnL = this.computePnL(pos.side, pos.avgEntryPrice, currentPrice, pos.volume);

      // Track excursions
      const profitDistance = pos.side === 'BUY'
        ? currentPrice - pos.avgEntryPrice
        : pos.avgEntryPrice - currentPrice;
      pos.maxFavorable = Math.max(pos.maxFavorable, profitDistance);
      pos.maxAdverse = Math.min(pos.maxAdverse, profitDistance);

      // 1. Break-even automation
      this.processBreakEven(pos, profitDistance);

      // 2. Trailing stop
      this.processTrailingStop(pos, currentPrice, profitDistance);

      // 3. Multiple TP levels (partial closes)
      this.processTakeProfits(pos, currentPrice, market);

      // 4. Multiple SL levels
      this.processStopLosses(pos, currentPrice, market);
    }
  }

  private processBreakEven(pos: Position, profitDistance: number): void {
    const be = pos.breakEven;
    if (!be.enabled || be.triggered) return;

    if (profitDistance >= be.triggerProfit) {
      const bePrice = pos.side === 'BUY'
        ? pos.avgEntryPrice + be.offset
        : pos.avgEntryPrice - be.offset;

      // Move all stop losses to break-even
      for (const sl of pos.stopLosses) {
        const improves = pos.side === 'BUY' ? bePrice > sl.price : bePrice < sl.price;
        if (improves) sl.price = bePrice;
      }
      // If no SL exists, create one at BE
      if (pos.stopLosses.length === 0) {
        pos.stopLosses.push({
          id: genId('sl'), price: bePrice, volumePercent: 100, triggered: false,
        });
      }
      be.triggered = true;
      this.emitPositionUpdate(pos);
    }
  }

  private processTrailingStop(pos: Position, currentPrice: number, profitDistance: number): void {
    const ts = pos.trailingStop;
    if (!ts.enabled) return;

    // Activate once profit threshold reached
    if (!ts.activated && profitDistance >= ts.activationProfit) {
      ts.activated = true;
      ts.currentStopPrice = pos.side === 'BUY'
        ? currentPrice - ts.distance
        : currentPrice + ts.distance;
    }

    if (ts.activated && ts.currentStopPrice !== undefined) {
      const newStop = pos.side === 'BUY'
        ? currentPrice - ts.distance
        : currentPrice + ts.distance;

      // Only move stop in favorable direction, respecting step
      const improves = pos.side === 'BUY'
        ? newStop >= ts.currentStopPrice + ts.step
        : newStop <= ts.currentStopPrice - ts.step;

      if (improves) {
        ts.currentStopPrice = newStop;
      }

      // Check trailing stop hit
      const hit = pos.side === 'BUY'
        ? currentPrice <= ts.currentStopPrice
        : currentPrice >= ts.currentStopPrice;

      if (hit) {
        this.closePartial(pos, pos.volume, ts.currentStopPrice, 'SL');
      }
    }
  }

  private processTakeProfits(pos: Position, currentPrice: number, market: MarketConditions): void {
    for (const tp of pos.takeProfits) {
      if (tp.filled) continue;
      const hit = pos.side === 'BUY' ? currentPrice >= tp.price : currentPrice <= tp.price;
      if (hit) {
        const closeVol = (pos.originalVolume * tp.volumePercent) / 100;
        const actualVol = Math.min(closeVol, pos.volume);
        if (actualVol > 0) {
          this.closePartial(pos, actualVol, tp.price, 'TP');
          tp.filled = true;
          tp.filledAt = Date.now();
        }
      }
    }
  }

  private processStopLosses(pos: Position, currentPrice: number, market: MarketConditions): void {
    for (const sl of pos.stopLosses) {
      if (sl.triggered) continue;
      const hit = pos.side === 'BUY' ? currentPrice <= sl.price : currentPrice >= sl.price;
      if (hit) {
        const closeVol = (pos.originalVolume * sl.volumePercent) / 100;
        const actualVol = Math.min(closeVol, pos.volume);
        if (actualVol > 0) {
          this.closePartial(pos, actualVol, sl.price, 'SL');
          sl.triggered = true;
          sl.triggeredAt = Date.now();
        }
      }
    }
  }

  // =========================================================================
  // HEDGING & BASKET MANAGEMENT
  // =========================================================================

  /**
   * Open a hedge position (opposite direction, same symbol)
   */
  openHedge(positionId: string, hedgeVolume?: number): ExecutionReport | null {
    const pos = this.positions.get(positionId);
    if (!pos || !this.config.allowHedging) return null;
    const oppositeSide: OrderSide = pos.side === 'BUY' ? 'SELL' : 'BUY';
    return this.submitOrder({
      symbol: pos.symbol,
      type: 'MARKET',
      side: oppositeSide,
      volume: hedgeVolume ?? pos.volume,
      groupId: pos.groupId ?? pos.id,
      comment: `Hedge of ${pos.id}`,
    });
  }

  /**
   * Close all positions in a group/basket
   */
  closeGroup(groupId: string): ExecutionReport[] {
    const reports: ExecutionReport[] = [];
    for (const pos of this.positions.values()) {
      if (pos.groupId === groupId && pos.status !== 'CLOSED') {
        const r = this.closePosition(pos.id);
        if (r) reports.push(r);
      }
    }
    return reports;
  }

  /**
   * Close all open positions (panic button)
   */
  closeAll(): ExecutionReport[] {
    const reports: ExecutionReport[] = [];
    for (const pos of this.positions.values()) {
      if (pos.status !== 'CLOSED') {
        const r = this.closePosition(pos.id);
        if (r) reports.push(r);
      }
    }
    return reports;
  }


  // =========================================================================
  // MODIFY POSITION (add/update TP, SL, trailing, break-even)
  // =========================================================================

  /** Add a take-profit level to a position */
  addTakeProfit(positionId: string, price: number, volumePercent: number): boolean {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return false;
    pos.takeProfits.push({ id: genId('tp'), price, volumePercent, filled: false });
    this.emitPositionUpdate(pos);
    return true;
  }

  /** Add a stop-loss level to a position */
  addStopLoss(positionId: string, price: number, volumePercent: number): boolean {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return false;
    pos.stopLosses.push({ id: genId('sl'), price, volumePercent, triggered: false });
    this.emitPositionUpdate(pos);
    return true;
  }

  /** Enable/configure trailing stop */
  setTrailingStop(positionId: string, config: Partial<TrailingStopConfig>): boolean {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return false;
    pos.trailingStop = { ...pos.trailingStop, ...config, enabled: true };
    this.emitPositionUpdate(pos);
    return true;
  }

  /** Enable/configure break-even automation */
  setBreakEven(positionId: string, config: Partial<BreakEvenConfig>): boolean {
    const pos = this.positions.get(positionId);
    if (!pos || pos.status === 'CLOSED') return false;
    pos.breakEven = { ...pos.breakEven, ...config, enabled: true };
    this.emitPositionUpdate(pos);
    return true;
  }

  // =========================================================================
  // HELPERS
  // =========================================================================

  private validateOrder(req: OrderRequest): { valid: boolean; reason?: string } {
    if (req.volume <= 0) return { valid: false, reason: 'Volume must be positive' };
    if (req.type === 'LIMIT' && req.limitPrice === undefined) return { valid: false, reason: 'Limit price required' };
    if (req.type === 'STOP' && req.stopPrice === undefined) return { valid: false, reason: 'Stop price required' };
    if (req.type === 'STOP_LIMIT' && (req.limitPrice === undefined || req.stopPrice === undefined))
      return { valid: false, reason: 'Stop-limit requires both prices' };

    const symbolPositions = Array.from(this.positions.values()).filter(p => p.symbol === req.symbol && p.status !== 'CLOSED');
    if (symbolPositions.length >= this.config.maxPositionsPerSymbol)
      return { valid: false, reason: 'Max positions per symbol reached' };

    const totalOpen = Array.from(this.positions.values()).filter(p => p.status !== 'CLOSED').length;
    if (totalOpen >= this.config.maxTotalPositions)
      return { valid: false, reason: 'Max total positions reached' };

    return { valid: true };
  }

  private createOrder(req: OrderRequest): Order {
    return {
      id: genId('ord'),
      symbol: req.symbol,
      type: req.type,
      side: req.side,
      status: 'PENDING',
      volume: req.volume,
      filledVolume: 0,
      limitPrice: req.limitPrice,
      stopPrice: req.stopPrice,
      timeInForce: req.timeInForce ?? 'GTC',
      expiryTime: req.expiryTime,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      linkedOrderIds: [],
      takeProfits: (req.takeProfits ?? []).map(tp => ({
        id: genId('tp'), price: tp.price, volumePercent: tp.volumePercent, filled: false,
      })),
      stopLosses: (req.stopLosses ?? []).map(sl => ({
        id: genId('sl'), price: sl.price, volumePercent: sl.volumePercent, triggered: false,
      })),
      commission: 0,
      slippage: 0,
      comment: req.comment,
    } as Order;
  }

  private mergeTPSL(pos: Position, order: Order): void {
    if (order.takeProfits.length) pos.takeProfits.push(...order.takeProfits);
    if (order.stopLosses.length) pos.stopLosses.push(...order.stopLosses);
  }

  private calculateSlippage(order: Order, market: MarketConditions): number {
    switch (this.config.slippageModel) {
      case 'FIXED':
        return this.config.baseSlippage;
      case 'PROPORTIONAL':
        return this.config.baseSlippage * (order.volume);
      case 'REALISTIC': {
        // Slippage scales with volume and spread, capped
        const spreadFactor = market.spread * 0.5;
        const volumeFactor = this.config.baseSlippage * Math.sqrt(order.volume);
        const liquidityFactor = market.liquidity ? (1 / Math.max(0.1, market.liquidity)) : 1;
        return Math.min(spreadFactor + volumeFactor * liquidityFactor, market.spread * 3);
      }
      default:
        return this.config.baseSlippage;
    }
  }

  private computePnL(side: OrderSide, entry: number, exit: number, volume: number): number {
    const priceDiff = side === 'BUY' ? exit - entry : entry - exit;
    // Standard lot = 100,000 units. PnL in account currency (simplified)
    return priceDiff * volume * 100000;
  }

  private defaultTrailing(): TrailingStopConfig {
    return { enabled: false, distance: 0, activationProfit: 0, step: 0, activated: false };
  }

  private defaultBreakEven(): BreakEvenConfig {
    return { enabled: false, triggerProfit: 0, offset: 0, triggered: false };
  }

  private rejectReport(req: OrderRequest, reason: string): ExecutionReport {
    return {
      orderId: '', status: 'REJECTED', filledVolume: 0, avgFillPrice: 0,
      commission: 0, slippage: 0, timestamp: Date.now(), message: `Rejected: ${reason}`,
    };
  }

  private rejectReportFromOrder(order: Order, reason: string): ExecutionReport {
    return {
      orderId: order.id, status: 'REJECTED', filledVolume: 0, avgFillPrice: 0,
      commission: 0, slippage: 0, timestamp: Date.now(), message: `Rejected: ${reason}`,
    };
  }

  private emitOrderUpdate(order: Order): void {
    this.eventBus?.emit({ type: 'ORDER_UPDATE', data: order });
  }

  private emitPositionUpdate(pos: Position): void {
    this.eventBus?.emit({ type: 'POSITION_UPDATE', data: pos });
  }

  // =========================================================================
  // PUBLIC GETTERS
  // =========================================================================

  getOrder(id: string): Order | undefined { return this.orders.get(id); }
  getPosition(id: string): Position | undefined { return this.positions.get(id); }
  getOpenPositions(): Position[] { return Array.from(this.positions.values()).filter(p => p.status !== 'CLOSED'); }
  getClosedPositions(): Position[] { return Array.from(this.positions.values()).filter(p => p.status === 'CLOSED'); }
  getWorkingOrders(): Order[] { return Array.from(this.orders.values()).filter(o => o.status === 'WORKING'); }
  getAllPositions(): Position[] { return Array.from(this.positions.values()); }

  /** Aggregate account metrics */
  getAccountSummary(): {
    openPositions: number;
    totalUnrealizedPnL: number;
    totalRealizedPnL: number;
    totalCommission: number;
    totalExposure: number;
  } {
    let unrealized = 0, realized = 0, commission = 0, exposure = 0;
    for (const pos of this.positions.values()) {
      realized += pos.realizedPnL;
      commission += pos.commission;
      if (pos.status !== 'CLOSED') {
        unrealized += pos.unrealizedPnL;
        exposure += pos.volume * pos.avgEntryPrice * 100000;
      }
    }
    return {
      openPositions: this.getOpenPositions().length,
      totalUnrealizedPnL: unrealized,
      totalRealizedPnL: realized,
      totalCommission: commission,
      totalExposure: exposure,
    };
  }

  reset(): void {
    this.orders.clear();
    this.positions.clear();
    this.marketConditions.clear();
  }
}
