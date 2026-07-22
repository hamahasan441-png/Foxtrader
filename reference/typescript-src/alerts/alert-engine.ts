// ============================================================================
// ALERT ENGINE
// Push Notifications | Email | Desktop | Mobile | Telegram | Webhook
// Centralized alert dispatch with configurable channels.
// ============================================================================

import { TradingEventBus } from '../core/event-bus';

export type AlertChannel = 'PUSH' | 'EMAIL' | 'DESKTOP' | 'MOBILE' | 'TELEGRAM' | 'WEBHOOK';
export type AlertPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface AlertConfig {
  enabledChannels: AlertChannel[];
  webhookUrl?: string;
  telegramBotToken?: string;
  telegramChatId?: string;
  emailTo?: string;
  emailFrom?: string;
  smtpHost?: string;
  /** Minimum priority to send (filter low-priority) */
  minPriority: AlertPriority;
  /** Cooldown between same alert type (ms) */
  cooldownMs: number;
  /** Sound on desktop notifications */
  soundEnabled: boolean;
  /** Max alerts per hour */
  maxAlertsPerHour: number;
}

const DEFAULT_CONFIG: AlertConfig = {
  enabledChannels: ['DESKTOP', 'PUSH'],
  minPriority: 'MEDIUM',
  cooldownMs: 60000,
  soundEnabled: true,
  maxAlertsPerHour: 30,
};

export interface Alert {
  id: string;
  title: string;
  body: string;
  priority: AlertPriority;
  symbol?: string;
  /** Channels this alert was dispatched to */
  dispatchedTo: AlertChannel[];
  timestamp: number;
  acknowledged: boolean;
  data?: Record<string, unknown>;
}

let alertSeq = 0;

export class AlertEngine {
  private config: AlertConfig;
  private eventBus?: TradingEventBus;
  private alerts: Alert[] = [];
  private cooldowns: Map<string, number> = new Map();
  private hourlyCount: number = 0;
  private hourlyReset: number = Date.now() + 3600000;

  constructor(config: Partial<AlertConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
  }

  /**
   * Send an alert through all enabled channels.
   */
  send(params: { title: string; body: string; priority: AlertPriority; symbol?: string; data?: Record<string, unknown>; cooldownKey?: string }): Alert | null {
    const { title, body, priority, symbol, data, cooldownKey } = params;

    // Priority filter
    if (!this.meetsMinPriority(priority)) return null;

    // Cooldown check
    const key = cooldownKey || `${title}_${symbol}`;
    const lastSent = this.cooldowns.get(key) || 0;
    if (Date.now() - lastSent < this.config.cooldownMs) return null;

    // Rate limit
    if (Date.now() > this.hourlyReset) { this.hourlyCount = 0; this.hourlyReset = Date.now() + 3600000; }
    if (this.hourlyCount >= this.config.maxAlertsPerHour) return null;

    const alert: Alert = {
      id: `alert_${++alertSeq}_${Date.now()}`,
      title, body, priority, symbol,
      dispatchedTo: [], timestamp: Date.now(),
      acknowledged: false, data,
    };

    // Dispatch to channels
    for (const channel of this.config.enabledChannels) {
      this.dispatch(alert, channel);
      alert.dispatchedTo.push(channel);
    }

    this.alerts.push(alert);
    this.cooldowns.set(key, Date.now());
    this.hourlyCount++;

    this.eventBus?.emit({ type: 'ALERT_SENT', data: alert });
    return alert;
  }

  private dispatch(alert: Alert, channel: AlertChannel): void {
    switch (channel) {
      case 'DESKTOP': this.sendDesktop(alert); break;
      case 'PUSH': this.sendPush(alert); break;
      case 'WEBHOOK': this.sendWebhook(alert); break;
      case 'TELEGRAM': this.sendTelegram(alert); break;
      case 'EMAIL': this.sendEmail(alert); break;
      case 'MOBILE': this.sendMobile(alert); break;
    }
  }

  /** Browser Notification API */
  private sendDesktop(alert: Alert): void {
    if (typeof Notification === 'undefined') return;
    if (Notification.permission === 'granted') {
      new Notification(alert.title, { body: alert.body, icon: '/icon.png', tag: alert.id });
    } else if (Notification.permission !== 'denied') {
      Notification.requestPermission().then(p => {
        if (p === 'granted') new Notification(alert.title, { body: alert.body });
      });
    }
  }

  /** Push via Service Worker (placeholder — requires SW registration) */
  private sendPush(alert: Alert): void {
    // In production, would send via Push API subscription
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
      navigator.serviceWorker.ready.then(reg => {
        reg.showNotification(alert.title, {
          body: alert.body, tag: alert.id, requireInteraction: alert.priority === 'CRITICAL',
        }).catch(() => {});
      }).catch(() => {});
    }
  }

  /** HTTP Webhook (Discord, Slack, custom) */
  private sendWebhook(alert: Alert): void {
    if (!this.config.webhookUrl) return;
    fetch(this.config.webhookUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: alert.title, body: alert.body, priority: alert.priority,
        symbol: alert.symbol, timestamp: alert.timestamp, data: alert.data,
      }),
    }).catch(err => console.warn('[AlertEngine] Webhook failed:', err));
  }

  /** Telegram Bot API */
  private sendTelegram(alert: Alert): void {
    if (!this.config.telegramBotToken || !this.config.telegramChatId) return;
    const text = `🔔 *${alert.title}*\n${alert.body}${alert.symbol ? `\nSymbol: ${alert.symbol}` : ''}`;
    const url = `https://api.telegram.org/bot${this.config.telegramBotToken}/sendMessage`;
    fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ chat_id: this.config.telegramChatId, text, parse_mode: 'Markdown' }),
    }).catch(err => console.warn('[AlertEngine] Telegram failed:', err));
  }

  /** Email (placeholder — requires SMTP transport in production) */
  private sendEmail(alert: Alert): void {
    if (!this.config.emailTo) return;
    // In production: use nodemailer or similar
    console.log(`[AlertEngine] EMAIL → ${this.config.emailTo}: ${alert.title} — ${alert.body}`);
  }

  /** Mobile push (placeholder — requires FCM/APNs in production) */
  private sendMobile(alert: Alert): void {
    // In production: use Firebase Cloud Messaging / Apple Push
    console.log(`[AlertEngine] MOBILE PUSH: ${alert.title}`);
  }

  private meetsMinPriority(priority: AlertPriority): boolean {
    const levels: AlertPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
    return levels.indexOf(priority) >= levels.indexOf(this.config.minPriority);
  }

  // --- Public API ---
  getAlerts(limit?: number): Alert[] {
    const sorted = [...this.alerts].sort((a, b) => b.timestamp - a.timestamp);
    return limit ? sorted.slice(0, limit) : sorted;
  }

  acknowledge(id: string): void {
    const a = this.alerts.find(al => al.id === id);
    if (a) a.acknowledged = true;
  }

  getUnacknowledged(): Alert[] { return this.alerts.filter(a => !a.acknowledged); }

  clearAll(): void { this.alerts = []; }

  updateConfig(config: Partial<AlertConfig>): void {
    this.config = { ...this.config, ...config };
  }

  /** Request notification permissions (call early in app lifecycle) */
  async requestPermission(): Promise<boolean> {
    if (typeof Notification === 'undefined') return false;
    const result = await Notification.requestPermission();
    return result === 'granted';
  }
}
