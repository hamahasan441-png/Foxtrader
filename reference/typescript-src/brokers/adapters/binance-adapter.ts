// ============================================================================
// BINANCE BROKER ADAPTER
// Spot + Futures trading via REST API + WebSocket streaming
// ============================================================================

import { BrokerAdapter, BrokerName, BrokerStatus, BrokerCredentials, BrokerBalance, BrokerPosition, BrokerOrder, BrokerTicker, OrderSide } from '../broker-interface';

export class BinanceBrokerAdapter implements BrokerAdapter {
  readonly name: BrokerName = 'BINANCE';
  status: BrokerStatus = 'DISCONNECTED';
  private baseUrl = 'https://api.binance.com/api/v3';
  private futuresUrl = 'https://fapi.binance.com/fapi/v1';
  private creds: BrokerCredentials | null = null;
  private wsConnections: Map<string, WebSocket> = new Map();

  async connect(credentials: BrokerCredentials): Promise<boolean> {
    this.creds = credentials;
    this.status = 'AUTHENTICATING';
    try {
      const res = await this.signedRequest('GET', '/account');
      this.status = res ? 'CONNECTED' : 'ERROR';
      return this.status === 'CONNECTED';
    } catch { this.status = 'ERROR'; return false; }
  }

  async disconnect(): Promise<void> {
    for (const ws of this.wsConnections.values()) ws.close();
    this.wsConnections.clear();
    this.status = 'DISCONNECTED';
  }

  async getBalance(): Promise<BrokerBalance[]> {
    const data = await this.signedRequest('GET', '/account');
    return (data?.balances || [])
      .filter((b: any) => parseFloat(b.free) > 0 || parseFloat(b.locked) > 0)
      .map((b: any) => ({
        currency: b.asset, total: parseFloat(b.free) + parseFloat(b.locked),
        available: parseFloat(b.free), margin: parseFloat(b.locked),
      }));
  }

  async getPositions(): Promise<BrokerPosition[]> {
    const data = await this.signedRequest('GET', '/positionRisk', this.futuresUrl);
    return (data || []).filter((p: any) => parseFloat(p.positionAmt) !== 0).map((p: any) => ({
      symbol: p.symbol, side: parseFloat(p.positionAmt) > 0 ? 'BUY' : 'SELL' as OrderSide,
      volume: Math.abs(parseFloat(p.positionAmt)), entryPrice: parseFloat(p.entryPrice),
      currentPrice: parseFloat(p.markPrice), unrealizedPnL: parseFloat(p.unRealizedProfit),
      realizedPnL: 0, openedAt: Date.now(),
    }));
  }

  async getOpenOrders(): Promise<BrokerOrder[]> {
    const data = await this.signedRequest('GET', '/openOrders');
    return (data || []).map((o: any) => ({
      id: String(o.orderId), symbol: o.symbol, side: o.side,
      type: o.type, volume: parseFloat(o.origQty), price: parseFloat(o.price) || undefined,
      stopPrice: parseFloat(o.stopPrice) || undefined, status: 'OPEN' as const,
      filledVolume: parseFloat(o.executedQty), avgFillPrice: parseFloat(o.price),
      createdAt: o.time,
    }));
  }

  async placeOrder(params: { symbol: string; side: OrderSide; type: BrokerOrder['type']; volume: number; price?: number; stopPrice?: number }): Promise<BrokerOrder> {
    const body: any = {
      symbol: params.symbol, side: params.side, type: params.type,
      quantity: params.volume, timeInForce: params.type === 'LIMIT' ? 'GTC' : undefined,
      price: params.price, stopPrice: params.stopPrice,
    };
    const data = await this.signedRequest('POST', '/order', this.baseUrl, body);
    return {
      id: String(data.orderId), symbol: data.symbol, side: data.side,
      type: data.type, volume: parseFloat(data.origQty),
      price: parseFloat(data.price) || undefined, status: data.status === 'FILLED' ? 'FILLED' : 'OPEN',
      filledVolume: parseFloat(data.executedQty), avgFillPrice: parseFloat(data.price),
      createdAt: data.transactTime,
    };
  }

  async cancelOrder(orderId: string): Promise<boolean> {
    try { await this.signedRequest('DELETE', `/order?orderId=${orderId}`); return true; } catch { return false; }
  }

  async closePosition(symbol: string, volume?: number): Promise<BrokerOrder> {
    const positions = await this.getPositions();
    const pos = positions.find(p => p.symbol === symbol);
    if (!pos) throw new Error('No position');
    const side: OrderSide = pos.side === 'BUY' ? 'SELL' : 'BUY';
    return this.placeOrder({ symbol, side, type: 'MARKET', volume: volume || pos.volume });
  }

  async getTicker(symbol: string): Promise<BrokerTicker> {
    const res = await fetch(`${this.baseUrl}/ticker/bookTicker?symbol=${symbol}`);
    const d = await res.json() as any;
    return { symbol, bid: parseFloat(d.bidPrice), ask: parseFloat(d.askPrice), last: parseFloat(d.bidPrice), volume24h: 0, timestamp: Date.now() };
  }

  async getSymbols(): Promise<string[]> {
    const res = await fetch(`${this.baseUrl}/exchangeInfo`);
    const d = await res.json() as any;
    return (d.symbols || []).map((s: any) => s.symbol);
  }

  subscribeTicker(symbol: string, cb: (ticker: BrokerTicker) => void): () => void {
    const ws = new WebSocket(`wss://stream.binance.com:9443/ws/${symbol.toLowerCase()}@bookTicker`);
    ws.onmessage = (e) => {
      const d = JSON.parse(e.data);
      cb({ symbol, bid: parseFloat(d.b), ask: parseFloat(d.a), last: parseFloat(d.b), volume24h: 0, timestamp: Date.now() });
    };
    this.wsConnections.set(symbol, ws);
    return () => { ws.close(); this.wsConnections.delete(symbol); };
  }

  private async signedRequest(method: string, path: string, base?: string, body?: any): Promise<any> {
    if (!this.creds) throw new Error('Not authenticated');
    const timestamp = Date.now();
    const queryStr = body ? new URLSearchParams({ ...body, timestamp: String(timestamp) }).toString() : `timestamp=${timestamp}`;
    // In production: HMAC-SHA256 signature with this.creds.apiSecret
    const url = `${base || this.baseUrl}${path}${path.includes('?') ? '&' : '?'}${queryStr}&signature=PLACEHOLDER`;
    const res = await fetch(url, { method, headers: { 'X-MBX-APIKEY': this.creds.apiKey } });
    return res.json();
  }
}
