// ============================================================================
// CUSTOMIZATION MODULE
// Unlimited themes | Indicator presets | Workspace presets |
// Trading templates | Shortcut keys | Gesture controls
// ============================================================================

import { TradingEventBus } from '../core/event-bus';

export interface Theme {
  id: string;
  name: string;
  isDark: boolean;
  colors: {
    background: string;
    surface: string;
    surfaceAlt: string;
    border: string;
    textPrimary: string;
    textSecondary: string;
    accent: string;
    bullish: string;
    bearish: string;
    wick: string;
    grid: string;
    // SMC concept colors
    bos: string; choch: string; mss: string;
    orderBlock: string; breaker: string; fvg: string;
    liquidity: string; sweep: string; premium: string; discount: string;
    entry: string; stopLoss: string; takeProfit: string;
  };
  fontFamily: string;
  fontSize: number;
  borderRadius: number;
  chartStyle: 'CANDLES' | 'BARS' | 'LINE' | 'HEIKIN_ASHI' | 'HOLLOW';
}

export interface IndicatorPreset {
  id: string;
  name: string;
  indicators: { name: string; enabled: boolean; params: Record<string, number | string | boolean>; color?: string }[];
}

export interface WorkspacePreset {
  id: string;
  name: string;
  layout: 'SINGLE' | 'SPLIT_H' | 'SPLIT_V' | 'QUAD' | 'TRIPLE';
  panels: { id: string; type: string; symbol?: string; timeframe?: string; visible: boolean; position: string }[];
  activePanel: string;
}

export interface ShortcutBinding {
  id: string;
  action: string;
  keys: string; // e.g. "Ctrl+B", "Shift+S", "Space"
  description: string;
  category: 'TRADING' | 'CHART' | 'NAVIGATION' | 'ANALYSIS' | 'GENERAL';
  enabled: boolean;
}

export type GestureType =
  | 'SWIPE_LEFT' | 'SWIPE_RIGHT' | 'SWIPE_UP' | 'SWIPE_DOWN'
  | 'PINCH_IN' | 'PINCH_OUT' | 'DOUBLE_TAP' | 'LONG_PRESS'
  | 'TWO_FINGER_TAP' | 'THREE_FINGER_SWIPE';

export interface GestureBinding {
  gesture: GestureType;
  action: string;
  enabled: boolean;
}

// --- Built-in themes ---
const BUILT_IN_THEMES: Theme[] = [
  {
    id: 'midnight', name: 'Midnight Pro', isDark: true,
    colors: {
      background: '#0a0e17', surface: '#111827', surfaceAlt: '#1f2937', border: '#1e293b',
      textPrimary: '#f1f5f9', textSecondary: '#94a3b8', accent: '#3b82f6',
      bullish: '#00dc82', bearish: '#ff4757', wick: '#4a5568', grid: '#1e293b',
      bos: '#3b82f6', choch: '#f59e0b', mss: '#ef4444', orderBlock: '#10b98133',
      breaker: '#f59e0b33', fvg: '#00dc8220', liquidity: '#06b6d4', sweep: '#f43f5e',
      premium: '#ef444420', discount: '#10b98120', entry: '#00dc82', stopLoss: '#ef4444', takeProfit: '#3b82f6',
    },
    fontFamily: 'Inter, sans-serif', fontSize: 12, borderRadius: 6, chartStyle: 'CANDLES',
  },
  {
    id: 'arctic', name: 'Arctic Light', isDark: false,
    colors: {
      background: '#ffffff', surface: '#f8fafc', surfaceAlt: '#f1f5f9', border: '#e2e8f0',
      textPrimary: '#0f172a', textSecondary: '#64748b', accent: '#2563eb',
      bullish: '#16a34a', bearish: '#dc2626', wick: '#94a3b8', grid: '#f1f5f9',
      bos: '#2563eb', choch: '#d97706', mss: '#dc2626', orderBlock: '#16a34a26',
      breaker: '#d9770626', fvg: '#16a34a1a', liquidity: '#0891b2', sweep: '#e11d48',
      premium: '#dc26261a', discount: '#16a34a1a', entry: '#16a34a', stopLoss: '#dc2626', takeProfit: '#2563eb',
    },
    fontFamily: 'Inter, sans-serif', fontSize: 12, borderRadius: 6, chartStyle: 'CANDLES',
  },
  {
    id: 'obsidian', name: 'Obsidian OLED', isDark: true,
    colors: {
      background: '#000000', surface: '#0a0a0a', surfaceAlt: '#141414', border: '#222222',
      textPrimary: '#ffffff', textSecondary: '#888888', accent: '#8b5cf6',
      bullish: '#22c55e', bearish: '#ef4444', wick: '#555555', grid: '#111111',
      bos: '#8b5cf6', choch: '#eab308', mss: '#ef4444', orderBlock: '#22c55e33',
      breaker: '#eab30833', fvg: '#22c55e1a', liquidity: '#06b6d4', sweep: '#f43f5e',
      premium: '#ef44441a', discount: '#22c55e1a', entry: '#22c55e', stopLoss: '#ef4444', takeProfit: '#8b5cf6',
    },
    fontFamily: 'Inter, sans-serif', fontSize: 12, borderRadius: 4, chartStyle: 'CANDLES',
  },
];

// --- Default shortcuts ---
const DEFAULT_SHORTCUTS: ShortcutBinding[] = [
  { id: 'buy', action: 'MARKET_BUY', keys: 'Shift+B', description: 'Market buy', category: 'TRADING', enabled: true },
  { id: 'sell', action: 'MARKET_SELL', keys: 'Shift+S', description: 'Market sell', category: 'TRADING', enabled: true },
  { id: 'closeAll', action: 'CLOSE_ALL', keys: 'Ctrl+Shift+C', description: 'Close all positions', category: 'TRADING', enabled: true },
  { id: 'cancelAll', action: 'CANCEL_ALL_ORDERS', keys: 'Ctrl+Shift+X', description: 'Cancel all orders', category: 'TRADING', enabled: true },
  { id: 'zoomIn', action: 'ZOOM_IN', keys: '+', description: 'Zoom in', category: 'CHART', enabled: true },
  { id: 'zoomOut', action: 'ZOOM_OUT', keys: '-', description: 'Zoom out', category: 'CHART', enabled: true },
  { id: 'fitContent', action: 'FIT_CONTENT', keys: 'F', description: 'Fit chart', category: 'CHART', enabled: true },
  { id: 'replay', action: 'TOGGLE_REPLAY', keys: 'R', description: 'Toggle replay', category: 'CHART', enabled: true },
  { id: 'replayStep', action: 'REPLAY_STEP', keys: 'Space', description: 'Replay step forward', category: 'CHART', enabled: true },
  { id: 'nextSymbol', action: 'NEXT_SYMBOL', keys: 'ArrowRight', description: 'Next watchlist symbol', category: 'NAVIGATION', enabled: true },
  { id: 'prevSymbol', action: 'PREV_SYMBOL', keys: 'ArrowLeft', description: 'Previous watchlist symbol', category: 'NAVIGATION', enabled: true },
  { id: 'scanner', action: 'OPEN_SCANNER', keys: 'Ctrl+K', description: 'Open scanner', category: 'ANALYSIS', enabled: true },
  { id: 'analyze', action: 'RUN_ANALYSIS', keys: 'Ctrl+A', description: 'Run AI analysis', category: 'ANALYSIS', enabled: true },
  { id: 'mentor', action: 'ASK_MENTOR', keys: 'Ctrl+M', description: 'Ask AI mentor', category: 'ANALYSIS', enabled: true },
  { id: 'journal', action: 'OPEN_JOURNAL', keys: 'Ctrl+J', description: 'Open journal', category: 'GENERAL', enabled: true },
  { id: 'voice', action: 'TOGGLE_VOICE', keys: 'Ctrl+V', description: 'Toggle voice assistant', category: 'GENERAL', enabled: true },
];

const DEFAULT_GESTURES: GestureBinding[] = [
  { gesture: 'SWIPE_LEFT', action: 'NEXT_SYMBOL', enabled: true },
  { gesture: 'SWIPE_RIGHT', action: 'PREV_SYMBOL', enabled: true },
  { gesture: 'SWIPE_UP', action: 'NEXT_TIMEFRAME', enabled: true },
  { gesture: 'SWIPE_DOWN', action: 'PREV_TIMEFRAME', enabled: true },
  { gesture: 'PINCH_OUT', action: 'ZOOM_IN', enabled: true },
  { gesture: 'PINCH_IN', action: 'ZOOM_OUT', enabled: true },
  { gesture: 'DOUBLE_TAP', action: 'FIT_CONTENT', enabled: true },
  { gesture: 'LONG_PRESS', action: 'CONTEXT_MENU', enabled: true },
  { gesture: 'TWO_FINGER_TAP', action: 'TOGGLE_CROSSHAIR', enabled: true },
  { gesture: 'THREE_FINGER_SWIPE', action: 'SWITCH_WORKSPACE', enabled: true },
];


export class CustomizationManager {
  private eventBus?: TradingEventBus;
  private themes: Map<string, Theme> = new Map();
  private indicatorPresets: Map<string, IndicatorPreset> = new Map();
  private workspacePresets: Map<string, WorkspacePreset> = new Map();
  private shortcuts: Map<string, ShortcutBinding> = new Map();
  private gestures: Map<GestureType, GestureBinding> = new Map();
  private activeThemeId: string = 'midnight';
  private activeWorkspaceId: string = '';

  constructor(eventBus?: TradingEventBus) {
    this.eventBus = eventBus;
    for (const t of BUILT_IN_THEMES) this.themes.set(t.id, t);
    for (const s of DEFAULT_SHORTCUTS) this.shortcuts.set(s.id, s);
    for (const g of DEFAULT_GESTURES) this.gestures.set(g.gesture, g);
    this.load();
  }

  // =========================================================================
  // THEMES (unlimited - users can create/import any number)
  // =========================================================================

  getThemes(): Theme[] { return Array.from(this.themes.values()); }
  getTheme(id: string): Theme | undefined { return this.themes.get(id); }
  getActiveTheme(): Theme { return this.themes.get(this.activeThemeId) || BUILT_IN_THEMES[0]; }

  applyTheme(id: string): boolean {
    if (!this.themes.has(id)) return false;
    this.activeThemeId = id;
    const theme = this.themes.get(id)!;
    this.applyThemeToDOM(theme);
    this.persist();
    this.eventBus?.emit({ type: 'THEME_CHANGED' as any, data: theme });
    return true;
  }

  /**
   * Create a custom theme (optionally based on an existing one)
   */
  createTheme(name: string, base?: string, overrides?: Partial<Theme['colors']>): Theme {
    const baseTheme = base ? this.themes.get(base) : BUILT_IN_THEMES[0];
    const theme: Theme = {
      ...(baseTheme || BUILT_IN_THEMES[0]),
      id: `theme_${Date.now()}`,
      name,
      colors: { ...(baseTheme || BUILT_IN_THEMES[0]).colors, ...overrides },
    };
    this.themes.set(theme.id, theme);
    this.persist();
    return theme;
  }

  deleteTheme(id: string): boolean {
    if (BUILT_IN_THEMES.some(t => t.id === id)) return false; // Can't delete built-ins
    const result = this.themes.delete(id);
    if (result) this.persist();
    return result;
  }

  /**
   * Apply theme CSS variables to the document root
   */
  private applyThemeToDOM(theme: Theme): void {
    if (typeof document === 'undefined') return;
    const root = document.documentElement;
    const c = theme.colors;
    root.style.setProperty('--bg-primary', c.background);
    root.style.setProperty('--bg-secondary', c.surface);
    root.style.setProperty('--bg-tertiary', c.surfaceAlt);
    root.style.setProperty('--border', c.border);
    root.style.setProperty('--text-primary', c.textPrimary);
    root.style.setProperty('--text-secondary', c.textSecondary);
    root.style.setProperty('--accent', c.accent);
    root.style.setProperty('--accent-bullish', c.bullish);
    root.style.setProperty('--accent-bearish', c.bearish);
    root.style.setProperty('--font-family', theme.fontFamily);
    root.style.setProperty('--font-size', `${theme.fontSize}px`);
    root.style.setProperty('--border-radius', `${theme.borderRadius}px`);
    root.setAttribute('data-theme', theme.isDark ? 'dark' : 'light');
  }

  // =========================================================================
  // INDICATOR PRESETS
  // =========================================================================

  saveIndicatorPreset(preset: Omit<IndicatorPreset, 'id'>): IndicatorPreset {
    const full: IndicatorPreset = { ...preset, id: `ind_preset_${Date.now()}` };
    this.indicatorPresets.set(full.id, full);
    this.persist();
    return full;
  }

  getIndicatorPresets(): IndicatorPreset[] { return Array.from(this.indicatorPresets.values()); }
  getIndicatorPreset(id: string): IndicatorPreset | undefined { return this.indicatorPresets.get(id); }
  deleteIndicatorPreset(id: string): boolean {
    const r = this.indicatorPresets.delete(id); if (r) this.persist(); return r;
  }

  // =========================================================================
  // WORKSPACE PRESETS
  // =========================================================================

  saveWorkspace(preset: Omit<WorkspacePreset, 'id'>): WorkspacePreset {
    const full: WorkspacePreset = { ...preset, id: `ws_${Date.now()}` };
    this.workspacePresets.set(full.id, full);
    this.persist();
    return full;
  }

  applyWorkspace(id: string): WorkspacePreset | null {
    const ws = this.workspacePresets.get(id);
    if (!ws) return null;
    this.activeWorkspaceId = id;
    this.persist();
    this.eventBus?.emit({ type: 'WORKSPACE_CHANGED' as any, data: ws });
    return ws;
  }

  getWorkspaces(): WorkspacePreset[] { return Array.from(this.workspacePresets.values()); }
  deleteWorkspace(id: string): boolean {
    const r = this.workspacePresets.delete(id); if (r) this.persist(); return r;
  }

  // =========================================================================
  // SHORTCUTS
  // =========================================================================

  getShortcuts(): ShortcutBinding[] { return Array.from(this.shortcuts.values()); }

  getShortcutsByCategory(category: ShortcutBinding['category']): ShortcutBinding[] {
    return this.getShortcuts().filter(s => s.category === category);
  }

  rebindShortcut(id: string, keys: string): boolean {
    const s = this.shortcuts.get(id);
    if (!s) return false;
    // Check for conflicts
    const conflict = this.getShortcuts().find(other => other.id !== id && other.keys === keys && other.enabled);
    if (conflict) {
      console.warn(`[Customization] Shortcut ${keys} conflicts with ${conflict.action}`);
      return false;
    }
    s.keys = keys;
    this.persist();
    return true;
  }

  /**
   * Match a keyboard event to an action
   */
  matchShortcut(event: { key: string; ctrlKey: boolean; shiftKey: boolean; altKey: boolean; metaKey: boolean }): string | null {
    const combo = this.buildKeyCombo(event);
    for (const s of this.shortcuts.values()) {
      if (s.enabled && s.keys.toLowerCase() === combo.toLowerCase()) return s.action;
    }
    return null;
  }

  private buildKeyCombo(event: { key: string; ctrlKey: boolean; shiftKey: boolean; altKey: boolean; metaKey: boolean }): string {
    const parts: string[] = [];
    if (event.ctrlKey) parts.push('Ctrl');
    if (event.shiftKey) parts.push('Shift');
    if (event.altKey) parts.push('Alt');
    if (event.metaKey) parts.push('Meta');
    parts.push(event.key === ' ' ? 'Space' : event.key);
    return parts.join('+');
  }

  // =========================================================================
  // GESTURES
  // =========================================================================

  getGestures(): GestureBinding[] { return Array.from(this.gestures.values()); }

  rebindGesture(gesture: GestureType, action: string): void {
    const g = this.gestures.get(gesture);
    if (g) { g.action = action; this.persist(); }
  }

  getGestureAction(gesture: GestureType): string | null {
    const g = this.gestures.get(gesture);
    return g && g.enabled ? g.action : null;
  }

  toggleGesture(gesture: GestureType, enabled: boolean): void {
    const g = this.gestures.get(gesture);
    if (g) { g.enabled = enabled; this.persist(); }
  }

  // =========================================================================
  // PERSISTENCE
  // =========================================================================

  private persist(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      const data = {
        activeThemeId: this.activeThemeId,
        activeWorkspaceId: this.activeWorkspaceId,
        customThemes: Array.from(this.themes.values()).filter(t => !BUILT_IN_THEMES.some(b => b.id === t.id)),
        indicatorPresets: Array.from(this.indicatorPresets.values()),
        workspacePresets: Array.from(this.workspacePresets.values()),
        shortcuts: Array.from(this.shortcuts.values()),
        gestures: Array.from(this.gestures.values()),
      };
      localStorage.setItem('customization', JSON.stringify(data));
    } catch (err) { console.warn('[Customization] Persist failed:', err); }
  }

  private load(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      const raw = localStorage.getItem('customization');
      if (!raw) return;
      const data = JSON.parse(raw);
      if (data.activeThemeId) this.activeThemeId = data.activeThemeId;
      if (data.activeWorkspaceId) this.activeWorkspaceId = data.activeWorkspaceId;
      for (const t of data.customThemes || []) this.themes.set(t.id, t);
      for (const p of data.indicatorPresets || []) this.indicatorPresets.set(p.id, p);
      for (const w of data.workspacePresets || []) this.workspacePresets.set(w.id, w);
      for (const s of data.shortcuts || []) this.shortcuts.set(s.id, s);
      for (const g of data.gestures || []) this.gestures.set(g.gesture, g);
    } catch (err) { console.warn('[Customization] Load failed:', err); }
  }

  /** Export all customization for cloud sync */
  export(): string {
    return JSON.stringify({
      themes: Array.from(this.themes.values()),
      indicatorPresets: Array.from(this.indicatorPresets.values()),
      workspacePresets: Array.from(this.workspacePresets.values()),
      shortcuts: Array.from(this.shortcuts.values()),
      gestures: Array.from(this.gestures.values()),
    });
  }
}
