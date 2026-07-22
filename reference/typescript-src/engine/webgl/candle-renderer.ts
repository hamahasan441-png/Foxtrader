// ============================================================================
// WEBGL2 CANDLE RENDERER — Instanced rendering for millions of candles
// One draw call renders ALL visible candles. GPU does the heavy lifting.
//
// Architecture:
// - Candle data stored in flat Float32Array (OHLC + index per candle)
// - Uploaded to the GPU ONCE on data change (setData/append/update) — never
//   per frame. Pan/zoom change only a uniform matrix + an attribute offset.
// - Unit quad (4 vertices) instanced N times for bodies
// - Unit line (2 vertices) instanced N times for wicks
// - Viewport culling: only the visible instance range is drawn, via a
//   per-frame attribute-offset re-point (no CPU→GPU upload while scrolling)
// - Transform via uniform matrix (zoom/pan = update 1 uniform, 0 re-upload)
// ============================================================================

import { Candle } from '../../core/types';
import {
  createProgram, createBuffer, createVAO,
  buildTransformMatrix, getUniform,
} from './gl-utils';
import {
  CANDLE_BODY_VERTEX, CANDLE_BODY_FRAGMENT,
  CANDLE_WICK_VERTEX, CANDLE_WICK_FRAGMENT,
} from './shaders';

/** Stride: open(1) + high(1) + low(1) + close(1) + index(1) = 5 floats = 20 bytes */
const INSTANCE_STRIDE = 5 * 4; // bytes
const FLOATS_PER_CANDLE = 5;

export interface ViewportState {
  /** Left-most visible bar index */
  startIndex: number;
  /** Number of visible bars */
  visibleBars: number;
  /** Bottom price (lowest visible) */
  priceLow: number;
  /** Top price (highest visible) */
  priceHigh: number;
}

/** Describes one instanced attribute so its byte offset can be re-pointed per frame. */
interface InstanceAttr {
  loc: number;
  /** Byte offset of this attribute WITHIN one candle record. */
  offset: number;
}

export class CandleRenderer {
  private gl: WebGL2RenderingContext;
  private bodyProgram: WebGLProgram;
  private wickProgram: WebGLProgram;
  private bodyVAO: WebGLVertexArrayObject;
  private wickVAO: WebGLVertexArrayObject;
  private instanceBuffer: WebGLBuffer;

  // Per-VAO instanced attribute descriptors (for per-frame offset re-pointing).
  private bodyInstanceAttrs: InstanceAttr[] = [];
  private wickInstanceAttrs: InstanceAttr[] = [];

  // Data state
  private candleData: Float32Array = new Float32Array(0);
  private totalCandles: number = 0;
  private visibleStart: number = 0;
  private visibleCount: number = 0;
  /** Floats currently allocated on the GPU (buffer capacity). */
  private gpuCapacityFloats: number = 0;

  // Config
  private barWidth: number = 0.7;    // Relative to barWidth+barSpacing
  private barSpacing: number = 0.3;
  private wickWidth: number = 0.002; // In data units (thin)

  constructor(gl: WebGL2RenderingContext) {
    this.gl = gl;

    // Compile shader programs
    this.bodyProgram = createProgram(gl, CANDLE_BODY_VERTEX, CANDLE_BODY_FRAGMENT);
    this.wickProgram = createProgram(gl, CANDLE_WICK_VERTEX, CANDLE_WICK_FRAGMENT);

    // Create instance buffer (populated on setData, never per frame)
    this.instanceBuffer = createBuffer(gl, new Float32Array(0), gl.DYNAMIC_DRAW);

    // Setup body VAO (unit quad + instanced attributes)
    this.bodyVAO = this.setupBodyVAO();

    // Setup wick VAO (unit line + instanced attributes)
    this.wickVAO = this.setupWickVAO();
  }

  // =========================================================================
  // DATA MANAGEMENT — GPU uploads happen HERE, not in render()
  // =========================================================================

  /**
   * Load full candle dataset. Converts to a flat Float32Array and uploads it
   * to the GPU ONCE. Subsequent frames re-use this buffer with zero uploads.
   */
  setData(candles: Candle[]): void {
    this.totalCandles = candles.length;
    this.candleData = new Float32Array(candles.length * FLOATS_PER_CANDLE);

    for (let i = 0; i < candles.length; i++) {
      const offset = i * FLOATS_PER_CANDLE;
      this.candleData[offset] = candles[i].open;
      this.candleData[offset + 1] = candles[i].high;
      this.candleData[offset + 2] = candles[i].low;
      this.candleData[offset + 3] = candles[i].close;
      this.candleData[offset + 4] = i; // Bar index
    }
    this.uploadFull();
  }

  /**
   * Append a single candle (real-time update). O(1) amortized. Uploads only
   * the new 20-byte record to the GPU (or reallocates on growth).
   */
  appendCandle(candle: Candle): void {
    const requiredFloats = (this.totalCandles + 1) * FLOATS_PER_CANDLE;
    let grew = false;
    if (requiredFloats > this.candleData.length) {
      // Grow capacity by 50% (amortized O(1)), keeping an integer length.
      const nextCapacity = Math.max(requiredFloats, Math.floor(this.candleData.length * 1.5));
      const grown = new Float32Array(nextCapacity);
      grown.set(this.candleData);
      this.candleData = grown;
      grew = true;
    }

    const offset = this.totalCandles * FLOATS_PER_CANDLE;
    this.candleData[offset] = candle.open;
    this.candleData[offset + 1] = candle.high;
    this.candleData[offset + 2] = candle.low;
    this.candleData[offset + 3] = candle.close;
    this.candleData[offset + 4] = this.totalCandles;
    this.totalCandles++;

    if (grew) {
      this.uploadFull();
    } else {
      this.uploadRange(offset, FLOATS_PER_CANDLE);
    }
  }

  /**
   * Update the last candle in-place (forming bar update). O(1). Uploads only
   * the last 20-byte record.
   */
  updateLastCandle(candle: Candle): void {
    if (this.totalCandles === 0) return;
    const offset = (this.totalCandles - 1) * FLOATS_PER_CANDLE;
    this.candleData[offset] = candle.open;
    this.candleData[offset + 1] = candle.high;
    this.candleData[offset + 2] = candle.low;
    this.candleData[offset + 3] = candle.close;
    this.uploadRange(offset, FLOATS_PER_CANDLE);
  }

  /** (Re)allocate the GPU buffer to the full CPU array and upload it. */
  private uploadFull(): void {
    const gl = this.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, this.candleData, gl.DYNAMIC_DRAW);
    this.gpuCapacityFloats = this.candleData.length;
  }

  /** Upload a sub-range of floats to the existing GPU buffer (no realloc). */
  private uploadRange(offsetFloats: number, lengthFloats: number): void {
    const gl = this.gl;
    // Safety: if the GPU buffer somehow hasn't been sized yet, do a full upload.
    if (offsetFloats + lengthFloats > this.gpuCapacityFloats) {
      this.uploadFull();
      return;
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    gl.bufferSubData(
      gl.ARRAY_BUFFER,
      offsetFloats * 4, // byte offset
      this.candleData.subarray(offsetFloats, offsetFloats + lengthFloats)
    );
  }

  // =========================================================================
  // RENDERING — zero uploads; only re-point the visible instance window
  // =========================================================================

  /**
   * Render visible candles. Called once per frame. Performs NO CPU→GPU upload:
   * viewport culling is achieved by re-pointing the instanced attributes to
   * the visible window's byte offset and drawing only `visibleCount` instances.
   */
  render(viewport: ViewportState): void {
    const gl = this.gl;

    // Viewport culling: determine visible instance range (+2 bar bleed).
    const start = Math.max(0, Math.floor(viewport.startIndex) - 2);
    const end = Math.min(this.totalCandles, Math.ceil(viewport.startIndex + viewport.visibleBars) + 2);
    this.visibleStart = start;
    this.visibleCount = end - start;

    if (this.visibleCount <= 0) return;

    const baseByteOffset = start * INSTANCE_STRIDE;

    // Build transform matrix (this is the ONLY thing that changes on pan/zoom).
    const transform = buildTransformMatrix(
      viewport.startIndex,
      viewport.visibleBars * (this.barWidth + this.barSpacing),
      viewport.priceLow,
      viewport.priceHigh - viewport.priceLow,
      gl.canvas.width,
      gl.canvas.height
    );

    // --- Pass 1: Render wicks (behind bodies) ---
    gl.useProgram(this.wickProgram);
    gl.uniformMatrix3fv(getUniform(gl, this.wickProgram, 'u_transform'), false, transform);
    gl.uniform1f(getUniform(gl, this.wickProgram, 'u_barWidth'), this.barWidth);
    gl.uniform1f(getUniform(gl, this.wickProgram, 'u_barSpacing'), this.barSpacing);
    gl.uniform1f(getUniform(gl, this.wickProgram, 'u_wickWidth'), this.wickWidth);

    gl.bindVertexArray(this.wickVAO);
    this.repointInstanceAttrs(this.wickInstanceAttrs, baseByteOffset);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, this.visibleCount);

    // --- Pass 2: Render bodies (on top) ---
    gl.useProgram(this.bodyProgram);
    gl.uniformMatrix3fv(getUniform(gl, this.bodyProgram, 'u_transform'), false, transform);
    gl.uniform1f(getUniform(gl, this.bodyProgram, 'u_barWidth'), this.barWidth);
    gl.uniform1f(getUniform(gl, this.bodyProgram, 'u_barSpacing'), this.barSpacing);

    gl.bindVertexArray(this.bodyVAO);
    this.repointInstanceAttrs(this.bodyInstanceAttrs, baseByteOffset);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, this.visibleCount);

    gl.bindVertexArray(null);
  }

  /**
   * Re-point instanced attributes to [baseByteOffset] so instance 0 maps to the
   * first visible candle. Cheap CPU-side state change — no data upload. The
   * absolute bar index baked into each record keeps X positioning correct.
   */
  private repointInstanceAttrs(attrs: InstanceAttr[], baseByteOffset: number): void {
    const gl = this.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    for (const a of attrs) {
      if (a.loc < 0) continue;
      gl.vertexAttribPointer(a.loc, 1, gl.FLOAT, false, INSTANCE_STRIDE, baseByteOffset + a.offset);
    }
  }

  // =========================================================================
  // VAO SETUP
  // =========================================================================

  private setupBodyVAO(): WebGLVertexArrayObject {
    const gl = this.gl;
    const vao = createVAO(gl);
    gl.bindVertexArray(vao);

    // Unit quad (triangle strip: 4 vertices)
    const quadData = new Float32Array([
      0, 0,  // bottom-left
      1, 0,  // bottom-right
      0, 1,  // top-left
      1, 1,  // top-right
    ]);
    const quadBuffer = createBuffer(gl, quadData);

    // a_position (per-vertex, divisor=0)
    const posLoc = gl.getAttribLocation(this.bodyProgram, 'a_position');
    gl.bindBuffer(gl.ARRAY_BUFFER, quadBuffer);
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);
    gl.vertexAttribDivisor(posLoc, 0);

    // Instance attributes (per-candle, divisor=1)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    this.bodyInstanceAttrs = [
      { loc: gl.getAttribLocation(this.bodyProgram, 'a_open'), offset: 0 },
      { loc: gl.getAttribLocation(this.bodyProgram, 'a_high'), offset: 4 },
      { loc: gl.getAttribLocation(this.bodyProgram, 'a_low'), offset: 8 },
      { loc: gl.getAttribLocation(this.bodyProgram, 'a_close'), offset: 12 },
      { loc: gl.getAttribLocation(this.bodyProgram, 'a_index'), offset: 16 },
    ];
    for (const a of this.bodyInstanceAttrs) this.setupInstanceAttr(gl, a.loc, INSTANCE_STRIDE, a.offset);

    gl.bindVertexArray(null);
    return vao;
  }

  private setupWickVAO(): WebGLVertexArrayObject {
    const gl = this.gl;
    const vao = createVAO(gl);
    gl.bindVertexArray(vao);

    // Unit line quad (thin rectangle for wick)
    const lineData = new Float32Array([
      0, 0,  1, 0,  0, 1,  1, 1,
    ]);
    const lineBuffer = createBuffer(gl, lineData);

    const posLoc = gl.getAttribLocation(this.wickProgram, 'a_position');
    gl.bindBuffer(gl.ARRAY_BUFFER, lineBuffer);
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);
    gl.vertexAttribDivisor(posLoc, 0);

    // Instance attributes
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    this.wickInstanceAttrs = [
      { loc: gl.getAttribLocation(this.wickProgram, 'a_open'), offset: 0 },
      { loc: gl.getAttribLocation(this.wickProgram, 'a_high'), offset: 4 },
      { loc: gl.getAttribLocation(this.wickProgram, 'a_low'), offset: 8 },
      { loc: gl.getAttribLocation(this.wickProgram, 'a_close'), offset: 12 },
      { loc: gl.getAttribLocation(this.wickProgram, 'a_index'), offset: 16 },
    ];
    for (const a of this.wickInstanceAttrs) this.setupInstanceAttr(gl, a.loc, INSTANCE_STRIDE, a.offset);

    gl.bindVertexArray(null);
    return vao;
  }

  private setupInstanceAttr(gl: WebGL2RenderingContext, loc: number, stride: number, offset: number): void {
    if (loc < 0) return; // Attribute optimized away by compiler
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 1, gl.FLOAT, false, stride, offset);
    gl.vertexAttribDivisor(loc, 1);
  }

  // =========================================================================
  // CONFIG
  // =========================================================================

  setBarWidth(width: number): void { this.barWidth = width; }
  setBarSpacing(spacing: number): void { this.barSpacing = spacing; }
  getTotalCandles(): number { return this.totalCandles; }
  getVisibleCount(): number { return this.visibleCount; }
  getVisibleStart(): number { return this.visibleStart; }

  destroy(): void {
    const gl = this.gl;
    gl.deleteProgram(this.bodyProgram);
    gl.deleteProgram(this.wickProgram);
    gl.deleteVertexArray(this.bodyVAO);
    gl.deleteVertexArray(this.wickVAO);
    gl.deleteBuffer(this.instanceBuffer);
  }
}
