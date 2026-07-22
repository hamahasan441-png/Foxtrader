// ============================================================================
// TRADING TEMPLATES MODULE
// Scalp 1M/3M/5M, Intraday 15M/30M, Swing 1H/4H, Daily, Weekly, Long-Term
// Each template auto-loads: Indicators, Colors, Risk, Alerts, Drawing Tools, Rules
// ============================================================================

import {
  TradingTemplate,
  TemplateType,
  Timeframe,
  IndicatorConfig,
  ColorScheme,
  RiskSettings,
  AlertConfig,
  TradingRule,
  ScannerSignalType,
} from '../../core/types';

// --- Professional Dark Color Scheme ---
const DARK_COLORS: ColorScheme = {
  background: '#0a0e17',
  candles: { bullish: '#00dc82', bearish: '#ff4757', wick: '#4a5568' },
  structure: { bos: '#3b82f6', choch: '#f59e0b', mss: '#ef4444' },
  liquidity: { bsl: '#06b6d4', ssl: '#8b5cf6', sweep: '#f43f5e' },
  orderBlocks: { bullish: '#10b98133', bearish: '#ef444433', breaker: '#f59e0b33', mitigation: '#6366f133' },
  fvg: { bullish: '#00dc8220', bearish: '#ff475720', ifvg: '#a78bfa20' },
  sessions: { asian: '#6366f115', london: '#3b82f615', newYork: '#f59e0b15', sydney: '#10b98115' },
  premium: '#ef444420',
  discount: '#10b98120',
  entry: '#00dc82',
  stopLoss: '#ef4444',
  takeProfit: '#3b82f6',
};

// --- Base Alert Configs ---
function createAlerts(signals: ScannerSignalType[], minConfidence: number): AlertConfig[] {
  return signals.map(type => ({
    type,
    enabled: true,
    sound: true,
    popup: true,
    minConfidence,
  }));
}

// --- Template Definitions ---


const SCALP_1M: TradingTemplate = {
  name: 'Scalp 1M',
  type: 'SCALP_1M',
  timeframe: 'M1',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 2, swingRight: 2, type: 'INTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 0.8, maxSize: 2.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.05 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.0002 } },
    { name: 'KillZones', enabled: true, params: {} },
    { name: 'Sessions', enabled: true, params: {} },
    { name: 'PremiumDiscount', enabled: true, params: { period: 30 } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 0.5,
    maxDailyLoss: 2.0,
    maxDrawdown: 5.0,
    defaultRiskReward: 3.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'LIT_SETUP'], 70),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'Rectangle', 'TrendLine', 'RiskRewardTool'],
  tradingRules: [
    { name: 'Kill Zone Only', description: 'Only trade during active kill zones', condition: 'killZone.active', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'OTE Entry', description: 'Enter only in OTE zone (61.8-78.6%)', condition: 'price.inOTE', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Max 3 Trades', description: 'Maximum 3 scalp trades per session', condition: 'trades.session < 3', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Structure Aligned', description: 'Trade only in direction of HTF bias', condition: 'bias.aligned', action: 'ALLOW_ENTRY', enabled: true },
  ],
};

const SCALP_3M: TradingTemplate = {
  name: 'Scalp 3M',
  type: 'SCALP_3M',
  timeframe: 'M3',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 3, swingRight: 3, type: 'INTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 1.0, maxSize: 2.5 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.08 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.0003 } },
    { name: 'KillZones', enabled: true, params: {} },
    { name: 'SilverBullet', enabled: true, params: {} },
    { name: 'PremiumDiscount', enabled: true, params: { period: 40 } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 0.75,
    maxDailyLoss: 2.5,
    maxDrawdown: 5.0,
    defaultRiskReward: 3.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP'], 65),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'Rectangle', 'TrendLine', 'RiskRewardTool'],
  tradingRules: [
    { name: 'Silver Bullet Window', description: 'Prioritize Silver Bullet time windows', condition: 'silverBullet.active', action: 'HIGHLIGHT', enabled: true },
    { name: 'Sweep Required', description: 'Require liquidity sweep before entry', condition: 'liquidity.swept', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'FVG Confluence', description: 'Entry must have FVG confluence', condition: 'fvg.present', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Max Daily Loss', description: 'Stop trading after 2.5% daily loss', condition: 'pnl.daily > -2.5', action: 'ALLOW_ENTRY', enabled: true },
  ],
};

const SCALP_5M: TradingTemplate = {
  name: 'Scalp 5M',
  type: 'SCALP_5M',
  timeframe: 'M5',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 3, swingRight: 3, type: 'SWING' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 1.0, maxSize: 3.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.1 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.0003 } },
    { name: 'LIT', enabled: true, params: { minConfidence: 65 } },
    { name: 'SMT', enabled: true, params: {} },
    { name: 'PremiumDiscount', enabled: true, params: { period: 50 } },
    { name: 'PowerOfThree', enabled: true, params: {} },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 1.0,
    maxDailyLoss: 3.0,
    maxDrawdown: 6.0,
    defaultRiskReward: 3.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP', 'REVERSAL'], 60),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine', 'RiskRewardTool', 'SessionBox'],
  tradingRules: [
    { name: 'HTF Bias Alignment', description: 'Align with H1/H4 bias', condition: 'bias.htf.aligned', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'SMT Confluence', description: 'Bonus confidence if SMT divergence present', condition: 'smt.active', action: 'BOOST_CONFIDENCE', enabled: true },
    { name: 'LIT Setup Required', description: 'Require LIT confirmation for entry', condition: 'lit.confirmed', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Avoid News', description: 'No entries 15min before high-impact news', condition: 'news.clear', action: 'ALLOW_ENTRY', enabled: true },
  ],
};


const INTRADAY_15M: TradingTemplate = {
  name: 'Intraday 15M',
  type: 'INTRADAY_15M',
  timeframe: 'M15',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 5, swingRight: 5, type: 'SWING' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 1.2, maxSize: 3.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.15 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.0004 } },
    { name: 'LIT', enabled: true, params: { minConfidence: 60 } },
    { name: 'SMT', enabled: true, params: {} },
    { name: 'ICT', enabled: true, params: { ote: true, silverBullet: true, amd: true } },
    { name: 'Sessions', enabled: true, params: {} },
    { name: 'PremiumDiscount', enabled: true, params: { period: 60 } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 1.0,
    maxDailyLoss: 3.0,
    maxDrawdown: 8.0,
    defaultRiskReward: 3.5,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP', 'BREAKOUT', 'REVERSAL'], 60),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine', 'RiskRewardTool', 'SessionBox', 'PriceRange'],
  tradingRules: [
    { name: 'Session Active', description: 'Only trade during London or NY', condition: 'session.london || session.newYork', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Structure Break', description: 'Wait for BOS/CHOCH before entry', condition: 'structure.broken', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'OB + FVG Stack', description: 'Prefer entries with OB and FVG overlap', condition: 'ob.present && fvg.present', action: 'BOOST_CONFIDENCE', enabled: true },
    { name: 'Discount Entry', description: 'Buy only in discount, sell only in premium', condition: 'pda.aligned', action: 'ALLOW_ENTRY', enabled: true },
  ],
};

const INTRADAY_30M: TradingTemplate = {
  name: 'Intraday 30M',
  type: 'INTRADAY_30M',
  timeframe: 'M30',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 5, swingRight: 5, type: 'EXTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 1.5, maxSize: 3.5 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.2 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.0005 } },
    { name: 'LIT', enabled: true, params: { minConfidence: 60 } },
    { name: 'SMT', enabled: true, params: {} },
    { name: 'ICT', enabled: true, params: { ote: true, amd: true, turtleSoup: true } },
    { name: 'Sessions', enabled: true, params: {} },
    { name: 'PremiumDiscount', enabled: true, params: { period: 80 } },
    { name: 'OpeningGaps', enabled: true, params: {} },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 1.5,
    maxDailyLoss: 4.0,
    maxDrawdown: 10.0,
    defaultRiskReward: 4.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP', 'BREAKOUT', 'REVERSAL', 'TREND_CONTINUATION'], 55),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine', 'RiskRewardTool', 'SessionBox', 'PriceRange', 'ChannelTool'],
  tradingRules: [
    { name: 'Daily Bias Set', description: 'Confirm daily bias before intraday entries', condition: 'bias.daily.set', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'NDOG Respect', description: 'Use NDOG as reference point', condition: 'ndog.valid', action: 'HIGHLIGHT', enabled: true },
    { name: 'Max 2 Trades', description: 'Maximum 2 intraday trades per day', condition: 'trades.daily < 2', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Turtle Soup Alert', description: 'Alert on Turtle Soup setups', condition: 'turtleSoup.detected', action: 'ALERT', enabled: true },
  ],
};


const SWING_1H: TradingTemplate = {
  name: 'Swing 1H',
  type: 'SWING_1H',
  timeframe: 'H1',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 7, swingRight: 7, type: 'EXTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 1.5, maxSize: 4.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.3 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.001 } },
    { name: 'LIT', enabled: true, params: { minConfidence: 55 } },
    { name: 'SMT', enabled: true, params: {} },
    { name: 'ICT', enabled: true, params: { ote: true, amd: true, judasSwing: true, weeklyBias: true } },
    { name: 'PremiumDiscount', enabled: true, params: { period: 100 } },
    { name: 'OpeningGaps', enabled: true, params: { type: 'BOTH' } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 1.5,
    maxDailyLoss: 3.0,
    maxDrawdown: 12.0,
    defaultRiskReward: 4.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP', 'REVERSAL', 'TREND_CONTINUATION'], 55),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine', 'RiskRewardTool', 'PriceRange', 'ChannelTool', 'PitchFork'],
  tradingRules: [
    { name: 'Weekly Bias', description: 'Align with weekly directional bias', condition: 'bias.weekly.aligned', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'D1 Structure', description: 'Confirm D1 structure before H1 entries', condition: 'structure.d1.confirmed', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Sweep + CHOCH', description: 'Require liquidity sweep followed by CHOCH', condition: 'sweep.recent && choch.confirmed', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'WOG Reference', description: 'Use Weekly Opening Gap as reference', condition: 'wog.valid', action: 'HIGHLIGHT', enabled: true },
  ],
};

const SWING_4H: TradingTemplate = {
  name: 'Swing 4H',
  type: 'SWING_4H',
  timeframe: 'H4',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 7, swingRight: 7, type: 'EXTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 2.0, maxSize: 5.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.4 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.002 } },
    { name: 'LIT', enabled: true, params: { minConfidence: 50 } },
    { name: 'SMT', enabled: true, params: {} },
    { name: 'ICT', enabled: true, params: { ote: true, weeklyBias: true, monthlyBias: true } },
    { name: 'PremiumDiscount', enabled: true, params: { period: 120 } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 2.0,
    maxDailyLoss: 4.0,
    maxDrawdown: 15.0,
    defaultRiskReward: 5.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'FVG', 'SMT', 'LIT_SETUP', 'REVERSAL', 'TREND_CONTINUATION'], 50),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine', 'RiskRewardTool', 'PriceRange', 'ChannelTool', 'PitchFork', 'GannBox'],
  tradingRules: [
    { name: 'Monthly Bias', description: 'Align with monthly directional bias', condition: 'bias.monthly.aligned', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'HTF OB', description: 'Only enter at HTF order blocks', condition: 'ob.htf.present', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Patience Rule', description: 'Wait for full retracement to OTE', condition: 'ote.reached', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Multi-TF Confluence', description: 'Require 3+ timeframe confluence', condition: 'confluence.count >= 3', action: 'ALLOW_ENTRY', enabled: true },
  ],
};

const DAILY: TradingTemplate = {
  name: 'Daily',
  type: 'DAILY',
  timeframe: 'D1',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 10, swingRight: 10, type: 'EXTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 2.0, maxSize: 5.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 0.5 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.005 } },
    { name: 'PremiumDiscount', enabled: true, params: { period: 200 } },
    { name: 'SMT', enabled: true, params: {} },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 2.0,
    maxDailyLoss: 3.0,
    maxDrawdown: 15.0,
    defaultRiskReward: 5.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'SMT', 'REVERSAL'], 50),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine', 'PriceRange'],
  tradingRules: [
    { name: 'Monthly Context', description: 'Trade within monthly structure', condition: 'structure.monthly.context', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Weekly Close', description: 'Consider weekly candle close patterns', condition: 'weekly.close.bullish', action: 'BOOST_CONFIDENCE', enabled: true },
  ],
};

const WEEKLY: TradingTemplate = {
  name: 'Weekly',
  type: 'WEEKLY',
  timeframe: 'W1',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 10, swingRight: 10, type: 'EXTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 2.5, maxSize: 6.0 } },
    { name: 'FVG', enabled: true, params: { minSize: 1.0 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.01 } },
    { name: 'PremiumDiscount', enabled: true, params: { period: 52 } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 2.0,
    maxDailyLoss: 5.0,
    maxDrawdown: 20.0,
    defaultRiskReward: 6.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK', 'REVERSAL'], 45),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'FibExtension', 'Rectangle', 'TrendLine'],
  tradingRules: [
    { name: 'Macro Context', description: 'Consider macro-economic context', condition: 'macro.aligned', action: 'ALLOW_ENTRY', enabled: true },
  ],
};

const LONG_TERM: TradingTemplate = {
  name: 'Long-Term Investor',
  type: 'LONG_TERM',
  timeframe: 'MN',
  indicators: [
    { name: 'MarketStructure', enabled: true, params: { swingLeft: 12, swingRight: 12, type: 'EXTERNAL' } },
    { name: 'OrderBlocks', enabled: true, params: { minDisplacement: 3.0, maxSize: 8.0 } },
    { name: 'Liquidity', enabled: true, params: { equalTolerance: 0.02 } },
    { name: 'PremiumDiscount', enabled: true, params: { period: 24 } },
  ],
  colors: DARK_COLORS,
  riskSettings: {
    maxRiskPerTrade: 3.0,
    maxDailyLoss: 10.0,
    maxDrawdown: 25.0,
    defaultRiskReward: 8.0,
    positionSizing: 'PERCENTAGE',
  },
  alerts: createAlerts(['BOS', 'CHOCH', 'ORDER_BLOCK'], 40),
  drawingTools: ['HorizontalLine', 'FibRetracement', 'Rectangle'],
  tradingRules: [
    { name: 'Yearly Bias', description: 'Align with yearly directional bias', condition: 'bias.yearly', action: 'ALLOW_ENTRY', enabled: true },
    { name: 'Deep Discount', description: 'Only accumulate in deep discount zones', condition: 'pda.deepDiscount', action: 'ALLOW_ENTRY', enabled: true },
  ],
};


// --- Template Registry ---

const ALL_TEMPLATES: Map<TemplateType, TradingTemplate> = new Map([
  ['SCALP_1M', SCALP_1M],
  ['SCALP_3M', SCALP_3M],
  ['SCALP_5M', SCALP_5M],
  ['INTRADAY_15M', INTRADAY_15M],
  ['INTRADAY_30M', INTRADAY_30M],
  ['SWING_1H', SWING_1H],
  ['SWING_4H', SWING_4H],
  ['DAILY', DAILY],
  ['WEEKLY', WEEKLY],
  ['LONG_TERM', LONG_TERM],
]);

export class TemplateManager {
  private activeTemplate: TradingTemplate | null = null;
  private customTemplates: Map<string, TradingTemplate> = new Map();

  /**
   * Get a built-in template
   */
  getTemplate(type: TemplateType): TradingTemplate {
    const template = ALL_TEMPLATES.get(type);
    if (!template) throw new Error(`Template ${type} not found`);
    return { ...template };
  }

  /**
   * Load and activate a template
   */
  loadTemplate(type: TemplateType): TradingTemplate {
    this.activeTemplate = this.getTemplate(type);
    return this.activeTemplate;
  }

  /**
   * Get the currently active template
   */
  getActiveTemplate(): TradingTemplate | null {
    return this.activeTemplate;
  }

  /**
   * List all available templates
   */
  listTemplates(): { type: TemplateType; name: string; timeframe: Timeframe }[] {
    return Array.from(ALL_TEMPLATES.entries()).map(([type, tmpl]) => ({
      type,
      name: tmpl.name,
      timeframe: tmpl.timeframe,
    }));
  }

  /**
   * Create a custom template based on an existing one
   */
  createCustomTemplate(name: string, baseType: TemplateType, overrides: Partial<TradingTemplate>): TradingTemplate {
    const base = this.getTemplate(baseType);
    const custom: TradingTemplate = { ...base, ...overrides, name };
    this.customTemplates.set(name, custom);
    return custom;
  }

  /**
   * Update template risk settings
   */
  updateRiskSettings(settings: Partial<RiskSettings>): void {
    if (this.activeTemplate) {
      this.activeTemplate.riskSettings = { ...this.activeTemplate.riskSettings, ...settings };
    }
  }

  /**
   * Toggle an indicator on/off
   */
  toggleIndicator(indicatorName: string, enabled: boolean): void {
    if (!this.activeTemplate) return;
    const indicator = this.activeTemplate.indicators.find(i => i.name === indicatorName);
    if (indicator) indicator.enabled = enabled;
  }

  /**
   * Update color scheme
   */
  updateColors(colors: Partial<ColorScheme>): void {
    if (this.activeTemplate) {
      this.activeTemplate.colors = { ...this.activeTemplate.colors, ...colors };
    }
  }

  /**
   * Get template recommendation based on timeframe
   */
  getRecommendedTemplate(timeframe: Timeframe): TemplateType {
    const map: Record<Timeframe, TemplateType> = {
      'TICK': 'SCALP_1M',
      'M1': 'SCALP_1M',
      'M3': 'SCALP_3M',
      'M5': 'SCALP_5M',
      'M15': 'INTRADAY_15M',
      'M30': 'INTRADAY_30M',
      'H1': 'SWING_1H',
      'H4': 'SWING_4H',
      'D1': 'DAILY',
      'W1': 'WEEKLY',
      'MN': 'LONG_TERM',
    };
    return map[timeframe];
  }
}
