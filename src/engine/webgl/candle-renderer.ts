// ============================================================================
// WEBGL2 CANDLE RENDERER — Instanced rendering for millions of candles
// One draw call renders ALL visible candles. GPU does the heavy lifting.
//
// Architecture:
// - Candle data stored in flat Float32Array (OHLC + index per candle)
// - Unit quad (4 vertices) instanced N times for bodies
// - Unit line (2 vertices) instanced N times for wicks
// - Viewport culling: only upload visible range to GPU
// - Transform via uniform matrix (zoom/pan = update 1 uniform, 0 re-upload)
// ============================================================================

import { Candle } from '../../core/types';
import {
  createProgram, createBuffer, createVAO,
  setupInstancedAttribute, buildTransformMatrix, getUniform,
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

export class CandleRenderer {
  private gl: WebGL2RenderingContext;
  private bodyProgram: WebGLProgram;
  private wickProgram: WebGLProgram;
  private bodyVAO: WebGLVertexArrayObject;
  private wickVAO: WebGLVertexArrayObject;
  private instanceBuffer: WebGLBuffer;

  // Data state
  private candleData: Float32Array = new Float32Array(0);
  private totalCandles: number = 0;
  private visibleStart: number = 0;
  private visibleCount: number = 0;
  private dirty: boolean = true;

  // Config
  private barWidth: number = 0.7;    // Relative to barWidth+barSpacing
  private barSpacing: number = 0.3;
  private wickWidth: number = 0.002; // In data units (thin)

  constructor(gl: WebGL2RenderingContext) {
    this.gl = gl;

    // Compile shader programs
    this.bodyProgram = createProgram(gl, CANDLE_BODY_VERTEX, CANDLE_BODY_FRAGMENT);
    this.wickProgram = createProgram(gl, CANDLE_WICK_VERTEX, CANDLE_WICK_FRAGMENT);

    // Create instance buffer (will be populated with candle data)
    this.instanceBuffer = createBuffer(gl, new Float32Array(0), gl.DYNAMIC_DRAW);

    // Setup body VAO (unit quad + instanced attributes)
    this.bodyVAO = this.setupBodyVAO();

    // Setup wick VAO (unit line + instanced attributes)
    this.wickVAO = this.setupWickVAO();
  }

  // =========================================================================
  // DATA MANAGEMENT
  // =========================================================================

  /**
   * Load full candle dataset. Converts to flat Float32Array for GPU upload.
   * Only the visible portion is actually uploaded each frame.
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
    this.dirty = true;
  }

  /**
   * Append a single candle (real-time update). O(1) operation.
   */
  appendCandle(candle: Candle): void {
    const newSize = (this.totalCandles + 1) * FLOATS_PER_CANDLE;
    if (newSize > this.candleData.length) {
      // Grow buffer by 50% (amortized O(1))
      const grown = new Float32Array(Math.max(newSize, this.candleData.length * 1.5));
      grown.set(this.candleData);
      this.candleData = grown;
    }
    const offset = this.totalCandles * FLOATS_PER_CANDLE;
    this.candleData[offset] = candle.open;
    this.candleData[offset + 1] = candle.high;
    this.candleData[offset + 2] = candle.low;
    this.candleData[offset + 3] = candle.close;
    this.candleData[offset + 4] = this.totalCandles;
    this.totalCandles++;
    this.dirty = true;
  }

  /**
   * Update the last candle in-place (forming bar update). O(1).
   */
  updateLastCandle(candle: Candle): void {
    if (this.totalCandles === 0) return;
    const offset = (this.totalCandles - 1) * FLOATS_PER_CANDLE;
    this.candleData[offset] = candle.open;
    this.candleData[offset + 1] = candle.high;
    this.candleData[offset + 2] = candle.low;
    this.candleData[offset + 3] = candle.close;
    this.dirty = true;
  }

  // =========================================================================
  // RENDERING
  // =========================================================================

  /**
   * Render visible candles. Called once per frame by the scheduler.
   * Only uploads the VISIBLE portion of data to GPU (viewport culling).
   */
  render(viewport: ViewportState): void {
    const gl = this.gl;

    // Viewport culling: determine visible range
    const start = Math.max(0, Math.floor(viewport.startIndex) - 2);
    const end = Math.min(this.totalCandles, Math.ceil(viewport.startIndex + viewport.visibleBars) + 2);
    this.visibleStart = start;
    this.visibleCount = end - start;

    if (this.visibleCount <= 0) return;

    // Upload only visible candle data to GPU
    const visibleData = this.candleData.subarray(
      start * FLOATS_PER_CANDLE,
      end * FLOATS_PER_CANDLE
    );

    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, visibleData, gl.DYNAMIC_DRAW);

    // Build transform matrix
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
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, this.visibleCount);

    // --- Pass 2: Render bodies (on top) ---
    gl.useProgram(this.bodyProgram);
    gl.uniformMatrix3fv(getUniform(gl, this.bodyProgram, 'u_transform'), false, transform);
    gl.uniform1f(getUniform(gl, this.bodyProgram, 'u_barWidth'), this.barWidth);
    gl.uniform1f(getUniform(gl, this.bodyProgram, 'u_barSpacing'), this.barSpacing);

    gl.bindVertexArray(this.bodyVAO);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, this.visibleCount);

    gl.bindVertexArray(null);
    this.dirty = false;
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
    const openLoc = gl.getAttribLocation(this.bodyProgram, 'a_open');
    const highLoc = gl.getAttribLocation(this.bodyProgram, 'a_high');
    const lowLoc = gl.getAttribLocation(this.bodyProgram, 'a_low');
    const closeLoc = gl.getAttribLocation(this.bodyProgram, 'a_close');
    const indexLoc = gl.getAttribLocation(this.bodyProgram, 'a_index');

    this.setupInstanceAttr(gl, openLoc, 1, INSTANCE_STRIDE, 0);
    this.setupInstanceAttr(gl, highLoc, 1, INSTANCE_STRIDE, 4);
    this.setupInstanceAttr(gl, lowLoc, 1, INSTANCE_STRIDE, 8);
    this.setupInstanceAttr(gl, closeLoc, 1, INSTANCE_STRIDE, 12);
    this.setupInstanceAttr(gl, indexLoc, 1, INSTANCE_STRIDE, 16);

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
    const highLoc = gl.getAttribLocation(this.wickProgram, 'a_high');
    const lowLoc = gl.getAttribLocation(this.wickProgram, 'a_low');
    const openLoc = gl.getAttribLocation(this.wickProgram, 'a_open');
    const closeLoc = gl.getAttribLocation(this.wickProgram, 'a_close');
    const indexLoc = gl.getAttribLocation(this.wickProgram, 'a_index');

    this.setupInstanceAttr(gl, openLoc, 1, INSTANCE_STRIDE, 0);
    this.setupInstanceAttr(gl, highLoc, 1, INSTANCE_STRIDE, 4);
    this.setupInstanceAttr(gl, lowLoc, 1, INSTANCE_STRIDE, 8);
    this.setupInstanceAttr(gl, closeLoc, 1, INSTANCE_STRIDE, 12);
    this.setupInstanceAttr(gl, indexLoc, 1, INSTANCE_STRIDE, 16);

    gl.bindVertexArray(null);
    return vao;
  }

  private setupInstanceAttr(gl: WebGL2RenderingContext, loc: number, size: number, stride: number, offset: number): void {
    if (loc < 0) return; // Attribute optimized away by compiler
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, size, gl.FLOAT, false, stride, offset);
    gl.vertexAttribDivisor(loc, 1);
  }

  // =========================================================================
  // CONFIG
  // =========================================================================

  setBarWidth(width: number): void { this.barWidth = width; this.dirty = true; }
  setBarSpacing(spacing: number): void { this.barSpacing = spacing; this.dirty = true; }
  getTotalCandles(): number { return this.totalCandles; }
  getVisibleCount(): number { return this.visibleCount; }
  isDirty(): boolean { return this.dirty; }

  destroy(): void {
    const gl = this.gl;
    gl.deleteProgram(this.bodyProgram);
    gl.deleteProgram(this.wickProgram);
    gl.deleteVertexArray(this.bodyVAO);
    gl.deleteVertexArray(this.wickVAO);
    gl.deleteBuffer(this.instanceBuffer);
  }
}
