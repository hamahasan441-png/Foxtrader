// ============================================================================
// SCREENER & WATCHLIST AI
// Scans: Forex, Crypto, Stocks, Indices, Metals, Energy, Commodities
// Session Engine: Asian/London/NY/Sydney/Open/Close/Overlap/Kill Zones
// Watchlist AI: rank opportunities by Best Buy/Sell/Swing/Scalp/Long-Term
// ============================================================================

import { Candle, Direction, Bias, Timeframe } from '../core/types';
import { calculateATR } from '../core/utils';
import { calculateEMA, calculateADX, calculateRSI, calculateMomentum } from '../indicators/technical';

export type AssetClass = 'FOREX' | 'CRYPTO' | 'STOCKS' | 'INDICES' | 'METALS' | 'ENERGY' | 'COMMODITIES';
export type WatchlistCategory = 'BEST_BUY' | 'BEST_SELL' | 'BEST_SWING' | 'BEST_SCALP' | 'BEST_LONG_TERM';

export interface ScreenerSymbol {
  symbol: string;
  assetClass: AssetClass;
  name?: string;
  enabled: boolean;
}

export interface ScreenerResult {
  symbol: string;
  assetClass: AssetClass;
  direction: Direction;
  score: number;           // 0-100
  bias: Bias;
  trendStrength: number;
  momentum: number;
  volatility: number;
  setupQuality: number;
  categories: WatchlistCategory[];
  tags: string[];
  lastPrice: number;
  changePercent: number;
}

export interface ScreenerOutput {
  results: ScreenerResult[];
  bestBuy: ScreenerResult | null;
  bestSell: ScreenerResult | null;
  bestSwing: ScreenerResult | null;
  bestScalp: ScreenerResult | null;
  bestLongTerm: ScreenerResult | null;
  scannedAt: number;
  totalSymbols: number;
}


/** Default watchlist covering all asset classes */
export const DEFAULT_WATCHLIST: ScreenerSymbol[] = [
  // Forex
  { symbol: 'EURUSD', assetClass: 'FOREX', enabled: true },
  { symbol: 'GBPUSD', assetClass: 'FOREX', enabled: true },
  { symbol: 'USDJPY', assetClass: 'FOREX', enabled: true },
  { symbol: 'USDCHF', assetClass: 'FOREX', enabled: true },
  { symbol: 'AUDUSD', assetClass: 'FOREX', enabled: true },
  { symbol: 'NZDUSD', assetClass: 'FOREX', enabled: true },
  { symbol: 'USDCAD', assetClass: 'FOREX', enabled: true },
  { symbol: 'EURGBP', assetClass: 'FOREX', enabled: true },
  { symbol: 'EURJPY', assetClass: 'FOREX', enabled: true },
  { symbol: 'GBPJPY', assetClass: 'FOREX', enabled: true },
  // Crypto
  { symbol: 'BTCUSDT', assetClass: 'CRYPTO', enabled: true },
  { symbol: 'ETHUSDT', assetClass: 'CRYPTO', enabled: true },
  { symbol: 'SOLUSDT', assetClass: 'CRYPTO', enabled: true },
  { symbol: 'BNBUSDT', assetClass: 'CRYPTO', enabled: true },
  { symbol: 'XRPUSDT', assetClass: 'CRYPTO', enabled: true },
  // Stocks
  { symbol: 'AAPL', assetClass: 'STOCKS', enabled: true },
  { symbol: 'MSFT', assetClass: 'STOCKS', enabled: true },
  { symbol: 'NVDA', assetClass: 'STOCKS', enabled: true },
  { symbol: 'TSLA', assetClass: 'STOCKS', enabled: true },
  { symbol: 'AMZN', assetClass: 'STOCKS', enabled: true },
  // Indices
  { symbol: 'US30', assetClass: 'INDICES', enabled: true },
  { symbol: 'NAS100', assetClass: 'INDICES', enabled: true },
  { symbol: 'US500', assetClass: 'INDICES', enabled: true },
  { symbol: 'DE30', assetClass: 'INDICES', enabled: true },
  // Metals
  { symbol: 'XAUUSD', assetClass: 'METALS', enabled: true },
  { symbol: 'XAGUSD', assetClass: 'METALS', enabled: true },
  // Energy
  { symbol: 'WTIUSD', assetClass: 'ENERGY', enabled: true },
  { symbol: 'BRENTUSD', assetClass: 'ENERGY', enabled: true },
  // Commodities
  { symbol: 'NATGAS', assetClass: 'COMMODITIES', enabled: true },
  { symbol: 'COPPER', assetClass: 'COMMODITIES', enabled: true },
];

export class Screener {
  private watchlist: ScreenerSymbol[] = [...DEFAULT_WATCHLIST];

  /**
   * Scan all watchlist symbols using provided candle data.
   * Returns ranked results with category assignments.
   */
  scan(dataMap: Map<string, Candle[]>): ScreenerOutput {
    const results: ScreenerResult[] = [];

    for (const ws of this.watchlist) {
      if (!ws.enabled) continue;
      const candles = dataMap.get(ws.symbol);
      if (!candles || candles.length < 50) continue;

      const result = this.analyzeSymbol(ws, candles);
      results.push(result);
    }

    // Sort by score
    results.sort((a, b) => b.score - a.score);

    // Assign categories
    const buys = results.filter(r => r.direction === 'BULLISH').sort((a, b) => b.score - a.score);
    const sells = results.filter(r => r.direction === 'BEARISH').sort((a, b) => b.score - a.score);
    const swings = [...results].sort((a, b) => b.trendStrength - a.trendStrength);
    const scalps = [...results].sort((a, b) => b.volatility - a.volatility);
    const longterm = [...results].sort((a, b) => b.trendStrength * b.score - a.trendStrength * a.score);

    if (buys[0]) buys[0].categories.push('BEST_BUY');
    if (sells[0]) sells[0].categories.push('BEST_SELL');
    if (swings[0]) swings[0].categories.push('BEST_SWING');
    if (scalps[0]) scalps[0].categories.push('BEST_SCALP');
    if (longterm[0]) longterm[0].categories.push('BEST_LONG_TERM');

    return {
      results,
      bestBuy: buys[0] || null,
      bestSell: sells[0] || null,
      bestSwing: swings[0] || null,
      bestScalp: scalps[0] || null,
      bestLongTerm: longterm[0] || null,
      scannedAt: Date.now(),
      totalSymbols: results.length,
    };
  }

  private analyzeSymbol(ws: ScreenerSymbol, candles: Candle[]): ScreenerResult {
    const last = candles.length - 1;
    const price = candles[last].close;
    const start = Math.max(0, last - 20);
    const changePercent = ((price - candles[start].close) / candles[start].close) * 100;

    // Indicators
    const ema20 = calculateEMA(candles, 20);
    const ema50 = calculateEMA(candles, 50);
    const { adx, plusDI, minusDI } = calculateADX(candles);
    const rsi = calculateRSI(candles);
    const mom = calculateMomentum(candles);
    const atr = calculateATR(candles, 14);

    // Direction
    const bullEMA = ema20[last] > ema50[last];
    const diUp = plusDI[last] > minusDI[last];
    const direction: Direction = (bullEMA && diUp) || changePercent > 0.5 ? 'BULLISH' : 'BEARISH';
    const bias: Bias = direction;

    // Scores
    const trendStrength = Math.min(100, adx[last] * 2);
    const momentum = Math.min(100, 50 + Math.abs(mom[last]) * 5);
    const volatility = Math.min(100, (atr[last] / price) * 100 * 50);
    const rsiScore = Math.abs(rsi[last] - 50);
    const setupQuality = Math.min(100, trendStrength * 0.4 + momentum * 0.3 + rsiScore * 0.3);
    const score = Math.min(100, setupQuality * 0.6 + trendStrength * 0.3 + (bullEMA === diUp ? 10 : 0));

    const tags: string[] = [];
    if (adx[last] > 25) tags.push('TRENDING');
    if (rsi[last] > 70) tags.push('OVERBOUGHT');
    if (rsi[last] < 30) tags.push('OVERSOLD');
    if (volatility > 60) tags.push('HIGH_VOL');
    if (Math.abs(changePercent) > 2) tags.push('MOVER');

    return {
      symbol: ws.symbol, assetClass: ws.assetClass, direction, score, bias,
      trendStrength, momentum, volatility, setupQuality, categories: [],
      tags, lastPrice: price, changePercent,
    };
  }

  // --- Watchlist management ---
  addSymbol(symbol: string, assetClass: AssetClass): void {
    if (!this.watchlist.find(w => w.symbol === symbol)) {
      this.watchlist.push({ symbol, assetClass, enabled: true });
    }
  }

  removeSymbol(symbol: string): void {
    this.watchlist = this.watchlist.filter(w => w.symbol !== symbol);
  }

  toggleSymbol(symbol: string, enabled: boolean): void {
    const s = this.watchlist.find(w => w.symbol === symbol);
    if (s) s.enabled = enabled;
  }

  getWatchlist(): ScreenerSymbol[] { return [...this.watchlist]; }

  getByAssetClass(assetClass: AssetClass): ScreenerSymbol[] {
    return this.watchlist.filter(w => w.assetClass === assetClass);
  }

  setWatchlist(symbols: ScreenerSymbol[]): void {
    this.watchlist = symbols;
  }
}
