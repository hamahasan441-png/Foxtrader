// ============================================================================
// WEBGL2 UTILITIES — Shader compilation, buffer management, error handling
// ============================================================================

/**
 * Compile a shader from source
 */
export function compileShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) throw new Error('Failed to create shader');
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const info = gl.getShaderInfoLog(shader);
    gl.deleteShader(shader);
    throw new Error(`Shader compile error: ${info}`);
  }
  return shader;
}

/**
 * Link a program from vertex + fragment shaders
 */
export function createProgram(gl: WebGL2RenderingContext, vsSource: string, fsSource: string): WebGLProgram {
  const vs = compileShader(gl, gl.VERTEX_SHADER, vsSource);
  const fs = compileShader(gl, gl.FRAGMENT_SHADER, fsSource);
  const program = gl.createProgram();
  if (!program) throw new Error('Failed to create program');
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const info = gl.getProgramInfoLog(program);
    gl.deleteProgram(program);
    throw new Error(`Program link error: ${info}`);
  }
  // Shaders can be detached after linking
  gl.detachShader(program, vs);
  gl.detachShader(program, fs);
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  return program;
}

/**
 * Create and upload a Float32Array buffer
 */
export function createBuffer(gl: WebGL2RenderingContext, data: Float32Array, usage: number = gl.STATIC_DRAW): WebGLBuffer {
  const buffer = gl.createBuffer();
  if (!buffer) throw new Error('Failed to create buffer');
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, data, usage);
  return buffer;
}

/**
 * Update a sub-range of an existing buffer (for real-time candle updates)
 */
export function updateBufferSubData(gl: WebGL2RenderingContext, buffer: WebGLBuffer, offset: number, data: Float32Array): void {
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferSubData(gl.ARRAY_BUFFER, offset * 4, data); // offset in bytes
}

/**
 * Create a Vertex Array Object with instanced attributes
 */
export function createVAO(gl: WebGL2RenderingContext): WebGLVertexArrayObject {
  const vao = gl.createVertexArray();
  if (!vao) throw new Error('Failed to create VAO');
  return vao;
}

/**
 * Set up an instanced attribute (divisor = 1)
 */
export function setupInstancedAttribute(
  gl: WebGL2RenderingContext,
  location: number,
  buffer: WebGLBuffer,
  size: number,
  stride: number,
  offset: number,
  divisor: number = 1
): void {
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.enableVertexAttribArray(location);
  gl.vertexAttribPointer(location, size, gl.FLOAT, false, stride, offset);
  gl.vertexAttribDivisor(location, divisor);
}

/**
 * Build a 2D transform matrix (translate + scale) for the chart viewport
 * Maps: (barIndex, price) → clip space (-1 to +1)
 */
export function buildTransformMatrix(
  viewportX: number,    // Left-most visible bar index
  viewportWidth: number, // Number of visible bars
  viewportY: number,    // Bottom price
  viewportHeight: number, // Price range (top - bottom)
  canvasWidth: number,
  canvasHeight: number
): Float32Array {
  // Scale to [-1, 1] clip space
  const sx = 2.0 / viewportWidth;
  const sy = 2.0 / viewportHeight;
  const tx = -2.0 * viewportX / viewportWidth - 1.0;
  const ty = -2.0 * viewportY / viewportHeight - 1.0;

  // Column-major 3x3 matrix
  return new Float32Array([
    sx, 0,  0,
    0,  sy, 0,
    tx, ty, 1,
  ]);
}

/**
 * Get uniform location (cached)
 */
const uniformCache = new Map<string, WebGLUniformLocation | null>();
export function getUniform(gl: WebGL2RenderingContext, program: WebGLProgram, name: string): WebGLUniformLocation | null {
  const key = `${program}_${name}`;
  if (!uniformCache.has(key)) {
    uniformCache.set(key, gl.getUniformLocation(program, name));
  }
  return uniformCache.get(key)!;
}

/**
 * Clear the uniform cache (call on context loss recovery)
 */
export function clearUniformCache(): void {
  uniformCache.clear();
}
