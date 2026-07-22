// ============================================================================
// ALPACA BROKER ADAPTER — US Stocks/Crypto commission-free
// ============================================================================

import { BrokerAdapter, BrokerName, BrokerStatus, BrokerCredentials, BrokerBalance, BrokerPosition, BrokerOrder, BrokerTicker, OrderSide } from '../broker-interface';

export class AlpacaBrokerAdapter implements BrokerAdapter {
  readonly name: BrokerName = 'ALPACA';
  status: BrokerStatus = 'DISCONNECTED';
  private baseUrl = 'https://paper-api.alpaca.markets/v2'; // Paper by default
  private dataUrl = 'https://data.alpaca.markets/v2';
  private creds: BrokerCredentials | null = null;

  async connect(creds: BrokerCredentials): Promise<boolean> {
    this.creds = creds;
    if (!creds.sandbox) this.baseUrl = 'https://api.alpaca.markets/v2';
    this.status = 'AUTHENTICATING';
    try { const r = await this.req('GET', '/account'); this.status = r?.status === 'ACTIVE' ? 'CONNECTED' : 'ERROR'; return this.status === 'CONNECTED'; }
    catch { this.status = 'ERROR'; return false; }
  }
  async disconnect() { this.status = 'DISCONNECTED'; }

  async getBalance(): Promise<BrokerBalance[]> {
    const d = await this.req('GET', '/account');
    return [{ currency: 'USD', total: parseFloat(d.equity), available: parseFloat(d.buying_power), margin: parseFloat(d.initial_margin || '0') }];
  }
  async getPositions(): Promise<BrokerPosition[]> {
    const d = await this.req('GET', '/positions');
    return (d || []).map((p: any) => ({ symbol: p.symbol, side: p.side === 'long' ? 'BUY' : 'SELL' as OrderSide, volume: parseFloat(p.qty), entryPrice: parseFloat(p.avg_entry_price), currentPrice: parseFloat(p.current_price), unrealizedPnL: parseFloat(p.unrealized_pl), realizedPnL: 0, openedAt: Date.now() }));
  }
  async getOpenOrders(): Promise<BrokerOrder[]> {
    const d = await this.req('GET', '/orders?status=open');
    return (d || []).map((o: any) => ({ id: o.id, symbol: o.symbol, side: o.side === 'buy' ? 'BUY' : 'SELL' as OrderSide, type: o.type.toUpperCase(), volume: parseFloat(o.qty), price: parseFloat(o.limit_price) || undefined, status: 'OPEN' as const, filledVolume: parseFloat(o.filled_qty), avgFillPrice: parseFloat(o.filled_avg_price || '0'), createdAt: new Date(o.created_at).getTime() }));
  }
  async placeOrder(p: { symbol: string; side: OrderSide; type: BrokerOrder['type']; volume: number; price?: number; stopPrice?: number }): Promise<BrokerOrder> {
    const body: any = { symbol: p.symbol, qty: p.volume, side: p.side.toLowerCase(), type: p.type.toLowerCase(), time_in_force: 'gtc' };
    if (p.price) body.limit_price = p.price;
    if (p.stopPrice) body.stop_price = p.stopPrice;
    const d = await this.req('POST', '/orders', body);
    return { id: d.id, symbol: d.symbol, side: p.side, type: p.type, volume: p.volume, price: p.price, status: 'OPEN', filledVolume: 0, avgFillPrice: 0, createdAt: Date.now() };
  }
  async cancelOrder(id: string): Promise<boolean> { try { await this.req('DELETE', `/orders/${id}`); return true; } catch { return false; } }
  async closePosition(symbol: string): Promise<BrokerOrder> { const d = await this.req('DELETE', `/positions/${symbol}`); return { id: d?.id || '', symbol, side: 'SELL', type: 'MARKET', volume: 0, status: 'FILLED', filledVolume: 0, avgFillPrice: 0, createdAt: Date.now() }; }
  async getTicker(symbol: string): Promise<BrokerTicker> { const d = await fetch(`${this.dataUrl}/stocks/${symbol}/quotes/latest`, { headers: this.headers() }).then(r => r.json()) as any; const q = d?.quote; return { symbol, bid: q?.bp || 0, ask: q?.ap || 0, last: q?.bp || 0, volume24h: 0, timestamp: Date.now() }; }
  async getSymbols(): Promise<string[]> { const d = await this.req('GET', '/assets?status=active&asset_class=us_equity'); return (d || []).slice(0, 500).map((a: any) => a.symbol); }
  subscribeTicker(symbol: string, cb: (t: BrokerTicker) => void): () => void {
    const ws = new WebSocket('wss://stream.data.alpaca.markets/v2/iex');
    ws.onopen = () => { ws.send(JSON.stringify({ action: 'auth', key: this.creds?.apiKey, secret: this.creds?.apiSecret })); ws.send(JSON.stringify({ action: 'subscribe', quotes: [symbol] })); };
    ws.onmessage = (e) => { try { const msgs = JSON.parse(e.data); for (const m of msgs) { if (m.T === 'q') cb({ symbol, bid: m.bp, ask: m.ap, last: m.bp, volume24h: 0, timestamp: Date.now() }); } } catch {} };
    return () => ws.close();
  }
  private headers() { return { 'APCA-API-KEY-ID': this.creds?.apiKey || '', 'APCA-API-SECRET-KEY': this.creds?.apiSecret || '' }; }
  private async req(method: string, path: string, body?: any): Promise<any> { const opts: RequestInit = { method, headers: { ...this.headers(), 'Content-Type': 'application/json' } }; if (body) opts.body = JSON.stringify(body); const r = await fetch(`${this.baseUrl}${path}`, opts); return r.json(); }
}
