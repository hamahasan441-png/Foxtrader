// ============================================================================
// MULTI-PROVIDER DATA ENGINE
// Primary: Dukascopy | Secondary: Binance, OANDA, Interactive Brokers,
// Polygon.io, Twelve Data, Alpha Vantage
// WebSocket live streaming | Unified interface | Failover | Rate limiting
// ============================================================================

export { DataEngine } from './data-engine';
export { DukascopyProvider } from './providers/dukascopy';
export { BinanceProvider } from './providers/binance';
export { OandaProvider } from './providers/oanda';
export { InteractiveBrokersProvider } from './providers/interactive-brokers';
export { PolygonProvider } from './providers/polygon';
export { TwelveDataProvider } from './providers/twelve-data';
export { AlphaVantageProvider } from './providers/alpha-vantage';
export { WebSocketFeed } from './websocket-feed';
export type { DataProviderInterface, ProviderConfig } from './provider-interface';
