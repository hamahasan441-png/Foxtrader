// ============================================================================
// FOX DESIGN SYSTEM — Design Tokens
// The single source of truth for all visual properties.
// Every component references these tokens — never hardcoded values.
//
// Philosophy:
// - Warm amber accent (fox identity) against cool neutral surfaces
// - 4px base grid for spacing
// - Geometric type scale (1.2 ratio)
// - Physics-based motion (spring curves)
// - Eye-comfort first (long trading sessions)
// ============================================================================

// ============================================================================
// COLOR PALETTE
// ============================================================================

export const palette = {
  // --- Neutrals (cool blue-grey base) ---
  neutral: {
    0: '#080b12',    // Deepest background (dark theme)
    5: '#0c1019',    // Surface background
    10: '#111720',   // Card/panel background
    15: '#181f2b',   // Elevated surface
    20: '#1f2735',   // Border subtle
    30: '#2d3748',   // Border default
    40: '#3d4a5c',   // Muted text / disabled
    50: '#556070',   // Placeholder text
    60: '#7a8494',   // Secondary text
    70: '#9ca3b0',   // Tertiary text
    80: '#c4c9d4',   // Body text (dark theme)
    90: '#e4e7ec',   // Primary text (dark theme)
    95: '#f3f4f7',   // Surface (light theme)
    98: '#fafbfd',   // Background (light theme)
    100: '#ffffff',  // Pure white
  },

  // --- Accent (warm amber — the Fox signature) ---
  amber: {
    5: '#1a1408',
    10: '#2e2410',
    20: '#5c4820',
    30: '#8a6c30',
    40: '#b89040',
    50: '#d4a84e',   // Primary accent
    60: '#e6be6a',   // Hover state
    70: '#f0d48e',   // Light accent
    80: '#f8e6b8',
    90: '#fcf3dc',
  },

  // --- Semantic: Trading ---
  bullish: {
    base: '#00c873',    // Green — profit / buy
    muted: '#00c87333', // 20% opacity for zones
    text: '#00e688',    // High contrast for dark bg
    surface: '#0a2a1c', // Panel tint
  },
  bearish: {
    base: '#e8364f',    // Red — loss / sell
    muted: '#e8364f33',
    text: '#ff5c72',
    surface: '#2a0f14',
  },

  // --- Semantic: System ---
  info: '#3b8df0',
  warning: '#e6a030',
  error: '#e8364f',
  success: '#00c873',

  // --- Chart-specific (SMC concepts) ---
  chart: {
    bos: '#3b8df0',
    choch: '#e6a030',
    mss: '#e8364f',
    orderBlock: '#00c87330',
    orderBlockBear: '#e8364f30',
    fvg: '#3b8df020',
    liquidity: '#06b6d4',
    sweep: '#f43f5e',
    premium: '#e8364f18',
    discount: '#00c87318',
    ote: '#7c5cfc28',
    session: {
      asian: '#7c5cfc12',
      london: '#3b8df012',
      newYork: '#e6a03012',
      sydney: '#00c87312',
    },
  },
} as const;

// ============================================================================
// SPACING (4px base grid)
// ============================================================================

export const space = {
  px: '1px',
  0: '0',
  0.5: '2px',
  1: '4px',
  1.5: '6px',
  2: '8px',
  2.5: '10px',
  3: '12px',
  4: '16px',
  5: '20px',
  6: '24px',
  7: '28px',
  8: '32px',
  10: '40px',
  12: '48px',
  14: '56px',
  16: '64px',
  20: '80px',
  24: '96px',
} as const;

// ============================================================================
// TYPOGRAPHY
// ============================================================================

export const font = {
  family: {
    sans: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    mono: "'JetBrains Mono', 'SF Mono', 'Fira Code', monospace",
  },
  size: {
    '2xs': '10px',
    xs: '11px',
    sm: '12px',
    base: '13px',
    md: '14px',
    lg: '16px',
    xl: '18px',
    '2xl': '22px',
    '3xl': '28px',
    '4xl': '36px',
  },
  weight: {
    normal: '400',
    medium: '500',
    semibold: '600',
    bold: '700',
  },
  lineHeight: {
    tight: '1.2',
    normal: '1.4',
    relaxed: '1.6',
  },
  letterSpacing: {
    tighter: '-0.03em',
    tight: '-0.01em',
    normal: '0',
    wide: '0.02em',
    wider: '0.05em',
    widest: '0.08em',
  },
} as const;

// ============================================================================
// ELEVATION (subtle, GPU-friendly — borders + blur, not heavy shadows)
// ============================================================================

export const elevation = {
  0: 'none',
  1: '0 1px 2px rgba(0,0,0,0.12)',
  2: '0 2px 8px rgba(0,0,0,0.16)',
  3: '0 4px 16px rgba(0,0,0,0.20)',
  4: '0 8px 32px rgba(0,0,0,0.24)',
  glow: {
    amber: '0 0 20px rgba(212,168,78,0.15)',
    bullish: '0 0 12px rgba(0,200,115,0.12)',
    bearish: '0 0 12px rgba(232,54,79,0.12)',
  },
} as const;

// ============================================================================
// BORDER RADIUS
// ============================================================================

export const radius = {
  none: '0',
  xs: '2px',
  sm: '4px',
  md: '6px',
  lg: '8px',
  xl: '12px',
  '2xl': '16px',
  full: '9999px',
} as const;

// ============================================================================
// ANIMATION — Physics-based spring curves
// ============================================================================

export const motion = {
  // Duration
  duration: {
    instant: '50ms',
    fast: '120ms',
    normal: '200ms',
    slow: '350ms',
    slower: '500ms',
  },
  // Easing (spring-approximation cubic-beziers)
  easing: {
    /** Snappy spring — buttons, toggles */
    spring: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
    /** Smooth deceleration — panels sliding in */
    decelerate: 'cubic-bezier(0, 0, 0.2, 1)',
    /** Smooth acceleration — panels sliding out */
    accelerate: 'cubic-bezier(0.4, 0, 1, 1)',
    /** Standard — most transitions */
    standard: 'cubic-bezier(0.4, 0, 0.2, 1)',
    /** Emphasized — important state changes */
    emphasized: 'cubic-bezier(0.2, 0, 0, 1)',
    /** Linear — progress bars, loaders */
    linear: 'linear',
  },
} as const;

// ============================================================================
// Z-INDEX LAYERS
// ============================================================================

export const zIndex = {
  chart: 0,
  chartOverlay: 10,
  panel: 20,
  sidebar: 30,
  header: 40,
  dropdown: 50,
  modal: 60,
  toast: 70,
  tooltip: 80,
} as const;

// ============================================================================
// BREAKPOINTS (responsive)
// ============================================================================

export const breakpoint = {
  sm: '640px',
  md: '768px',
  lg: '1024px',
  xl: '1280px',
  '2xl': '1536px',
} as const;
