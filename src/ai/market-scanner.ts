// ============================================================================
// MARKET SCANNER WITH RANKING
// Scans all watchlist symbols. Finds: Best Buy, Best Sell, Highest Probability,
// Strongest Trend, Reversal, Breakout, Liquidity Grab, SMT, LIT, ICT.
// Produces ranked results.
// ============================================================================

import { Candle, Direction, Bias, Timeframe } from '../core/types';
import {
  StructureBreak, OrderBlock, FairValueGap, LiquiditySweep,
  LiquidityLevel, SMTDivergence, LITSetup, TradingSession, PremiumDiscount, OTE, KillZone,
} from '../core/types';
import { ProbabilityEngine, ProbabilityResult } from './probability-engine';
import { ConfluenceEngine, ConfluenceResult } from './confluence-engine';
import { calculateADX, calculateEMA } from '../indicators/technical';

export type ScanCategory =
  | 'BEST_BUY' | 'BEST_SELL' | 'HIGHEST_PROBABILITY' | 'STRONGEST_TREND'
  | 'REVERSAL' | 'BREAKOUT' | 'LIQUIDITY_GRAB' | 'SMT' | 'LIT' | 'ICT';

export interface SymbolAnalysisBundle {
  symbol: string;
  timeframe: Timeframe;
  candles: Candle[];
  currentPrice: number;
  structureBreaks: StructureBreak[];
  orderBlocks: OrderBlock[];
  fvgs: FairValueGap[];
  sweeps: LiquiditySweep[];
  liquidityLevels: LiquidityLevel[];
  smtDivergences: SMTDivergence[];
  litSetups: LITSetup[];
  sessions: TradingSession[];
  premiumDiscount?: PremiumDiscount;
  ote?: OTE;
  killZones: KillZone[];
  htfBias: Bias;
}

export interface ScanResultEntry {
  symbol: string;
  timeframe: Timeframe;
  direction: Direction;
  confidence: number;
  probability: ProbabilityResult;
  confluence: ConfluenceResult;
  categories: ScanCategory[];
  price: number;
  rank: number;
  tags: string[];
}

export interface ScanRanking {
  scannedAt: number;
  totalSymbols: number;
  bestBuy: ScanResultEntry | null;
  bestSell: ScanResultEntry | null;
  highestProbability: ScanResultEntry | null;
  strongestTrend: ScanResultEntry | null;
  byCategory: Map<ScanCategory, ScanResultEntry[]>;
  ranked: ScanResultEntry[];
}

export class MarketScanner {
  private probabilityEngine: ProbabilityEngine;
  private confluenceEngine: ConfluenceEngine;

  constructor() {
    this.probabilityEngine = new ProbabilityEngine();
    this.confluenceEngine = new ConfluenceEngine();
  }

  /**
   * Scan a watchlist of pre-analyzed symbols and produce ranked results
   */
  scan(bundles: SymbolAnalysisBundle[]): ScanRanking {
    const entries: ScanResultEntry[] = [];

    for (const bundle of bundles) {
      if (bundle.candles.length < 50) continue;

      // Evaluate both directions and take the stronger
      const bullish = this.evaluateDirection(bundle, 'BULLISH');
      const bearish = this.evaluateDirection(bundle, 'BEARISH');
      const best = bullish.confidence >= bearish.confidence ? bullish : bearish;

      if (best.confidence >= 40) { // Minimum threshold for inclusion
        entries.push(best);
      }
    }

    // Rank by confidence
    entries.sort((a, b) => b.confidence - a.confidence);
    entries.forEach((e, i) => (e.rank = i + 1));

    return this.buildRanking(entries, bundles.length);
  }

  /**
   * Evaluate a symbol in a specific direction
   */
  private evaluateDirection(bundle: SymbolAnalysisBundle, direction: Direction): ScanResultEntry {
    const probability = this.probabilityEngine.calculate({
      candles: bundle.candles,
      direction,
      currentPrice: bundle.currentPrice,
      structureBreaks: bundle.structureBreaks,
      orderBlocks: bundle.orderBlocks,
      fvgs: bundle.fvgs,
      sweeps: bundle.sweeps,
      liquidityLevels: bundle.liquidityLevels,
      smtDivergences: bundle.smtDivergences,
      litSetups: bundle.litSetups,
      htfBias: bundle.htfBias,
    });

    const confluence = this.confluenceEngine.analyze({
      candles: bundle.candles,
      direction,
      currentPrice: bundle.currentPrice,
      structureBreaks: bundle.structureBreaks,
      orderBlocks: bundle.orderBlocks,
      fvgs: bundle.fvgs,
      sweeps: bundle.sweeps,
      smtDivergences: bundle.smtDivergences,
      litSetups: bundle.litSetups,
      ote: bundle.ote,
      killZones: bundle.killZones,
      sessions: bundle.sessions,
      premiumDiscount: bundle.premiumDiscount,
      htfBias: bundle.htfBias,
    });

    // Blended confidence
    const confidence = Math.round(probability.confidence * 0.6 + confluence.totalScore * 0.4);
    const categories = this.classifyCategories(bundle, direction, probability, confluence);

    return {
      symbol: bundle.symbol,
      timeframe: bundle.timeframe,
      direction,
      confidence,
      probability,
      confluence,
      categories,
      price: bundle.currentPrice,
      rank: 0,
      tags: this.buildTags(bundle, direction, confluence),
    };
  }

  /**
   * Classify which scan categories this setup qualifies for
   */
  private classifyCategories(
    bundle: SymbolAnalysisBundle, direction: Direction,
    probability: ProbabilityResult, confluence: ConfluenceResult
  ): ScanCategory[] {
    const categories: ScanCategory[] = [];

    // Best buy/sell
    if (direction === 'BULLISH' && probability.confidence >= 60) categories.push('BEST_BUY');
    if (direction === 'BEARISH' && probability.confidence >= 60) categories.push('BEST_SELL');

    // Highest probability
    if (probability.confidence >= 70) categories.push('HIGHEST_PROBABILITY');

    // Strongest trend
    const { adx } = calculateADX(bundle.candles);
    if (adx[adx.length - 1] > 30 && probability.scores.trendStrength >= 65) {
      categories.push('STRONGEST_TREND');
    }

    // Reversal - CHOCH against prior trend + sweep
    const hasChoch = bundle.structureBreaks.some(b => (b.type === 'CHOCH' || b.type === 'MSS') && b.direction === direction);
    const hasSweep = bundle.sweeps.some(s => s.recovered);
    if (hasChoch && hasSweep) categories.push('REVERSAL');

    // Breakout - recent BOS with strong momentum
    const recentBos = bundle.structureBreaks.filter(b => b.type === 'BOS' && b.direction === direction);
    if (recentBos.length > 0 && probability.scores.momentumScore >= 60) categories.push('BREAKOUT');

    // Liquidity grab
    if (bundle.sweeps.filter(s => s.recovered).length > 0) categories.push('LIQUIDITY_GRAB');

    // SMT
    if (bundle.smtDivergences.some(d => d.direction === direction)) categories.push('SMT');

    // LIT
    if (bundle.litSetups.some(l => l.direction === direction && l.confidence >= 60)) categories.push('LIT');

    // ICT - OTE + kill zone + premium/discount alignment
    const ictConfluence = confluence.items.filter(i =>
      ['OTE', 'KILL_ZONE', 'PREMIUM_DISCOUNT', 'ORDER_BLOCK', 'FVG'].includes(i.factor) && i.aligned
    ).length;
    if (ictConfluence >= 3) categories.push('ICT');

    return categories;
  }

  private buildTags(bundle: SymbolAnalysisBundle, direction: Direction, confluence: ConfluenceResult): string[] {
    const tags: string[] = [confluence.grade];
    if (bundle.htfBias === direction) tags.push('HTF-ALIGNED');
    if (bundle.premiumDiscount) {
      const pd = bundle.premiumDiscount.currentPosition;
      if ((direction === 'BULLISH' && pd === 'DISCOUNT') || (direction === 'BEARISH' && pd === 'PREMIUM')) {
        tags.push('PD-OPTIMAL');
      }
    }
    if (bundle.killZones.length > 0) tags.push('KILLZONE');
    return tags;
  }

  /**
   * Build the final ranking structure
   */
  private buildRanking(entries: ScanResultEntry[], totalSymbols: number): ScanRanking {
    const byCategory = new Map<ScanCategory, ScanResultEntry[]>();
    const allCategories: ScanCategory[] = [
      'BEST_BUY', 'BEST_SELL', 'HIGHEST_PROBABILITY', 'STRONGEST_TREND',
      'REVERSAL', 'BREAKOUT', 'LIQUIDITY_GRAB', 'SMT', 'LIT', 'ICT',
    ];

    for (const cat of allCategories) {
      const catEntries = entries
        .filter(e => e.categories.includes(cat))
        .sort((a, b) => b.confidence - a.confidence);
      byCategory.set(cat, catEntries);
    }

    const buys = entries.filter(e => e.direction === 'BULLISH').sort((a, b) => b.confidence - a.confidence);
    const sells = entries.filter(e => e.direction === 'BEARISH').sort((a, b) => b.confidence - a.confidence);

    // Strongest trend
    const strongestTrend = [...entries]
      .filter(e => e.categories.includes('STRONGEST_TREND'))
      .sort((a, b) => b.probability.scores.trendStrength - a.probability.scores.trendStrength)[0] || null;

    return {
      scannedAt: Date.now(),
      totalSymbols,
      bestBuy: buys[0] || null,
      bestSell: sells[0] || null,
      highestProbability: entries[0] || null,
      strongestTrend,
      byCategory,
      ranked: entries,
    };
  }
}
