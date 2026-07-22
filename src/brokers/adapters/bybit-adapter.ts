// ============================================================================
// BYBIT BROKER ADAPTER — V5 Unified API
// ============================================================================

import { BrokerAdapter, BrokerName, BrokerStatus, BrokerCredentials, BrokerBalance, BrokerPosition, BrokerOrder, BrokerTicker, OrderSide } from '../broker-interface';

export class BybitBrokerAdapter implements BrokerAdapter {
  readonly name: BrokerName = 'BYBIT';
  status: BrokerStatus = 'DISCONNECTED';
  private baseUrl = 'https://api.bybit.com/v5';
  private creds: BrokerCredentials | null = null;

  async connect(creds: BrokerCredentials): Promise<boolean> {
    this.creds = creds; this.status = 'AUTHENTICATING';
    try { await this.req('GET', '/account/wallet-balance?accountType=UNIFIED'); this.status = 'CONNECTED'; return true; }
    catch { this.status = 'ERROR'; return false; }
  }
  async disconnect() { this.status = 'DISCONNECTED'; }

  async getBalance(): Promise<BrokerBalance[]> {
    const d = await this.req('GET', '/account/wallet-balance?accountType=UNIFIED');
    return (d?.result?.list?.[0]?.coin || []).map((c: any) => ({ currency: c.coin, total: parseFloat(c.walletBalance), available: parseFloat(c.availableToWithdraw), margin: parseFloat(c.locked) }));
  }
  async getPositions(): Promise<BrokerPosition[]> {
    const d = await this.req('GET', '/position/list?category=linear&settleCoin=USDT');
    return (d?.result?.list || []).filter((p: any) => parseFloat(p.size) > 0).map((p: any) => ({ symbol: p.symbol, side: p.side as OrderSide, volume: parseFloat(p.size), entryPrice: parseFloat(p.avgPrice), currentPrice: parseFloat(p.markPrice), unrealizedPnL: parseFloat(p.unrealisedPnl), realizedPnL: parseFloat(p.cumRealisedPnl), openedAt: parseInt(p.createdTime) }));
  }
  async getOpenOrders(): Promise<BrokerOrder[]> {
    const d = await this.req('GET', '/order/realtime?category=linear');
    return (d?.result?.list || []).map((o: any) => ({ id: o.orderId, symbol: o.symbol, side: o.side, type: o.orderType, volume: parseFloat(o.qty), price: parseFloat(o.price) || undefined, status: 'OPEN' as const, filledVolume: parseFloat(o.cumExecQty), avgFillPrice: parseFloat(o.avgPrice), createdAt: parseInt(o.createdTime) }));
  }
  async placeOrder(p: { symbol: string; side: OrderSide; type: BrokerOrder['type']; volume: number; price?: number; stopPrice?: number }): Promise<BrokerOrder> {
    const body = { category: 'linear', symbol: p.symbol, side: p.side === 'BUY' ? 'Buy' : 'Sell', orderType: p.type === 'MARKET' ? 'Market' : 'Limit', qty: String(p.volume), price: p.price ? String(p.price) : undefined, triggerPrice: p.stopPrice ? String(p.stopPrice) : undefined };
    const d = await this.req('POST', '/order/create', body);
    return { id: d?.result?.orderId || '', symbol: p.symbol, side: p.side, type: p.type, volume: p.volume, price: p.price, status: 'OPEN', filledVolume: 0, avgFillPrice: 0, createdAt: Date.now() };
  }
  async cancelOrder(id: string): Promise<boolean> { try { await this.req('POST', '/order/cancel', { category: 'linear', orderId: id }); return true; } catch { return false; } }
  async closePosition(symbol: string, volume?: number): Promise<BrokerOrder> { const pos = (await this.getPositions()).find(p => p.symbol === symbol); if (!pos) throw new Error('No position'); return this.placeOrder({ symbol, side: pos.side === 'BUY' ? 'SELL' : 'BUY', type: 'MARKET', volume: volume || pos.volume }); }
  async getTicker(symbol: string): Promise<BrokerTicker> { const d = await this.req('GET', `/market/tickers?category=linear&symbol=${symbol}`); const t = d?.result?.list?.[0]; return { symbol, bid: parseFloat(t?.bid1Price || '0'), ask: parseFloat(t?.ask1Price || '0'), last: parseFloat(t?.lastPrice || '0'), volume24h: parseFloat(t?.volume24h || '0'), timestamp: Date.now() }; }
  async getSymbols(): Promise<string[]> { const d = await this.req('GET', '/market/instruments-info?category=linear'); return (d?.result?.list || []).map((s: any) => s.symbol); }
  subscribeTicker(symbol: string, cb: (t: BrokerTicker) => void): () => void {
    const ws = new WebSocket('wss://stream.bybit.com/v5/public/linear');
    ws.onopen = () => ws.send(JSON.stringify({ op: 'subscribe', args: [`tickers.${symbol}`] }));
    ws.onmessage = (e) => { try { const d = JSON.parse(e.data); if (d.data) cb({ symbol, bid: parseFloat(d.data.bid1Price || '0'), ask: parseFloat(d.data.ask1Price || '0'), last: parseFloat(d.data.lastPrice || '0'), volume24h: 0, timestamp: Date.now() }); } catch {} };
    return () => ws.close();
  }
  private async req(method: string, path: string, body?: any): Promise<any> { const url = `${this.baseUrl}${path}`; const opts: RequestInit = { method, headers: { 'X-BAPI-API-KEY': this.creds?.apiKey || '', 'Content-Type': 'application/json' } }; if (body) opts.body = JSON.stringify(body); const r = await fetch(url, opts); return r.json(); }
}
