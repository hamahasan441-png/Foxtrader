// ============================================================================
// WEBGL2 CHART SHADERS — GPU-accelerated candle rendering
// Uses instanced rendering: one draw call for ALL visible candles
// ============================================================================

/** Vertex shader for candlestick bodies (instanced quads) */
export const CANDLE_BODY_VERTEX = `#version 300 es
precision highp float;

// Per-vertex (unit quad: 0,0 → 1,1)
in vec2 a_position;

// Per-instance (one per candle)
in float a_open;
in float a_high;
in float a_low;
in float a_close;
in float a_index;    // Bar index (x position)

// Uniforms (shared across all instances)
uniform mat3 u_transform;   // 2D projection + view matrix
uniform float u_barWidth;   // Width of one bar in clip space
uniform float u_barSpacing; // Gap between bars

out vec4 v_color;
out float v_isBullish;

void main() {
  float isBullish = step(a_open, a_close); // 1.0 if bullish, 0.0 if bearish
  v_isBullish = isBullish;

  // Body bounds
  float bodyTop = max(a_open, a_close);
  float bodyBot = min(a_open, a_close);

  // Scale quad to body dimensions
  float x = a_index * (u_barWidth + u_barSpacing) + a_position.x * u_barWidth;
  float y = bodyBot + a_position.y * (bodyTop - bodyBot);

  // Apply view/projection transform
  vec3 transformed = u_transform * vec3(x, y, 1.0);
  gl_Position = vec4(transformed.xy, 0.0, 1.0);

  // Color: green for bullish, red for bearish
  v_color = mix(
    vec4(1.0, 0.275, 0.341, 1.0),  // Bearish: #FF4657
    vec4(0.0, 0.863, 0.510, 1.0),  // Bullish: #00DC82
    isBullish
  );
}
`;

/** Fragment shader for candle bodies */
export const CANDLE_BODY_FRAGMENT = `#version 300 es
precision highp float;

in vec4 v_color;
in float v_isBullish;
out vec4 fragColor;

void main() {
  fragColor = v_color;
}
`;

/** Vertex shader for candle wicks (thin lines) */
export const CANDLE_WICK_VERTEX = `#version 300 es
precision highp float;

in vec2 a_position;   // Unit line segment (0→1 on y)

// Per-instance
in float a_high;
in float a_low;
in float a_open;
in float a_close;
in float a_index;

uniform mat3 u_transform;
uniform float u_barWidth;
uniform float u_barSpacing;
uniform float u_wickWidth;  // Wick pixel width

out vec4 v_color;

void main() {
  float isBullish = step(a_open, a_close);

  // Wick runs from low to high
  float x = a_index * (u_barWidth + u_barSpacing) + u_barWidth * 0.5
            + (a_position.x - 0.5) * u_wickWidth;
  float y = a_low + a_position.y * (a_high - a_low);

  vec3 transformed = u_transform * vec3(x, y, 1.0);
  gl_Position = vec4(transformed.xy, 0.0, 1.0);

  // Wick color: slightly muted
  v_color = mix(
    vec4(0.7, 0.22, 0.27, 1.0),   // Bearish wick
    vec4(0.0, 0.7, 0.42, 1.0),    // Bullish wick
    isBullish
  );
}
`;

/** Fragment shader for wicks */
export const CANDLE_WICK_FRAGMENT = `#version 300 es
precision highp float;
in vec4 v_color;
out vec4 fragColor;
void main() { fragColor = v_color; }
`;

/** Vertex shader for grid lines */
export const GRID_VERTEX = `#version 300 es
precision highp float;
in vec2 a_position;
uniform mat3 u_transform;
out float v_alpha;

void main() {
  vec3 transformed = u_transform * vec3(a_position, 1.0);
  gl_Position = vec4(transformed.xy, 0.0, 1.0);
  v_alpha = 0.15;
}
`;

export const GRID_FRAGMENT = `#version 300 es
precision highp float;
in float v_alpha;
out vec4 fragColor;
uniform vec4 u_gridColor;
void main() { fragColor = vec4(u_gridColor.rgb, v_alpha); }
`;

/** Vertex shader for overlay zones (OB, FVG, Premium/Discount rectangles) */
export const ZONE_VERTEX = `#version 300 es
precision highp float;
in vec2 a_position;

// Per-instance zone data
in vec4 a_zoneBounds;  // x: startIndex, y: endIndex, z: priceHigh, w: priceLow
in vec4 a_zoneColor;   // RGBA

uniform mat3 u_transform;
uniform float u_barWidth;
uniform float u_barSpacing;

out vec4 v_color;

void main() {
  float startX = a_zoneBounds.x * (u_barWidth + u_barSpacing);
  float endX = a_zoneBounds.y * (u_barWidth + u_barSpacing);
  float highY = a_zoneBounds.z;
  float lowY = a_zoneBounds.w;

  float x = startX + a_position.x * (endX - startX);
  float y = lowY + a_position.y * (highY - lowY);

  vec3 transformed = u_transform * vec3(x, y, 1.0);
  gl_Position = vec4(transformed.xy, 0.0, 1.0);
  v_color = a_zoneColor;
}
`;

export const ZONE_FRAGMENT = `#version 300 es
precision highp float;
in vec4 v_color;
out vec4 fragColor;
void main() { fragColor = v_color; }
`;
