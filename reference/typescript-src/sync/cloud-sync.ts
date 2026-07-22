// ============================================================================
// CLOUD SYNC
// Syncs: Watchlists, Templates, Trades, Journal, Drawings, Settings, Alerts
// Cross-device synchronization with conflict resolution and offline queue.
// ============================================================================

import { TradingEventBus } from '../core/event-bus';

export type SyncableType =
  | 'WATCHLISTS' | 'TEMPLATES' | 'TRADES' | 'JOURNAL'
  | 'DRAWINGS' | 'SETTINGS' | 'ALERTS';

export type SyncStatus = 'IDLE' | 'SYNCING' | 'SYNCED' | 'OFFLINE' | 'ERROR' | 'CONFLICT';

export interface SyncItem<T = unknown> {
  id: string;
  type: SyncableType;
  data: T;
  /** Vector clock / version for conflict resolution */
  version: number;
  /** Last modified timestamp */
  updatedAt: number;
  /** Device that made the last change */
  deviceId: string;
  /** Soft-delete flag */
  deleted: boolean;
  /** Content hash for change detection */
  hash: string;
}

export interface SyncConfig {
  endpoint: string;
  userId: string;
  deviceId: string;
  autoSync: boolean;
  syncIntervalMs: number;
  /** Conflict resolution strategy */
  conflictStrategy: 'LAST_WRITE_WINS' | 'MERGE' | 'MANUAL';
  encryptPayload: boolean;
}

const DEFAULT_CONFIG: SyncConfig = {
  endpoint: 'https://api.tradingplatform.example/sync',
  userId: '',
  deviceId: '',
  autoSync: true,
  syncIntervalMs: 30000,
  conflictStrategy: 'LAST_WRITE_WINS',
  encryptPayload: true,
};

export interface SyncResult {
  status: SyncStatus;
  pushed: number;
  pulled: number;
  conflicts: SyncConflict[];
  timestamp: number;
  error?: string;
}

export interface SyncConflict {
  itemId: string;
  type: SyncableType;
  localVersion: SyncItem;
  remoteVersion: SyncItem;
  resolution?: 'LOCAL' | 'REMOTE' | 'MERGED';
}

/** Interface for the encryption provider (implemented by security module) */
export interface EncryptionProvider {
  encrypt(plaintext: string): Promise<string>;
  decrypt(ciphertext: string): Promise<string>;
}

export class CloudSync {
  private config: SyncConfig;
  private eventBus?: TradingEventBus;
  private localStore: Map<string, SyncItem> = new Map();
  private offlineQueue: SyncItem[] = [];
  private status: SyncStatus = 'IDLE';
  private syncTimer: number = 0;
  private lastSyncTime: number = 0;
  private encryption?: EncryptionProvider;
  private online: boolean = true;

  constructor(config: Partial<SyncConfig> = {}, eventBus?: TradingEventBus, encryption?: EncryptionProvider) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.eventBus = eventBus;
    this.encryption = encryption;
    if (!this.config.deviceId) this.config.deviceId = this.generateDeviceId();
    this.loadLocal();
    this.setupOnlineDetection();
  }

  // =========================================================================
  // LOCAL DATA MANAGEMENT
  // =========================================================================

  /**
   * Store/update a syncable item locally and queue for sync
   */
  put<T>(type: SyncableType, id: string, data: T): SyncItem<T> {
    const key = this.key(type, id);
    const existing = this.localStore.get(key);
    const hash = this.hashData(data);

    // No change - skip
    if (existing && existing.hash === hash && !existing.deleted) {
      return existing as SyncItem<T>;
    }

    const item: SyncItem<T> = {
      id, type, data,
      version: existing ? existing.version + 1 : 1,
      updatedAt: Date.now(),
      deviceId: this.config.deviceId,
      deleted: false,
      hash,
    };

    this.localStore.set(key, item as SyncItem);
    this.offlineQueue.push(item as SyncItem);
    this.persistLocal();

    if (this.config.autoSync && this.online) {
      this.scheduleSyncSoon();
    }
    return item;
  }

  /**
   * Soft-delete an item
   */
  delete(type: SyncableType, id: string): void {
    const key = this.key(type, id);
    const existing = this.localStore.get(key);
    if (!existing) return;
    existing.deleted = true;
    existing.version++;
    existing.updatedAt = Date.now();
    existing.deviceId = this.config.deviceId;
    this.offlineQueue.push(existing);
    this.persistLocal();
    if (this.config.autoSync && this.online) this.scheduleSyncSoon();
  }

  get<T>(type: SyncableType, id: string): T | undefined {
    const item = this.localStore.get(this.key(type, id));
    return item && !item.deleted ? (item.data as T) : undefined;
  }

  getAll<T>(type: SyncableType): T[] {
    return Array.from(this.localStore.values())
      .filter(i => i.type === type && !i.deleted)
      .map(i => i.data as T);
  }

  // =========================================================================
  // SYNC ORCHESTRATION
  // =========================================================================

  /**
   * Perform a full sync: push local changes, pull remote changes, resolve conflicts
   */
  async sync(): Promise<SyncResult> {
    if (!this.online) {
      this.setStatus('OFFLINE');
      return { status: 'OFFLINE', pushed: 0, pulled: 0, conflicts: [], timestamp: Date.now() };
    }

    this.setStatus('SYNCING');

    try {
      // 1. Push offline queue
      const pushed = await this.pushChanges();

      // 2. Pull remote changes since last sync
      const { items: remoteItems, conflicts } = await this.pullChanges();

      // 3. Apply remote changes (non-conflicting)
      let pulled = 0;
      for (const remote of remoteItems) {
        const key = this.key(remote.type, remote.id);
        const local = this.localStore.get(key);
        if (!local || remote.version > local.version || remote.updatedAt > local.updatedAt) {
          this.localStore.set(key, remote);
          pulled++;
        }
      }

      // 4. Resolve conflicts
      const resolvedConflicts = this.resolveConflicts(conflicts);

      this.persistLocal();
      this.lastSyncTime = Date.now();
      this.offlineQueue = [];
      this.setStatus(resolvedConflicts.some(c => !c.resolution) ? 'CONFLICT' : 'SYNCED');

      const result: SyncResult = {
        status: this.status, pushed, pulled, conflicts: resolvedConflicts, timestamp: Date.now(),
      };
      this.eventBus?.emit({ type: 'SYNC_COMPLETE', data: result });
      return result;
    } catch (err) {
      this.setStatus('ERROR');
      return { status: 'ERROR', pushed: 0, pulled: 0, conflicts: [], timestamp: Date.now(), error: String(err) };
    }
  }

  /**
   * Push local changes to remote endpoint
   */
  private async pushChanges(): Promise<number> {
    if (this.offlineQueue.length === 0) return 0;

    const payload = await this.preparePayload(this.offlineQueue);
    const response = await fetch(`${this.config.endpoint}/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User-Id': this.config.userId, 'X-Device-Id': this.config.deviceId },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(15000),
    });

    if (!response.ok) throw new Error(`Push failed: HTTP ${response.status}`);
    return this.offlineQueue.length;
  }

  /**
   * Pull remote changes since last sync
   */
  private async pullChanges(): Promise<{ items: SyncItem[]; conflicts: SyncConflict[] }> {
    const response = await fetch(`${this.config.endpoint}/pull?since=${this.lastSyncTime}`, {
      method: 'GET',
      headers: { 'X-User-Id': this.config.userId, 'X-Device-Id': this.config.deviceId },
      signal: AbortSignal.timeout(15000),
    });

    if (!response.ok) throw new Error(`Pull failed: HTTP ${response.status}`);
    const raw = await response.json() as { items: string | SyncItem[] };
    const items = await this.parsePayload(raw.items);

    // Detect conflicts: item modified both locally (in queue) and remotely
    const conflicts: SyncConflict[] = [];
    const nonConflicting: SyncItem[] = [];

    for (const remote of items) {
      const key = this.key(remote.type, remote.id);
      const localQueued = this.offlineQueue.find(q => this.key(q.type, q.id) === key);
      const local = this.localStore.get(key);

      if (localQueued && local && local.deviceId !== remote.deviceId && local.version === remote.version) {
        // Concurrent modification = conflict
        conflicts.push({ itemId: remote.id, type: remote.type, localVersion: local, remoteVersion: remote });
      } else {
        nonConflicting.push(remote);
      }
    }

    return { items: nonConflicting, conflicts };
  }

  // =========================================================================
  // CONFLICT RESOLUTION
  // =========================================================================

  private resolveConflicts(conflicts: SyncConflict[]): SyncConflict[] {
    for (const conflict of conflicts) {
      switch (this.config.conflictStrategy) {
        case 'LAST_WRITE_WINS': {
          const winner = conflict.localVersion.updatedAt >= conflict.remoteVersion.updatedAt
            ? conflict.localVersion : conflict.remoteVersion;
          const key = this.key(winner.type, winner.id);
          this.localStore.set(key, winner);
          conflict.resolution = winner === conflict.localVersion ? 'LOCAL' : 'REMOTE';
          break;
        }
        case 'MERGE': {
          const merged = this.mergeItems(conflict.localVersion, conflict.remoteVersion);
          this.localStore.set(this.key(merged.type, merged.id), merged);
          conflict.resolution = 'MERGED';
          break;
        }
        case 'MANUAL':
          // Leave unresolved for user decision
          break;
      }
    }
    return conflicts;
  }

  private mergeItems(local: SyncItem, remote: SyncItem): SyncItem {
    // For array/object data, shallow-merge; otherwise last-write-wins
    let mergedData: unknown = remote.data;
    if (Array.isArray(local.data) && Array.isArray(remote.data)) {
      // Union by JSON identity
      const seen = new Set<string>();
      mergedData = [...(local.data as unknown[]), ...(remote.data as unknown[])].filter(item => {
        const k = JSON.stringify(item);
        if (seen.has(k)) return false; seen.add(k); return true;
      });
    } else if (typeof local.data === 'object' && typeof remote.data === 'object' && local.data && remote.data) {
      mergedData = { ...(remote.data as object), ...(local.data as object) };
    }
    return {
      ...local,
      data: mergedData,
      version: Math.max(local.version, remote.version) + 1,
      updatedAt: Date.now(),
      hash: this.hashData(mergedData),
    };
  }

  /**
   * Manually resolve a conflict
   */
  resolveManually(conflict: SyncConflict, choice: 'LOCAL' | 'REMOTE'): void {
    const winner = choice === 'LOCAL' ? conflict.localVersion : conflict.remoteVersion;
    this.localStore.set(this.key(winner.type, winner.id), winner);
    conflict.resolution = choice;
    this.persistLocal();
  }

  // =========================================================================
  // ENCRYPTION & SERIALIZATION
  // =========================================================================

  private async preparePayload(items: SyncItem[]): Promise<{ items: string | SyncItem[] }> {
    const json = JSON.stringify(items);
    if (this.config.encryptPayload && this.encryption) {
      return { items: await this.encryption.encrypt(json) };
    }
    return { items };
  }

  private async parsePayload(raw: string | SyncItem[]): Promise<SyncItem[]> {
    if (typeof raw === 'string') {
      if (this.config.encryptPayload && this.encryption) {
        const decrypted = await this.encryption.decrypt(raw);
        return JSON.parse(decrypted);
      }
      return JSON.parse(raw);
    }
    return raw;
  }

  // =========================================================================
  // AUTO-SYNC & ONLINE DETECTION
  // =========================================================================

  startAutoSync(): void {
    if (this.syncTimer) return;
    this.syncTimer = window.setInterval(() => {
      if (this.online) this.sync();
    }, this.config.syncIntervalMs);
  }

  stopAutoSync(): void {
    if (this.syncTimer) { clearInterval(this.syncTimer); this.syncTimer = 0; }
  }

  private scheduleSyncSoon(): void {
    // Debounced immediate sync
    setTimeout(() => { if (this.online) this.sync(); }, 2000);
  }

  private setupOnlineDetection(): void {
    if (typeof window === 'undefined') return;
    this.online = navigator.onLine ?? true;
    window.addEventListener('online', () => {
      this.online = true;
      this.eventBus?.emit({ type: 'SYNC_ONLINE', data: {} });
      this.sync(); // Sync queued changes on reconnect
    });
    window.addEventListener('offline', () => {
      this.online = false;
      this.setStatus('OFFLINE');
    });
  }

  // =========================================================================
  // PERSISTENCE & HELPERS
  // =========================================================================

  private persistLocal(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      localStorage.setItem('cloud_sync_store', JSON.stringify(Array.from(this.localStore.entries())));
      localStorage.setItem('cloud_sync_queue', JSON.stringify(this.offlineQueue));
    } catch (err) { console.warn('[CloudSync] Persist failed:', err); }
  }

  private loadLocal(): void {
    if (typeof localStorage === 'undefined') return;
    try {
      const store = localStorage.getItem('cloud_sync_store');
      if (store) this.localStore = new Map(JSON.parse(store));
      const queue = localStorage.getItem('cloud_sync_queue');
      if (queue) this.offlineQueue = JSON.parse(queue);
    } catch (err) { console.warn('[CloudSync] Load failed:', err); }
  }

  private key(type: SyncableType, id: string): string { return `${type}:${id}`; }

  private hashData(data: unknown): string {
    const str = JSON.stringify(data);
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
    }
    return hash.toString(36);
  }

  private generateDeviceId(): string {
    return `device_${Math.random().toString(36).slice(2)}_${Date.now().toString(36)}`;
  }

  private setStatus(status: SyncStatus): void {
    this.status = status;
    this.eventBus?.emit({ type: 'SYNC_STATUS', data: { status } });
  }

  // =========================================================================
  // PUBLIC API
  // =========================================================================

  getStatus(): SyncStatus { return this.status; }
  getLastSyncTime(): number { return this.lastSyncTime; }
  getPendingCount(): number { return this.offlineQueue.length; }
  isOnline(): boolean { return this.online; }
  setEncryption(provider: EncryptionProvider): void { this.encryption = provider; }
  updateConfig(config: Partial<SyncConfig>): void { this.config = { ...this.config, ...config }; }

  /** Full export of all synced data (for backup) */
  exportAll(): string {
    return JSON.stringify(Array.from(this.localStore.values()).filter(i => !i.deleted));
  }

  destroy(): void {
    this.stopAutoSync();
    this.persistLocal();
  }
}
