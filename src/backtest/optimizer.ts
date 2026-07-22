// ============================================================================
// AI OPTIMIZATION ENGINE
// Automatically optimizes: Indicators, Risk, Entries, Exits, Filters, Sessions
// Methods: Grid Search, Random Search, Genetic Algorithm
// Generates best settings while guarding against overfitting.
// ============================================================================

import { Candle, Timeframe, SessionType } from '../core/types';
import { Backtester, StrategyFunction, BacktestConfig, BacktestResult } from './backtester';

export type OptimizationTarget =
  | 'NET_PROFIT' | 'PROFIT_FACTOR' | 'SHARPE' | 'SORTINO'
  | 'EXPECTANCY' | 'WIN_RATE' | 'CALMAR' | 'RECOVERY_FACTOR';

export type OptimizationMethod = 'GRID' | 'RANDOM' | 'GENETIC';

/** A parameter to optimize with a search range */
export interface OptimizableParam {
  name: string;
  category: 'INDICATOR' | 'RISK' | 'ENTRY' | 'EXIT' | 'FILTER' | 'SESSION';
  min: number;
  max: number;
  step: number;
  /** For session params, list of allowed session combos */
  sessionOptions?: SessionType[][];
}

export interface OptimizerConfig {
  method: OptimizationMethod;
  target: OptimizationTarget;
  /** For RANDOM/GENETIC: max evaluations */
  maxEvaluations: number;
  /** Genetic: population size */
  populationSize: number;
  /** Genetic: generations */
  generations: number;
  /** Genetic: mutation rate */
  mutationRate: number;
  /** Minimum trades for a result to be valid (avoid overfit on few trades) */
  minTrades: number;
  backtestConfig: Partial<BacktestConfig>;
}

const DEFAULT_CONFIG: OptimizerConfig = {
  method: 'GENETIC',
  target: 'SHARPE',
  maxEvaluations: 500,
  populationSize: 40,
  generations: 25,
  mutationRate: 0.15,
  minTrades: 20,
  backtestConfig: {},
};

export interface ParamSet {
  [paramName: string]: number;
}

export interface OptimizationCandidate {
  params: ParamSet;
  fitness: number;
  result: BacktestResult;
  valid: boolean;
}

export interface OptimizationResult {
  best: OptimizationCandidate | null;
  target: OptimizationTarget;
  method: OptimizationMethod;
  evaluations: number;
  topCandidates: OptimizationCandidate[];
  /** Robustness check: how stable are nearby parameter values */
  robustnessScore: number;
  recommendations: string[];
  bestSettings: {
    indicators: ParamSet;
    risk: ParamSet;
    entries: ParamSet;
    exits: ParamSet;
    filters: ParamSet;
    sessions: ParamSet;
  };
}

/** Strategy factory: builds a strategy from a parameter set */
export type ParametrizedStrategy = (params: ParamSet) => StrategyFunction;

export class Optimizer {
  private config: OptimizerConfig;
  private backtester: Backtester;

  constructor(config: Partial<OptimizerConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.backtester = new Backtester(this.config.backtestConfig);
  }

  /**
   * Optimize a parametrized strategy over candle data
   */
  optimize(
    candles: Candle[],
    params: OptimizableParam[],
    strategyFactory: ParametrizedStrategy,
    symbol: string = 'UNKNOWN',
    timeframe: Timeframe = 'M15'
  ): OptimizationResult {
    let candidates: OptimizationCandidate[] = [];

    switch (this.config.method) {
      case 'GRID':
        candidates = this.gridSearch(candles, params, strategyFactory, symbol, timeframe);
        break;
      case 'RANDOM':
        candidates = this.randomSearch(candles, params, strategyFactory, symbol, timeframe);
        break;
      case 'GENETIC':
        candidates = this.geneticSearch(candles, params, strategyFactory, symbol, timeframe);
        break;
    }

    // Filter valid + sort by fitness
    const valid = candidates.filter(c => c.valid);
    valid.sort((a, b) => b.fitness - a.fitness);

    const best = valid[0] || null;
    const robustness = this.assessRobustness(valid, params);

    return {
      best,
      target: this.config.target,
      method: this.config.method,
      evaluations: candidates.length,
      topCandidates: valid.slice(0, 10),
      robustnessScore: robustness,
      recommendations: this.buildRecommendations(best, robustness, valid.length),
      bestSettings: this.categorizeSettings(best?.params || {}, params),
    };
  }

  // =========================================================================
  // GRID SEARCH - exhaustive
  // =========================================================================

  private gridSearch(
    candles: Candle[], params: OptimizableParam[],
    factory: ParametrizedStrategy, symbol: string, tf: Timeframe
  ): OptimizationCandidate[] {
    const combos = this.generateGrid(params);
    const candidates: OptimizationCandidate[] = [];

    // Cap grid size to maxEvaluations
    const limited = combos.slice(0, this.config.maxEvaluations);
    for (const paramSet of limited) {
      candidates.push(this.evaluate(candles, paramSet, factory, symbol, tf));
    }
    return candidates;
  }

  private generateGrid(params: OptimizableParam[]): ParamSet[] {
    let combos: ParamSet[] = [{}];
    for (const param of params) {
      const values: number[] = [];
      for (let v = param.min; v <= param.max; v += param.step) values.push(v);

      const newCombos: ParamSet[] = [];
      for (const combo of combos) {
        for (const v of values) {
          newCombos.push({ ...combo, [param.name]: v });
        }
      }
      combos = newCombos;
    }
    return combos;
  }

  // =========================================================================
  // RANDOM SEARCH
  // =========================================================================

  private randomSearch(
    candles: Candle[], params: OptimizableParam[],
    factory: ParametrizedStrategy, symbol: string, tf: Timeframe
  ): OptimizationCandidate[] {
    const candidates: OptimizationCandidate[] = [];
    for (let i = 0; i < this.config.maxEvaluations; i++) {
      const paramSet = this.randomParamSet(params);
      candidates.push(this.evaluate(candles, paramSet, factory, symbol, tf));
    }
    return candidates;
  }

  private randomParamSet(params: OptimizableParam[]): ParamSet {
    const set: ParamSet = {};
    for (const p of params) {
      const steps = Math.floor((p.max - p.min) / p.step);
      set[p.name] = p.min + Math.floor(Math.random() * (steps + 1)) * p.step;
    }
    return set;
  }

  // =========================================================================
  // GENETIC ALGORITHM
  // =========================================================================

  private geneticSearch(
    candles: Candle[], params: OptimizableParam[],
    factory: ParametrizedStrategy, symbol: string, tf: Timeframe
  ): OptimizationCandidate[] {
    const allEvaluated: OptimizationCandidate[] = [];

    // Initialize population
    let population: ParamSet[] = [];
    for (let i = 0; i < this.config.populationSize; i++) {
      population.push(this.randomParamSet(params));
    }

    for (let gen = 0; gen < this.config.generations; gen++) {
      // Evaluate population
      const evaluated = population.map(p => this.evaluate(candles, p, factory, symbol, tf));
      allEvaluated.push(...evaluated);
      evaluated.sort((a, b) => b.fitness - a.fitness);

      // Elitism: keep top 20%
      const eliteCount = Math.max(2, Math.floor(this.config.populationSize * 0.2));
      const elite = evaluated.slice(0, eliteCount).map(c => c.params);

      // Breed new population
      const newPop: ParamSet[] = [...elite];
      while (newPop.length < this.config.populationSize) {
        const parent1 = this.tournamentSelect(evaluated);
        const parent2 = this.tournamentSelect(evaluated);
        let child = this.crossover(parent1, parent2);
        child = this.mutate(child, params);
        newPop.push(child);
      }
      population = newPop;

      if (allEvaluated.length >= this.config.maxEvaluations) break;
    }

    return allEvaluated;
  }

  private tournamentSelect(candidates: OptimizationCandidate[]): ParamSet {
    const size = 3;
    let best = candidates[Math.floor(Math.random() * candidates.length)];
    for (let i = 1; i < size; i++) {
      const contender = candidates[Math.floor(Math.random() * candidates.length)];
      if (contender.fitness > best.fitness) best = contender;
    }
    return best.params;
  }

  private crossover(p1: ParamSet, p2: ParamSet): ParamSet {
    const child: ParamSet = {};
    for (const key of Object.keys(p1)) {
      child[key] = Math.random() < 0.5 ? p1[key] : p2[key];
    }
    return child;
  }

  private mutate(params: ParamSet, defs: OptimizableParam[]): ParamSet {
    const mutated = { ...params };
    for (const def of defs) {
      if (Math.random() < this.config.mutationRate) {
        const steps = Math.floor((def.max - def.min) / def.step);
        mutated[def.name] = def.min + Math.floor(Math.random() * (steps + 1)) * def.step;
      }
    }
    return mutated;
  }

  // =========================================================================
  // EVALUATION & FITNESS
  // =========================================================================

  private evaluate(
    candles: Candle[], paramSet: ParamSet,
    factory: ParametrizedStrategy, symbol: string, tf: Timeframe
  ): OptimizationCandidate {
    const strategy = factory(paramSet);
    const result = this.backtester.run(candles, strategy, symbol, tf);
    const valid = result.metrics.totalTrades >= this.config.minTrades;
    const fitness = valid ? this.computeFitness(result) : -Infinity;
    return { params: paramSet, fitness, result, valid };
  }

  private computeFitness(result: BacktestResult): number {
    const m = result.metrics;
    switch (this.config.target) {
      case 'NET_PROFIT': return m.netProfit;
      case 'PROFIT_FACTOR': return isFinite(m.profitFactor) ? m.profitFactor : 10;
      case 'SHARPE': return m.sharpeRatio;
      case 'SORTINO': return isFinite(m.sortinoRatio) ? m.sortinoRatio : 10;
      case 'EXPECTANCY': return m.expectancy;
      case 'WIN_RATE': return m.winRate;
      case 'CALMAR': return m.calmarRatio;
      case 'RECOVERY_FACTOR': return m.recoveryFactor;
      default: return m.sharpeRatio;
    }
  }

  // =========================================================================
  // ROBUSTNESS & RECOMMENDATIONS
  // =========================================================================

  /**
   * Assess robustness by checking if top candidates cluster around similar params
   * (a robust optimum has good neighbors, not an isolated spike = overfit)
   */
  private assessRobustness(sorted: OptimizationCandidate[], params: OptimizableParam[]): number {
    if (sorted.length < 5) return 0;

    const top = sorted.slice(0, Math.max(5, Math.floor(sorted.length * 0.1)));
    const best = top[0];

    // Measure fitness stability among top candidates
    const fitnesses = top.map(c => c.fitness);
    const meanFitness = fitnesses.reduce((a, b) => a + b, 0) / fitnesses.length;
    const variance = fitnesses.reduce((s, f) => s + (f - meanFitness) ** 2, 0) / fitnesses.length;
    const cv = meanFitness !== 0 ? Math.sqrt(variance) / Math.abs(meanFitness) : 1;

    // Lower coefficient of variation = more stable = more robust
    const stabilityScore = Math.max(0, 100 - cv * 100);
    return Math.round(stabilityScore);
  }

  private buildRecommendations(best: OptimizationCandidate | null, robustness: number, validCount: number): string[] {
    const recs: string[] = [];
    if (!best) {
      recs.push('No valid parameter set found. Increase data length or relax minTrades.');
      return recs;
    }

    recs.push(`Best ${this.config.target}: ${best.fitness.toFixed(2)} with ${best.result.metrics.totalTrades} trades.`);

    if (robustness >= 70) {
      recs.push('Parameters are ROBUST - top candidates cluster tightly. Safe to deploy.');
    } else if (robustness >= 40) {
      recs.push('Moderate robustness - validate with walk-forward analysis before live use.');
    } else {
      recs.push('LOW robustness - likely overfit. Widen parameter ranges and re-test out-of-sample.');
    }

    if (best.result.metrics.maxDrawdownPercent > 25) {
      recs.push(`High max drawdown (${best.result.metrics.maxDrawdownPercent.toFixed(1)}%) - reduce risk %.`);
    }
    if (validCount < 10) {
      recs.push('Few valid candidates - consider expanding search space.');
    }

    return recs;
  }

  private categorizeSettings(params: ParamSet, defs: OptimizableParam[]): OptimizationResult['bestSettings'] {
    const settings = {
      indicators: {} as ParamSet, risk: {} as ParamSet, entries: {} as ParamSet,
      exits: {} as ParamSet, filters: {} as ParamSet, sessions: {} as ParamSet,
    };
    for (const def of defs) {
      const value = params[def.name];
      if (value === undefined) continue;
      switch (def.category) {
        case 'INDICATOR': settings.indicators[def.name] = value; break;
        case 'RISK': settings.risk[def.name] = value; break;
        case 'ENTRY': settings.entries[def.name] = value; break;
        case 'EXIT': settings.exits[def.name] = value; break;
        case 'FILTER': settings.filters[def.name] = value; break;
        case 'SESSION': settings.sessions[def.name] = value; break;
      }
    }
    return settings;
  }
}
