// ============================================================================
// VISUALIZATION ENGINE - Converts analysis data to chart annotations
// Draws all concepts directly on the chart: BOS, CHOCH, OB, Breaker,
// Mitigation, FVG, IFVG, Liquidity, Sweep, SMT, LIT, Premium/Discount,
// Kill Zones, OTE, Entry/SL/TP, Target Zones, Invalidations
// 60 FPS optimized | Cleaner than TradingView
// ============================================================================

import {
  ChartAnnotation,
  ChartAnnotationType,
  StructureBreak,
  OrderBlock,
  FairValueGap,
  LiquidityLevel,
  LiquiditySweep,
  LITSetup,
  SMTDivergence,
  PremiumDiscount,
  KillZone,
  OTE,
  TradingSession,
  AIAnalysis,
  ColorScheme,
} from '../core/types';

export interface VisualizationConfig {
  colors: ColorScheme;
  opacity: {
    zones: number;
    lines: number;
    labels: number;
  };
  showLabels: boolean;
  showMitigated: boolean;
  maxVisibleAnnotations: number;
  animateNew: boolean;
}

const DEFAULT_VIS_CONFIG: VisualizationConfig = {
  colors: {
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
  },
  opacity: { zones: 0.2, lines: 0.8, labels: 1.0 },
  showLabels: true,
  showMitigated: false,
  maxVisibleAnnotations: 500,
  animateNew: true,
};


export class VisualizationEngine {
  private config: VisualizationConfig;
  private annotations: ChartAnnotation[] = [];
  private idCounter: number = 0;

  constructor(config: Partial<VisualizationConfig> = {}) {
    this.config = { ...DEFAULT_VIS_CONFIG, ...config };
  }

  /**
   * Convert all analysis results to chart annotations
   */
  generateAnnotations(data: {
    structureBreaks?: StructureBreak[];
    orderBlocks?: OrderBlock[];
    fvgs?: FairValueGap[];
    liquidityLevels?: LiquidityLevel[];
    sweeps?: LiquiditySweep[];
    litSetups?: LITSetup[];
    smtDivergences?: SMTDivergence[];
    premiumDiscount?: PremiumDiscount;
    killZones?: KillZone[];
    ote?: OTE;
    sessions?: TradingSession[];
    aiAnalysis?: AIAnalysis;
  }): ChartAnnotation[] {
    this.annotations = [];

    // Order matters for z-index layering
    if (data.sessions) this.drawSessions(data.sessions);
    if (data.premiumDiscount) this.drawPremiumDiscount(data.premiumDiscount);
    if (data.killZones) this.drawKillZones(data.killZones);
    if (data.fvgs) this.drawFVGs(data.fvgs);
    if (data.orderBlocks) this.drawOrderBlocks(data.orderBlocks);
    if (data.liquidityLevels) this.drawLiquidity(data.liquidityLevels);
    if (data.sweeps) this.drawSweeps(data.sweeps);
    if (data.structureBreaks) this.drawStructure(data.structureBreaks);
    if (data.ote) this.drawOTE(data.ote);
    if (data.litSetups) this.drawLITSetups(data.litSetups);
    if (data.smtDivergences) this.drawSMT(data.smtDivergences);
    if (data.aiAnalysis) this.drawTradeSetup(data.aiAnalysis);

    // Performance limit
    if (this.annotations.length > this.config.maxVisibleAnnotations) {
      this.annotations = this.annotations.slice(-this.config.maxVisibleAnnotations);
    }

    return [...this.annotations];
  }

  /**
   * Draw BOS, CHOCH, MSS structure lines
   */
  private drawStructure(breaks: StructureBreak[]): void {
    for (const brk of breaks) {
      const color = brk.type === 'BOS' ? this.config.colors.structure.bos
        : brk.type === 'CHOCH' ? this.config.colors.structure.choch
        : this.config.colors.structure.mss;

      const label = `${brk.type} ${brk.direction === 'BULLISH' ? '↑' : '↓'}`;

      this.addAnnotation(
        brk.type === 'BOS' ? 'BOS_LINE' : 'CHOCH_LINE',
        {
          startIndex: brk.swingPoint.index,
          endIndex: brk.breakIndex,
          price: brk.breakPrice,
          color,
          label: this.config.showLabels ? label : undefined,
          dashed: brk.type === 'IDM',
        }
      );
    }
  }

  /**
   * Draw Order Block zones
   */
  private drawOrderBlocks(blocks: OrderBlock[]): void {
    for (const ob of blocks) {
      if (ob.mitigated && !this.config.showMitigated) continue;

      let color: string;
      let type: ChartAnnotationType;

      switch (ob.type) {
        case 'BULLISH_OB':
          color = this.config.colors.orderBlocks.bullish;
          type = 'ORDER_BLOCK_ZONE';
          break;
        case 'BEARISH_OB':
          color = this.config.colors.orderBlocks.bearish;
          type = 'ORDER_BLOCK_ZONE';
          break;
        case 'BREAKER':
          color = this.config.colors.orderBlocks.breaker;
          type = 'BREAKER_ZONE';
          break;
        case 'MITIGATION':
          color = this.config.colors.orderBlocks.mitigation;
          type = 'MITIGATION_ZONE';
          break;
        default:
          color = this.config.colors.orderBlocks.bullish;
          type = 'ORDER_BLOCK_ZONE';
      }

      // Fade mitigated blocks
      if (ob.mitigated) {
        color = color.replace(/[0-9a-f]{2}$/, '10');
      }

      this.addAnnotation(type, {
        high: ob.zone.high,
        low: ob.zone.low,
        startIndex: ob.originIndex,
        endIndex: ob.zone.endTime ? undefined : undefined, // Extends to right edge
        color,
        borderColor: color.replace(/[0-9a-f]{2}$/, '60'),
        label: this.config.showLabels ? `${ob.type.replace('_', ' ')} (${ob.strength})` : undefined,
      });
    }
  }

  /**
   * Draw Fair Value Gaps
   */
  private drawFVGs(fvgs: FairValueGap[]): void {
    for (const fvg of fvgs) {
      if (fvg.filled && !this.config.showMitigated) continue;

      let color: string;
      let type: ChartAnnotationType;

      if (fvg.type === 'IFVG') {
        color = this.config.colors.fvg.ifvg;
        type = 'IFVG_ZONE';
      } else {
        color = fvg.direction === 'BULLISH'
          ? this.config.colors.fvg.bullish
          : this.config.colors.fvg.bearish;
        type = 'FVG_ZONE';
      }

      if (fvg.filled) {
        color = color.replace(/[0-9a-f]{2}$/, '08');
      }

      this.addAnnotation(type, {
        high: fvg.zone.high,
        low: fvg.zone.low,
        startIndex: fvg.index - 1,
        color,
        label: this.config.showLabels ? `${fvg.type} ${fvg.direction === 'BULLISH' ? '↑' : '↓'}` : undefined,
      });
    }
  }

  /**
   * Draw liquidity levels
   */
  private drawLiquidity(levels: LiquidityLevel[]): void {
    for (const level of levels) {
      if (level.swept && !this.config.showMitigated) continue;

      const isBuySide = level.type === 'BSL' || level.type === 'EQH';
      const color = isBuySide
        ? this.config.colors.liquidity.bsl
        : this.config.colors.liquidity.ssl;

      this.addAnnotation('LIQUIDITY_LINE', {
        price: level.price,
        startIndex: level.startIndex,
        endIndex: level.endIndex || level.startIndex + 100,
        color: level.swept ? color + '40' : color,
        style: level.type === 'EQH' || level.type === 'EQL' ? 'double' : 'dashed',
        label: this.config.showLabels ? `${level.type} (${level.touches}x)` : undefined,
      });
    }
  }

  /**
   * Draw liquidity sweeps
   */
  private drawSweeps(sweeps: LiquiditySweep[]): void {
    for (const sweep of sweeps) {
      this.addAnnotation('SWEEP_MARKER', {
        index: sweep.sweepIndex,
        price: sweep.level.price,
        color: this.config.colors.liquidity.sweep,
        label: '💧 SWEEP',
      });
    }
  }

  /**
   * Draw Premium/Discount zones
   */
  private drawPremiumDiscount(pd: PremiumDiscount): void {
    this.addAnnotation('PREMIUM_ZONE', {
      high: pd.premiumZone.high,
      low: pd.premiumZone.low,
      startIndex: 0,
      color: this.config.colors.premium,
    });

    this.addAnnotation('DISCOUNT_ZONE', {
      high: pd.discountZone.high,
      low: pd.discountZone.low,
      startIndex: 0,
      color: this.config.colors.discount,
    });
  }

  /**
   * Draw Kill Zones
   */
  private drawKillZones(killZones: KillZone[]): void {
    for (const kz of killZones) {
      this.addAnnotation('KILL_ZONE', {
        high: kz.high,
        low: kz.low,
        startIndex: 0,
        color: 'rgba(245, 158, 11, 0.05)',
        label: `${kz.type} Kill Zone`,
      });
    }
  }

  /**
   * Draw OTE zone
   */
  private drawOTE(ote: OTE): void {
    this.addAnnotation('OTE_ZONE', {
      high: ote.zone.high,
      low: ote.zone.low,
      startIndex: ote.swingLow.index,
      color: 'rgba(99, 102, 241, 0.15)',
      borderColor: 'rgba(99, 102, 241, 0.5)',
      label: 'OTE Zone (0.618-0.786)',
    });
  }

  /**
   * Draw trading sessions
   */
  private drawSessions(sessions: TradingSession[]): void {
    const colorMap: Record<string, string> = {
      ASIAN: this.config.colors.sessions.asian,
      LONDON: this.config.colors.sessions.london,
      NEW_YORK: this.config.colors.sessions.newYork,
      SYDNEY: this.config.colors.sessions.sydney,
    };

    for (const session of sessions) {
      this.addAnnotation('SESSION_BOX', {
        startIndex: 0,
        endIndex: 50,
        high: session.high,
        low: session.low,
        color: colorMap[session.type] || 'rgba(100, 100, 100, 0.05)',
        label: `${session.type} H/L`,
      });
    }
  }

  /**
   * Draw LIT setup annotations
   */
  private drawLITSetups(setups: LITSetup[]): void {
    for (const setup of setups) {
      this.addAnnotation('LIT_MARKER', {
        index: setup.index,
        price: setup.price,
        color: setup.direction === 'BULLISH' ? '#10b981' : '#ef4444',
        label: `LIT: ${setup.type.replace(/_/g, ' ')} (${setup.confidence}%)`,
      });
    }
  }

  /**
   * Draw SMT divergence markers
   */
  private drawSMT(divergences: SMTDivergence[]): void {
    for (const div of divergences) {
      this.addAnnotation('SMT_MARKER', {
        index: 0,
        price: div.priceLevel1,
        color: div.direction === 'BULLISH' ? '#10b981' : '#ef4444',
        label: `SMT: ${div.symbol1}/${div.symbol2} (${div.strength}%)`,
      });
    }
  }

  /**
   * Draw trade setup (Entry, SL, TP from AI)
   */
  private drawTradeSetup(analysis: AIAnalysis): void {
    // Entry line
    this.addAnnotation('ENTRY_LINE', {
      price: analysis.entry,
      label: 'ENTRY',
      color: this.config.colors.entry,
      startIndex: 0,
    });

    // Stop Loss line
    this.addAnnotation('SL_LINE', {
      price: analysis.stopLoss,
      label: 'STOP LOSS',
      color: this.config.colors.stopLoss,
      startIndex: 0,
    });

    // Take Profit lines
    for (let i = 0; i < analysis.takeProfit.length; i++) {
      this.addAnnotation('TP_LINE', {
        price: analysis.takeProfit[i],
        label: `TP${i + 1} (${(i + 1) * 2}R)`,
        color: this.config.colors.takeProfit,
        startIndex: 0,
      });
    }

    // Invalidation line
    if (analysis.stopLoss) {
      this.addAnnotation('INVALIDATION_LINE', {
        price: analysis.stopLoss,
        label: 'INVALIDATION',
        color: '#ff6b6b',
        startIndex: 0,
      });
    }
  }

  /**
   * Helper to add annotation with auto-generated ID
   */
  private addAnnotation(type: ChartAnnotationType, data: Record<string, unknown>): void {
    this.idCounter++;
    this.annotations.push({
      id: `vis_${this.idCounter}`,
      type,
      data,
      visible: true,
      interactive: false,
      zIndex: this.getZIndex(type),
    });
  }

  /**
   * Get z-index for annotation type (layering order)
   */
  private getZIndex(type: ChartAnnotationType): number {
    const zMap: Partial<Record<ChartAnnotationType, number>> = {
      'SESSION_BOX': 1,
      'PREMIUM_ZONE': 2,
      'DISCOUNT_ZONE': 2,
      'KILL_ZONE': 3,
      'OTE_ZONE': 4,
      'FVG_ZONE': 5,
      'IFVG_ZONE': 5,
      'ORDER_BLOCK_ZONE': 6,
      'BREAKER_ZONE': 6,
      'MITIGATION_ZONE': 6,
      'TARGET_ZONE': 7,
      'LIQUIDITY_LINE': 8,
      'BOS_LINE': 9,
      'CHOCH_LINE': 9,
      'STRUCTURE_LINE': 9,
      'SWEEP_MARKER': 10,
      'SMT_MARKER': 11,
      'LIT_MARKER': 11,
      'ENTRY_LINE': 12,
      'SL_LINE': 12,
      'TP_LINE': 12,
      'INVALIDATION_LINE': 13,
    };
    return zMap[type] || 5;
  }

  /**
   * Update color scheme
   */
  updateColors(colors: Partial<ColorScheme>): void {
    this.config.colors = { ...this.config.colors, ...colors };
  }

  /**
   * Toggle visibility of annotation type
   */
  toggleType(type: ChartAnnotationType, visible: boolean): void {
    for (const ann of this.annotations) {
      if (ann.type === type) ann.visible = visible;
    }
  }

  /**
   * Get visible annotations only
   */
  getVisibleAnnotations(): ChartAnnotation[] {
    return this.annotations.filter(a => a.visible);
  }

  reset(): void {
    this.annotations = [];
    this.idCounter = 0;
  }
}
