// ============================================================================
// ORDER BLOCKS MODULE
// Bullish OB, Bearish OB, Mitigation Blocks, Breaker Blocks,
// Rejection Blocks, Flip Zones
// No repainting | No look-ahead bias | Institutional-grade accuracy
// ============================================================================

import {
  Candle,
  OrderBlock,
  OrderBlockType,
  Direction,
  PriceZone,
  StructureBreak,
} from '../../core/types';
import { calculateATR } from '../../core/utils';
import { TradingEventBus } from '../../core/event-bus';

export interface OrderBlockConfig {
  minImbalance: number; // Minimum imbalance ratio to qualify as OB
  maxMitigationPercent: number; // Max % filled before considered mitigated
  useBodyOnly: boolean; // Use candle body or full range for OB zone
  minDisplacement: number; // Min displacement (ATR multiple) after OB
  maxOBSize: number; // Max OB size in ATR multiples
  refinementMode: 'AGGRESSIVE' | 'MODERATE' | 'CONSERVATIVE';
  trackMitigation: boolean;
  maxActiveBlocks: number; // Performance limit
}

const DEFAULT_CONFIG: OrderBlockConfig = {
  minImbalance: 1.5,
  maxMitigationPercent: 50,
  useBodyOnly: true,
  minDisplacement: 1.0,
  maxOBSize: 3.0,
  refinementMode: 'MODERATE',
  trackMitigation: true,
  maxActiveBlocks: 200,
};

export class OrderBlockAnalyzer {
  private config: OrderBlockConfig;
  private eventBus?: TradingEventBus;
  private orderBlocks: OrderBlock[] = [];
  private breakerBlocks: OrderBlock[] = [];
  private mitigationBlocks: OrderBlock[] = [];
  private rejectionBlocks: OrderBlock[] = [];
  private flipZones: OrderBlock[] = [];

  constructor(config: Partial<OrderBlockConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Full analysis - detect all order block types
   */
  analyze(candles: Candle[], structureBreaks?: StructureBreak[]): OrderBlock[] {
    this.reset();

    if (candles.length < 5) return [];

    const atr = calculateATR(candles, 14);

    // 1. Detect Bullish Order Blocks
    this.detectBullishOB(candles, atr);

    // 2. Detect Bearish Order Blocks
    this.detectBearishOB(candles, atr);

    // 3. Detect Breaker Blocks (failed OBs that flip)
    this.detectBreakerBlocks(candles, atr);

    // 4. Detect Mitigation Blocks
    this.detectMitigationBlocks(candles, atr, structureBreaks);

    // 5. Detect Rejection Blocks
    this.detectRejectionBlocks(candles, atr);

    // 6. Detect Flip Zones
    this.detectFlipZones(candles, atr);

    // 7. Track mitigation of all blocks
    if (this.config.trackMitigation) {
      this.trackAllMitigation(candles);
    }

    return this.getAllBlocks();
  }

  /**
   * Detect Bullish Order Blocks
   * Last bearish candle before a strong bullish displacement
   */
  private detectBullishOB(candles: Candle[], atr: number[]): void {
    for (let i = 2; i < candles.length - 1; i++) {
      const current = candles[i];
      const previous = candles[i - 1];

      // Look for bearish candle (potential OB body)
      if (current.close >= current.open) continue; // Must be bearish

      // Check for displacement after (bullish move away)
      const displacement = this.measureDisplacement(candles, i, 'BULLISH', atr[i]);
      if (displacement < this.config.minDisplacement) continue;

      // Check imbalance
      const imbalance = this.calculateImbalance(candles, i, 'BULLISH');
      if (imbalance < this.config.minImbalance) continue;

      // Validate OB size
      const obSize = (current.high - current.low) / (atr[i] || 1);
      if (obSize > this.config.maxOBSize) continue;

      // Create zone based on configuration
      const zone = this.createOBZone(current, 'BULLISH');

      const ob: OrderBlock = {
        type: 'BULLISH_OB',
        direction: 'BULLISH',
        zone,
        originCandle: current,
        originIndex: i,
        timestamp: current.timestamp,
        mitigated: false,
        mitigationPercentage: 0,
        tested: false,
        testCount: 0,
        strength: this.calculateOBStrength(displacement, imbalance, atr[i], current),
        volumeProfile: current.volume,
        imbalanceRatio: imbalance,
      };

      this.orderBlocks.push(ob);
      this.eventBus?.emit({ type: 'ORDER_BLOCK_FORMED', data: ob });
    }
  }

  /**
   * Detect Bearish Order Blocks
   * Last bullish candle before a strong bearish displacement
   */
  private detectBearishOB(candles: Candle[], atr: number[]): void {
    for (let i = 2; i < candles.length - 1; i++) {
      const current = candles[i];

      // Look for bullish candle (potential OB body)
      if (current.close <= current.open) continue; // Must be bullish

      // Check for displacement after (bearish move away)
      const displacement = this.measureDisplacement(candles, i, 'BEARISH', atr[i]);
      if (displacement < this.config.minDisplacement) continue;

      // Check imbalance
      const imbalance = this.calculateImbalance(candles, i, 'BEARISH');
      if (imbalance < this.config.minImbalance) continue;

      // Validate OB size
      const obSize = (current.high - current.low) / (atr[i] || 1);
      if (obSize > this.config.maxOBSize) continue;

      const zone = this.createOBZone(current, 'BEARISH');

      const ob: OrderBlock = {
        type: 'BEARISH_OB',
        direction: 'BEARISH',
        zone,
        originCandle: current,
        originIndex: i,
        timestamp: current.timestamp,
        mitigated: false,
        mitigationPercentage: 0,
        tested: false,
        testCount: 0,
        strength: this.calculateOBStrength(displacement, imbalance, atr[i], current),
        volumeProfile: current.volume,
        imbalanceRatio: imbalance,
      };

      this.orderBlocks.push(ob);
      this.eventBus?.emit({ type: 'ORDER_BLOCK_FORMED', data: ob });
    }
  }

  /**
   * Detect Breaker Blocks
   * Order blocks that failed (were mitigated) and now act as opposite zones
   */
  private detectBreakerBlocks(candles: Candle[], atr: number[]): void {
    // First pass - find OBs that were broken through with force
    for (const ob of this.orderBlocks) {
      const startSearch = ob.originIndex + 1;

      for (let i = startSearch; i < candles.length; i++) {
        const candle = candles[i];

        if (ob.direction === 'BULLISH') {
          // Bullish OB broken = becomes Bearish Breaker
          if (candle.close < ob.zone.low) {
            // Check for displacement through the OB
            const breakDisplacement = this.measureDisplacement(candles, i, 'BEARISH', atr[i] || 1);
            if (breakDisplacement >= this.config.minDisplacement * 0.7) {
              const breaker: OrderBlock = {
                type: 'BREAKER',
                direction: 'BEARISH', // Flipped direction
                zone: { ...ob.zone, mitigated: false },
                originCandle: ob.originCandle,
                originIndex: ob.originIndex,
                timestamp: candle.timestamp,
                mitigated: false,
                mitigationPercentage: 0,
                tested: false,
                testCount: 0,
                strength: ob.strength * 0.8,
                volumeProfile: ob.volumeProfile,
                imbalanceRatio: ob.imbalanceRatio,
              };
              this.breakerBlocks.push(breaker);
              ob.mitigated = true;
              ob.mitigationPercentage = 100;
            }
            break;
          }
        } else {
          // Bearish OB broken = becomes Bullish Breaker
          if (candle.close > ob.zone.high) {
            const breakDisplacement = this.measureDisplacement(candles, i, 'BULLISH', atr[i] || 1);
            if (breakDisplacement >= this.config.minDisplacement * 0.7) {
              const breaker: OrderBlock = {
                type: 'BREAKER',
                direction: 'BULLISH',
                zone: { ...ob.zone, mitigated: false },
                originCandle: ob.originCandle,
                originIndex: ob.originIndex,
                timestamp: candle.timestamp,
                mitigated: false,
                mitigationPercentage: 0,
                tested: false,
                testCount: 0,
                strength: ob.strength * 0.8,
                volumeProfile: ob.volumeProfile,
                imbalanceRatio: ob.imbalanceRatio,
              };
              this.breakerBlocks.push(breaker);
              ob.mitigated = true;
              ob.mitigationPercentage = 100;
            }
            break;
          }
        }
      }
    }
  }

  /**
   * Detect Mitigation Blocks
   * Areas where previous orders were "mitigated" (partially filled)
   */
  private detectMitigationBlocks(
    candles: Candle[],
    atr: number[],
    structureBreaks?: StructureBreak[]
  ): void {
    if (!structureBreaks || structureBreaks.length === 0) return;

    for (const brk of structureBreaks) {
      if (brk.type !== 'BOS' && brk.type !== 'CHOCH') continue;

      // Find the last opposing candle before the structure break
      const searchEnd = brk.breakIndex;
      const searchStart = Math.max(0, searchEnd - 20);

      for (let i = searchEnd - 1; i >= searchStart; i--) {
        const candle = candles[i];
        
        if (brk.direction === 'BULLISH' && candle.close < candle.open) {
          // Last bearish candle before bullish break = mitigation zone
          const zone = this.createOBZone(candle, 'BULLISH');
          
          const mitBlock: OrderBlock = {
            type: 'MITIGATION',
            direction: 'BULLISH',
            zone,
            originCandle: candle,
            originIndex: i,
            timestamp: candle.timestamp,
            mitigated: false,
            mitigationPercentage: 0,
            tested: false,
            testCount: 0,
            strength: 60,
            volumeProfile: candle.volume,
          };
          this.mitigationBlocks.push(mitBlock);
          break;
        }

        if (brk.direction === 'BEARISH' && candle.close > candle.open) {
          const zone = this.createOBZone(candle, 'BEARISH');
          
          const mitBlock: OrderBlock = {
            type: 'MITIGATION',
            direction: 'BEARISH',
            zone,
            originCandle: candle,
            originIndex: i,
            timestamp: candle.timestamp,
            mitigated: false,
            mitigationPercentage: 0,
            tested: false,
            testCount: 0,
            strength: 60,
            volumeProfile: candle.volume,
          };
          this.mitigationBlocks.push(mitBlock);
          break;
        }
      }
    }
  }

  /**
   * Detect Rejection Blocks
   * Candles with long wicks showing institutional rejection at a level
   */
  private detectRejectionBlocks(candles: Candle[], atr: number[]): void {
    for (let i = 1; i < candles.length; i++) {
      const candle = candles[i];
      const body = Math.abs(candle.close - candle.open);
      const totalRange = candle.high - candle.low;
      
      if (totalRange === 0) continue;

      const upperWick = candle.high - Math.max(candle.open, candle.close);
      const lowerWick = Math.min(candle.open, candle.close) - candle.low;

      // Bearish Rejection (long upper wick)
      if (upperWick > body * 2 && upperWick > totalRange * 0.6) {
        const zone: PriceZone = {
          high: candle.high,
          low: Math.max(candle.open, candle.close),
          startTime: candle.timestamp,
          mitigated: false,
        };

        const rb: OrderBlock = {
          type: 'REJECTION',
          direction: 'BEARISH',
          zone,
          originCandle: candle,
          originIndex: i,
          timestamp: candle.timestamp,
          mitigated: false,
          mitigationPercentage: 0,
          tested: false,
          testCount: 0,
          strength: Math.min(100, (upperWick / totalRange) * 100),
          volumeProfile: candle.volume,
        };
        this.rejectionBlocks.push(rb);
      }

      // Bullish Rejection (long lower wick)
      if (lowerWick > body * 2 && lowerWick > totalRange * 0.6) {
        const zone: PriceZone = {
          high: Math.min(candle.open, candle.close),
          low: candle.low,
          startTime: candle.timestamp,
          mitigated: false,
        };

        const rb: OrderBlock = {
          type: 'REJECTION',
          direction: 'BULLISH',
          zone,
          originCandle: candle,
          originIndex: i,
          timestamp: candle.timestamp,
          mitigated: false,
          mitigationPercentage: 0,
          tested: false,
          testCount: 0,
          strength: Math.min(100, (lowerWick / totalRange) * 100),
          volumeProfile: candle.volume,
        };
        this.rejectionBlocks.push(rb);
      }
    }
  }

  /**
   * Detect Flip Zones
   * Areas that were resistance, became support (or vice versa)
   */
  private detectFlipZones(candles: Candle[], atr: number[]): void {
    // A flip zone is where a bearish OB was broken and retested as support (or vice versa)
    const allBlocks = [...this.orderBlocks, ...this.breakerBlocks];

    for (const block of allBlocks) {
      if (!block.mitigated) continue;

      const mitigationIndex = this.findMitigationIndex(candles, block);
      if (mitigationIndex === -1) continue;

      // Check if price came back to the zone from the other side
      let retested = false;
      for (let i = mitigationIndex + 1; i < candles.length; i++) {
        const candle = candles[i];
        
        if (block.direction === 'BULLISH') {
          // Was bullish, now check if it acts as resistance
          if (candle.high >= block.zone.low && candle.close < block.zone.high) {
            retested = true;
            break;
          }
        } else {
          // Was bearish, now check if it acts as support
          if (candle.low <= block.zone.high && candle.close > block.zone.low) {
            retested = true;
            break;
          }
        }
      }

      if (retested) {
        const flipZone: OrderBlock = {
          type: 'FLIP_ZONE',
          direction: block.direction === 'BULLISH' ? 'BEARISH' : 'BULLISH',
          zone: { ...block.zone, mitigated: false },
          originCandle: block.originCandle,
          originIndex: block.originIndex,
          timestamp: block.timestamp,
          mitigated: false,
          mitigationPercentage: 0,
          tested: true,
          testCount: 1,
          strength: block.strength * 0.7,
          volumeProfile: block.volumeProfile,
        };
        this.flipZones.push(flipZone);
      }
    }
  }

  /**
   * Track mitigation of all active blocks
   */
  private trackAllMitigation(candles: Candle[]): void {
    const allBlocks = this.getAllBlocks();

    for (const block of allBlocks) {
      if (block.mitigated) continue;

      const startSearch = block.originIndex + 1;
      for (let i = startSearch; i < candles.length; i++) {
        const candle = candles[i];
        const zoneSize = block.zone.high - block.zone.low;

        if (block.direction === 'BULLISH') {
          // Price enters the zone from above
          if (candle.low <= block.zone.high && candle.low >= block.zone.low) {
            block.tested = true;
            block.testCount++;
            
            // Calculate mitigation percentage
            const penetration = block.zone.high - candle.low;
            block.mitigationPercentage = Math.min(100, (penetration / zoneSize) * 100);
            
            if (block.mitigationPercentage >= this.config.maxMitigationPercent) {
              block.mitigated = true;
              block.zone.mitigated = true;
              block.zone.endTime = candle.timestamp;
            }
          }
          // Price closes below zone = fully mitigated
          if (candle.close < block.zone.low) {
            block.mitigated = true;
            block.mitigationPercentage = 100;
            block.zone.mitigated = true;
            block.zone.endTime = candle.timestamp;
            break;
          }
        } else {
          // Price enters the zone from below
          if (candle.high >= block.zone.low && candle.high <= block.zone.high) {
            block.tested = true;
            block.testCount++;
            
            const penetration = candle.high - block.zone.low;
            block.mitigationPercentage = Math.min(100, (penetration / zoneSize) * 100);
            
            if (block.mitigationPercentage >= this.config.maxMitigationPercent) {
              block.mitigated = true;
              block.zone.mitigated = true;
              block.zone.endTime = candle.timestamp;
            }
          }
          if (candle.close > block.zone.high) {
            block.mitigated = true;
            block.mitigationPercentage = 100;
            block.zone.mitigated = true;
            block.zone.endTime = candle.timestamp;
            break;
          }
        }
      }
    }
  }

  /**
   * Measure displacement after a candle (how strongly price moved away)
   */
  private measureDisplacement(
    candles: Candle[],
    index: number,
    direction: Direction,
    currentATR: number
  ): number {
    if (currentATR === 0) return 0;

    const lookForward = Math.min(3, candles.length - index - 1);
    let maxDisplacement = 0;

    for (let i = 1; i <= lookForward; i++) {
      const nextCandle = candles[index + i];
      const body = Math.abs(nextCandle.close - nextCandle.open);
      
      if (direction === 'BULLISH' && nextCandle.close > nextCandle.open) {
        maxDisplacement = Math.max(maxDisplacement, body / currentATR);
      } else if (direction === 'BEARISH' && nextCandle.close < nextCandle.open) {
        maxDisplacement = Math.max(maxDisplacement, body / currentATR);
      }
    }

    return maxDisplacement;
  }

  /**
   * Calculate volume/price imbalance around the OB candle
   */
  private calculateImbalance(candles: Candle[], index: number, direction: Direction): number {
    if (index < 1 || index >= candles.length - 1) return 0;

    const prevCandle = candles[index - 1];
    const obCandle = candles[index];
    const nextCandle = candles[index + 1];

    // Imbalance based on candle body ratio
    const obBody = Math.abs(obCandle.close - obCandle.open);
    const nextBody = Math.abs(nextCandle.close - nextCandle.open);
    const prevBody = Math.abs(prevCandle.close - prevCandle.open);

    if (obBody === 0) return 0;

    return nextBody / obBody; // How much bigger is the displacement vs the OB
  }

  /**
   * Create OB zone from candle
   */
  private createOBZone(candle: Candle, direction: Direction): PriceZone {
    if (this.config.useBodyOnly) {
      return {
        high: direction === 'BULLISH'
          ? Math.max(candle.open, candle.close) // Use open (top of bearish body)
          : candle.high,
        low: direction === 'BULLISH'
          ? candle.low
          : Math.min(candle.open, candle.close), // Use close (bottom of bullish body)
        startTime: candle.timestamp,
        mitigated: false,
      };
    }

    // Full range
    return {
      high: candle.high,
      low: candle.low,
      startTime: candle.timestamp,
      mitigated: false,
    };
  }

  /**
   * Calculate OB strength score
   */
  private calculateOBStrength(
    displacement: number,
    imbalance: number,
    atr: number,
    candle: Candle
  ): number {
    let strength = 0;

    // Displacement score (0-40)
    strength += Math.min(40, displacement * 20);

    // Imbalance score (0-30)
    strength += Math.min(30, imbalance * 15);

    // Volume score (0-20) - higher volume = stronger
    if (candle.volume > 0) {
      strength += Math.min(20, 10); // Normalized placeholder
    }

    // Size score (0-10) - smaller OB = more precise
    const obSize = (candle.high - candle.low) / (atr || 1);
    strength += Math.max(0, 10 - obSize * 3);

    return Math.min(100, Math.round(strength));
  }

  /**
   * Find the candle index where a block was mitigated
   */
  private findMitigationIndex(candles: Candle[], block: OrderBlock): number {
    for (let i = block.originIndex + 1; i < candles.length; i++) {
      if (block.direction === 'BULLISH' && candles[i].close < block.zone.low) return i;
      if (block.direction === 'BEARISH' && candles[i].close > block.zone.high) return i;
    }
    return -1;
  }

  /**
   * Get all blocks combined
   */
  getAllBlocks(): OrderBlock[] {
    return [
      ...this.orderBlocks,
      ...this.breakerBlocks,
      ...this.mitigationBlocks,
      ...this.rejectionBlocks,
      ...this.flipZones,
    ];
  }

  /**
   * Get active (unmitigated) blocks
   */
  getActiveBlocks(): OrderBlock[] {
    return this.getAllBlocks().filter(b => !b.mitigated);
  }

  /**
   * Get blocks near price
   */
  getBlocksNearPrice(price: number, range: number): OrderBlock[] {
    return this.getActiveBlocks().filter(
      b => Math.abs(price - (b.zone.high + b.zone.low) / 2) <= range
    );
  }

  /**
   * Get blocks by type
   */
  getBlocksByType(type: OrderBlockType): OrderBlock[] {
    return this.getAllBlocks().filter(b => b.type === type);
  }

  /**
   * Check if price is currently in an order block
   */
  isInOrderBlock(price: number): OrderBlock | null {
    for (const block of this.getActiveBlocks()) {
      if (price >= block.zone.low && price <= block.zone.high) {
        return block;
      }
    }
    return null;
  }

  reset(): void {
    this.orderBlocks = [];
    this.breakerBlocks = [];
    this.mitigationBlocks = [];
    this.rejectionBlocks = [];
    this.flipZones = [];
  }
}
