// ============================================================================
// BROKER REGISTRY — Central registry + router for all broker adapters
// New adapters are registered here; core code never imports broker files.
// ============================================================================

import { BrokerAdapter, BrokerName, BrokerCredentials, BrokerStatus } from './broker-interface';
import { BinanceBrokerAdapter } from './adapters/binance-adapter';
import { BybitBrokerAdapter } from './adapters/bybit-adapter';
import { AlpacaBrokerAdapter } from './adapters/alpaca-adapter';
// OKX, OANDA, IB adapters follow the same pattern (stubbed below)

export class BrokerRegistry {
  private adapters: Map<BrokerName, BrokerAdapter> = new Map();
  private activeBroker: BrokerName | null = null;

  constructor() {
    // Register all built-in adapters
    this.register(new BinanceBrokerAdapter());
    this.register(new BybitBrokerAdapter());
    this.register(new AlpacaBrokerAdapter());
    // Stubs for the remaining 3 — same interface, implement when API keys available
    this.register(this.createStub('OKX'));
    this.register(this.createStub('OANDA'));
    this.register(this.createStub('INTERACTIVE_BROKERS'));
  }

  /** Register a new broker adapter (plug-in) */
  register(adapter: BrokerAdapter): void {
    this.adapters.set(adapter.name, adapter);
  }

  /** Connect to a broker and make it active */
  async connect(name: BrokerName, credentials: BrokerCredentials): Promise<boolean> {
    const adapter = this.adapters.get(name);
    if (!adapter) throw new Error(`Broker ${name} not registered`);
    const ok = await adapter.connect(credentials);
    if (ok) this.activeBroker = name;
    return ok;
  }

  /** Get the currently active broker adapter */
  getActive(): BrokerAdapter | null {
    return this.activeBroker ? this.adapters.get(this.activeBroker) || null : null;
  }

  /** Get a specific adapter by name */
  get(name: BrokerName): BrokerAdapter | undefined {
    return this.adapters.get(name);
  }

  /** Get status of all registered brokers */
  getAllStatus(): { name: BrokerName; status: BrokerStatus }[] {
    return Array.from(this.adapters.entries()).map(([name, a]) => ({ name, status: a.status }));
  }

  /** Disconnect all */
  async disconnectAll(): Promise<void> {
    for (const a of this.adapters.values()) await a.disconnect();
    this.activeBroker = null;
  }

  /** List registered broker names */
  getRegistered(): BrokerName[] { return Array.from(this.adapters.keys()); }

  /** Create a stub adapter for brokers not yet fully implemented */
  private createStub(name: BrokerName): BrokerAdapter {
    return {
      name, status: 'DISCONNECTED' as BrokerStatus,
      async connect() { console.warn(`[Broker] ${name} adapter not fully implemented yet`); return false; },
      async disconnect() {},
      async getBalance() { return []; },
      async getPositions() { return []; },
      async getOpenOrders() { return []; },
      async placeOrder() { throw new Error('Not implemented'); },
      async cancelOrder() { return false; },
      async closePosition() { throw new Error('Not implemented'); },
      async getTicker(s) { return { symbol: s, bid: 0, ask: 0, last: 0, volume24h: 0, timestamp: Date.now() }; },
      async getSymbols() { return []; },
      subscribeTicker() { return () => {}; },
    };
  }
}
