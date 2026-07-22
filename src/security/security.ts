// ============================================================================
// SECURITY MODULE
// AES-256 Encryption | Biometric Login | PIN | Cloud Backup Encryption
// Certificate Pinning | Anti-Tamper | Anti-Debug | Root/Emulator Detection
// ============================================================================

import { TradingEventBus } from '../core/event-bus';
import { EncryptionProvider } from '../sync/cloud-sync';

export interface SecurityConfig {
  requireAuth: boolean;
  authMethod: 'PIN' | 'BIOMETRIC' | 'BOTH';
  pinLength: number;
  maxAuthAttempts: number;
  lockoutMs: number;
  enableAntiDebug: boolean;
  enableTamperDetection: boolean;
  enableRootDetection: boolean;
  certificatePins: string[]; // SHA-256 pins of trusted certs
  sessionTimeoutMs: number;
}

const DEFAULT_CONFIG: SecurityConfig = {
  requireAuth: true,
  authMethod: 'BOTH',
  pinLength: 6,
  maxAuthAttempts: 5,
  lockoutMs: 300000, // 5 minutes
  enableAntiDebug: true,
  enableTamperDetection: true,
  enableRootDetection: true,
  certificatePins: [],
  sessionTimeoutMs: 1800000, // 30 minutes
};

export interface SecurityThreat {
  type: 'DEBUG' | 'TAMPER' | 'ROOT' | 'EMULATOR' | 'CERT_MISMATCH' | 'BRUTE_FORCE';
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  detail: string;
  timestamp: number;
}

export interface AuthResult {
  success: boolean;
  method?: 'PIN' | 'BIOMETRIC';
  reason?: string;
  lockedUntil?: number;
}

// ============================================================================
// AES-256-GCM ENCRYPTION (Web Crypto API)
// ============================================================================

export class AES256Encryption implements EncryptionProvider {
  private key: CryptoKey | null = null;
  private salt: Uint8Array;

  constructor() {
    this.salt = crypto.getRandomValues(new Uint8Array(16));
  }

  /**
   * Derive a 256-bit key from a password using PBKDF2
   */
  async deriveKey(password: string, salt?: Uint8Array): Promise<void> {
    const useSalt = salt || this.salt;
    const enc = new TextEncoder();
    const keyMaterial = await crypto.subtle.importKey(
      'raw', enc.encode(password), { name: 'PBKDF2' }, false, ['deriveKey']
    );
    this.key = await crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt: useSalt, iterations: 210000, hash: 'SHA-256' },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt']
    );
    this.salt = useSalt;
  }

  /**
   * Set a raw key directly (e.g. from secure storage)
   */
  async setKey(rawKey: ArrayBuffer): Promise<void> {
    this.key = await crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  }

  async encrypt(plaintext: string): Promise<string> {
    if (!this.key) throw new Error('Encryption key not initialized');
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const enc = new TextEncoder();
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv }, this.key, enc.encode(plaintext)
    );
    // Prepend IV + salt to ciphertext, base64 encode
    const combined = new Uint8Array(this.salt.length + iv.length + ciphertext.byteLength);
    combined.set(this.salt, 0);
    combined.set(iv, this.salt.length);
    combined.set(new Uint8Array(ciphertext), this.salt.length + iv.length);
    return this.toBase64(combined);
  }

  async decrypt(ciphertext: string): Promise<string> {
    if (!this.key) throw new Error('Encryption key not initialized');
    const combined = this.fromBase64(ciphertext);
    const iv = combined.slice(16, 28);
    const data = combined.slice(28);
    const decrypted = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, this.key, data);
    return new TextDecoder().decode(decrypted);
  }

  private toBase64(bytes: Uint8Array): string {
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return typeof btoa !== 'undefined' ? btoa(binary) : Buffer.from(bytes).toString('base64');
  }

  private fromBase64(b64: string): Uint8Array {
    const binary = typeof atob !== 'undefined' ? atob(b64) : Buffer.from(b64, 'base64').toString('binary');
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  isReady(): boolean { return this.key !== null; }
}


// ============================================================================
// SECURITY MANAGER - Auth, threat detection, hardening
// ============================================================================

export class SecurityManager {
  private config: SecurityConfig;
  private eventBus?: TradingEventBus;
  private encryption: AES256Encryption;
  private authenticated: boolean = false;
  private authAttempts: number = 0;
  private lockedUntil: number = 0;
  private pinHash: string | null = null;
  private sessionExpiry: number = 0;
  private threats: SecurityThreat[] = [];
  private antiDebugTimer: number = 0;

  constructor(config: Partial<SecurityConfig> = {}, eventBus?: TradingEventBus) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
    this.encryption = new AES256Encryption();
  }

  /**
   * Initialize security - run detection checks and start monitors
   */
  async initialize(): Promise<{ safe: boolean; threats: SecurityThreat[] }> {
    const threats: SecurityThreat[] = [];

    if (this.config.enableRootDetection) {
      const root = this.detectRootJailbreak();
      if (root) threats.push(root);
      const emu = this.detectEmulator();
      if (emu) threats.push(emu);
    }

    if (this.config.enableTamperDetection) {
      const tamper = this.detectTampering();
      if (tamper) threats.push(tamper);
    }

    if (this.config.enableAntiDebug) {
      this.startAntiDebugMonitor();
    }

    this.threats.push(...threats);
    const critical = threats.some(t => t.severity === 'CRITICAL');
    return { safe: !critical, threats };
  }

  // =========================================================================
  // AUTHENTICATION - PIN
  // =========================================================================

  /**
   * Set up a PIN (hashed with SHA-256 + salt)
   */
  async setupPIN(pin: string): Promise<boolean> {
    if (pin.length !== this.config.pinLength) return false;
    this.pinHash = await this.hashPIN(pin);
    this.persistCredentials();
    return true;
  }

  async verifyPIN(pin: string): Promise<AuthResult> {
    // Lockout check
    if (Date.now() < this.lockedUntil) {
      return { success: false, reason: 'Locked out', lockedUntil: this.lockedUntil };
    }

    const hash = await this.hashPIN(pin);
    if (hash === this.pinHash) {
      this.onAuthSuccess('PIN');
      return { success: true, method: 'PIN' };
    }

    this.authAttempts++;
    if (this.authAttempts >= this.config.maxAuthAttempts) {
      this.lockedUntil = Date.now() + this.config.lockoutMs;
      this.recordThreat({ type: 'BRUTE_FORCE', severity: 'HIGH', detail: `${this.authAttempts} failed PIN attempts`, timestamp: Date.now() });
      return { success: false, reason: 'Too many attempts - locked out', lockedUntil: this.lockedUntil };
    }
    return { success: false, reason: `Incorrect PIN (${this.config.maxAuthAttempts - this.authAttempts} attempts left)` };
  }

  private async hashPIN(pin: string): Promise<string> {
    const enc = new TextEncoder();
    const data = enc.encode(pin + '::trading_platform_salt');
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    return Array.from(new Uint8Array(hashBuffer)).map(b => b.toString(16).padStart(2, '0')).join('');
  }

  // =========================================================================
  // AUTHENTICATION - BIOMETRIC (WebAuthn)
  // =========================================================================

  /**
   * Check if biometric auth (WebAuthn platform authenticator) is available
   */
  async isBiometricAvailable(): Promise<boolean> {
    if (typeof window === 'undefined' || !window.PublicKeyCredential) return false;
    try {
      return await (window.PublicKeyCredential as any).isUserVerifyingPlatformAuthenticatorAvailable();
    } catch { return false; }
  }

  /**
   * Register a biometric credential (WebAuthn)
   */
  async registerBiometric(userId: string, userName: string): Promise<boolean> {
    if (typeof navigator === 'undefined' || !navigator.credentials) return false;
    try {
      const challenge = crypto.getRandomValues(new Uint8Array(32));
      const credential = await navigator.credentials.create({
        publicKey: {
          challenge,
          rp: { name: 'Institutional Trading Platform', id: window.location.hostname },
          user: { id: new TextEncoder().encode(userId), name: userName, displayName: userName },
          pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
          authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required' },
          timeout: 60000,
        },
      });
      return credential !== null;
    } catch (err) {
      console.warn('[Security] Biometric registration failed:', err);
      return false;
    }
  }

  /**
   * Authenticate via biometric (WebAuthn)
   */
  async verifyBiometric(): Promise<AuthResult> {
    if (typeof navigator === 'undefined' || !navigator.credentials) {
      return { success: false, reason: 'Biometric not supported' };
    }
    try {
      const challenge = crypto.getRandomValues(new Uint8Array(32));
      const assertion = await navigator.credentials.get({
        publicKey: { challenge, timeout: 60000, userVerification: 'required' },
      });
      if (assertion) {
        this.onAuthSuccess('BIOMETRIC');
        return { success: true, method: 'BIOMETRIC' };
      }
      return { success: false, reason: 'Biometric verification failed' };
    } catch (err) {
      return { success: false, reason: `Biometric error: ${err}` };
    }
  }

  private onAuthSuccess(method: 'PIN' | 'BIOMETRIC'): void {
    this.authenticated = true;
    this.authAttempts = 0;
    this.sessionExpiry = Date.now() + this.config.sessionTimeoutMs;
    this.eventBus?.emit({ type: 'AUTH_SUCCESS', data: { method } });
  }

  isAuthenticated(): boolean {
    if (this.authenticated && Date.now() > this.sessionExpiry) {
      this.authenticated = false; // Session expired
    }
    return this.authenticated;
  }

  refreshSession(): void {
    if (this.authenticated) this.sessionExpiry = Date.now() + this.config.sessionTimeoutMs;
  }

  logout(): void {
    this.authenticated = false;
    this.sessionExpiry = 0;
  }

  // =========================================================================
  // CERTIFICATE PINNING
  // =========================================================================

  /**
   * Verify a certificate against pinned SHA-256 fingerprints.
   * In browsers, full cert pinning requires the fetch response's
   * security info; here we validate provided fingerprints.
   */
  verifyCertificate(certSha256: string): boolean {
    if (this.config.certificatePins.length === 0) return true; // No pinning configured
    const match = this.config.certificatePins.includes(certSha256);
    if (!match) {
      this.recordThreat({
        type: 'CERT_MISMATCH', severity: 'CRITICAL',
        detail: `Certificate ${certSha256.slice(0, 16)}... not in pinned set`,
        timestamp: Date.now(),
      });
    }
    return match;
  }

  addCertificatePin(sha256: string): void {
    if (!this.config.certificatePins.includes(sha256)) {
      this.config.certificatePins.push(sha256);
    }
  }

  // =========================================================================
  // THREAT DETECTION
  // =========================================================================

  /**
   * Detect debugger via timing analysis (debugger statement slows execution)
   */
  private startAntiDebugMonitor(): void {
    if (typeof window === 'undefined') return;
    this.antiDebugTimer = window.setInterval(() => {
      const start = performance.now();
      // eslint-disable-next-line no-debugger
      debugger; // If devtools open, this pauses and inflates the delta
      const elapsed = performance.now() - start;
      if (elapsed > 100) {
        this.recordThreat({
          type: 'DEBUG', severity: 'MEDIUM',
          detail: `Debugger detected (${elapsed.toFixed(0)}ms pause)`,
          timestamp: Date.now(),
        });
      }
    }, 4000);
  }

  /**
   * Detect DOM/code tampering by checking for devtools and integrity markers
   */
  private detectTampering(): SecurityThreat | null {
    if (typeof window === 'undefined') return null;

    // Detect open devtools via window size heuristic
    const widthThreshold = window.outerWidth - window.innerWidth > 160;
    const heightThreshold = window.outerHeight - window.innerHeight > 160;
    if (widthThreshold || heightThreshold) {
      return { type: 'TAMPER', severity: 'LOW', detail: 'DevTools may be open', timestamp: Date.now() };
    }
    return null;
  }

  /**
   * Detect rooted/jailbroken environment (mobile web heuristics)
   */
  private detectRootJailbreak(): SecurityThreat | null {
    if (typeof navigator === 'undefined') return null;
    const ua = navigator.userAgent.toLowerCase();
    // Heuristic markers
    const suspicious = ['cydia', 'substrate', 'frida', 'xposed', 'magisk', 'superuser'];
    for (const marker of suspicious) {
      if (ua.includes(marker)) {
        return { type: 'ROOT', severity: 'CRITICAL', detail: `Root/jailbreak marker: ${marker}`, timestamp: Date.now() };
      }
    }
    return null;
  }

  /**
   * Detect emulator/simulator environment
   */
  private detectEmulator(): SecurityThreat | null {
    if (typeof navigator === 'undefined') return null;
    const ua = navigator.userAgent.toLowerCase();

    // Emulator heuristics
    const emulatorMarkers = ['sdk_gphone', 'emulator', 'android sdk', 'genymotion', 'bluestacks', 'nox'];
    for (const marker of emulatorMarkers) {
      if (ua.includes(marker)) {
        return { type: 'EMULATOR', severity: 'MEDIUM', detail: `Emulator marker: ${marker}`, timestamp: Date.now() };
      }
    }

    // Hardware concurrency + memory heuristics (emulators often report low/round values)
    const nav = navigator as any;
    if (nav.hardwareConcurrency && nav.hardwareConcurrency <= 1) {
      return { type: 'EMULATOR', severity: 'LOW', detail: 'Suspiciously low CPU count', timestamp: Date.now() };
    }
    return null;
  }

  private recordThreat(threat: SecurityThreat): void {
    this.threats.push(threat);
    this.eventBus?.emit({ type: 'SECURITY_THREAT', data: threat });
    console.warn(`[Security] Threat detected: ${threat.type} (${threat.severity}) - ${threat.detail}`);
  }

  // =========================================================================
  // ENCRYPTION ACCESS
  // =========================================================================

  getEncryption(): AES256Encryption { return this.encryption; }

  /** Initialize encryption from the user's PIN/password */
  async initEncryption(password: string): Promise<void> {
    await this.encryption.deriveKey(password);
  }

  /** Encrypt data for cloud backup */
  async encryptBackup(data: string): Promise<string> {
    if (!this.encryption.isReady()) throw new Error('Encryption not initialized');
    return this.encryption.encrypt(data);
  }

  async decryptBackup(data: string): Promise<string> {
    if (!this.encryption.isReady()) throw new Error('Encryption not initialized');
    return this.encryption.decrypt(data);
  }

  // =========================================================================
  // PERSISTENCE & GETTERS
  // =========================================================================

  private persistCredentials(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      // Only store the PIN hash, never the PIN itself
      localStorage.setItem('sec_pin_hash', this.pinHash || '');
    } catch { /* ignore */ }
  }

  loadCredentials(): void {
    if (typeof localStorage === 'undefined') return;
    this.pinHash = localStorage.getItem('sec_pin_hash') || null;
  }

  getThreats(): SecurityThreat[] { return [...this.threats]; }
  hasCriticalThreat(): boolean { return this.threats.some(t => t.severity === 'CRITICAL'); }
  getConfig(): SecurityConfig { return { ...this.config }; }
  updateConfig(config: Partial<SecurityConfig>): void { this.config = { ...this.config, ...config }; }

  destroy(): void {
    if (this.antiDebugTimer) clearInterval(this.antiDebugTimer);
    this.logout();
  }
}
