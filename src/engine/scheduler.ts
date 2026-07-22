// ============================================================================
// UNIFIED FRAME SCHEDULER
// Single rAF loop that orchestrates ALL rendering and updates.
// Priority lanes: CRITICAL > HIGH > NORMAL > LOW > IDLE
// Cooperative yielding: never block main thread beyond frame budget.
//
// Design:
// - One requestAnimationFrame loop (no competing loops)
// - Tasks registered with priority + callback
// - Frame budget tracking (8.3ms at 120fps, 16.6ms at 60fps)
// - Automatic FPS measurement
// - Idle callbacks for non-urgent work (GC-friendly)
// ============================================================================

export type TaskPriority = 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW' | 'IDLE';

export interface ScheduledTask {
  id: string;
  priority: TaskPriority;
  callback: (deltaMs: number, timestamp: number) => void;
  /** If true, runs every frame. If false, runs once then removes itself. */
  recurring: boolean;
  /** If true, task is currently active */
  active: boolean;
}

export interface SchedulerStats {
  fps: number;
  frameTimeMs: number;
  taskCount: number;
  droppedFrames: number;
  budgetUtilization: number; // 0-1 (how much of frame budget we're using)
}

const PRIORITY_ORDER: TaskPriority[] = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW', 'IDLE'];

export class FrameScheduler {
  private tasks: Map<string, ScheduledTask> = new Map();
  private rafId = 0;
  private running = false;
  private lastFrameTime = 0;
  private frameCount = 0;
  private fps = 0;
  private fpsUpdateTime = 0;
  private droppedFrames = 0;
  private lastFrameDuration = 0;

  // Frame budget: target 120fps = 8.33ms, but gracefully degrade
  private targetFPS = 120;
  private frameBudgetMs = 1000 / 120; // 8.33ms

  // =========================================================================
  // TASK MANAGEMENT
  // =========================================================================

  /**
   * Register a recurring task (runs every frame)
   */
  register(id: string, priority: TaskPriority, callback: (deltaMs: number, timestamp: number) => void): void {
    this.tasks.set(id, { id, priority, callback, recurring: true, active: true });
  }

  /**
   * Schedule a one-shot task (runs next frame, then removed)
   */
  scheduleOnce(id: string, priority: TaskPriority, callback: (deltaMs: number, timestamp: number) => void): void {
    this.tasks.set(id, { id, priority, callback, recurring: false, active: true });
  }

  /**
   * Pause a task (remains registered but doesn't execute)
   */
  pause(id: string): void {
    const task = this.tasks.get(id);
    if (task) task.active = false;
  }

  /**
   * Resume a paused task
   */
  resume(id: string): void {
    const task = this.tasks.get(id);
    if (task) task.active = true;
  }

  /**
   * Remove a task entirely
   */
  unregister(id: string): void {
    this.tasks.delete(id);
  }

  // =========================================================================
  // LIFECYCLE
  // =========================================================================

  start(): void {
    if (this.running) return;
    this.running = true;
    this.lastFrameTime = performance.now();
    this.fpsUpdateTime = this.lastFrameTime;
    this.tick(this.lastFrameTime);
  }

  stop(): void {
    this.running = false;
    if (this.rafId) {
      cancelAnimationFrame(this.rafId);
      this.rafId = 0;
    }
  }

  setTargetFPS(fps: number): void {
    this.targetFPS = fps;
    this.frameBudgetMs = 1000 / fps;
  }

  // =========================================================================
  // FRAME LOOP
  // =========================================================================

  private tick(timestamp: number): void {
    if (!this.running) return;
    this.rafId = requestAnimationFrame((t) => this.tick(t));

    const deltaMs = timestamp - this.lastFrameTime;
    this.lastFrameTime = timestamp;

    // Detect dropped frames (delta > 2x budget)
    if (deltaMs > this.frameBudgetMs * 2.5) {
      this.droppedFrames++;
    }

    // FPS counter (updated every second)
    this.frameCount++;
    if (timestamp - this.fpsUpdateTime >= 1000) {
      this.fps = this.frameCount;
      this.frameCount = 0;
      this.fpsUpdateTime = timestamp;
    }

    // Execute tasks in priority order, respecting frame budget
    const frameStart = performance.now();
    const toRemove: string[] = [];

    for (const priority of PRIORITY_ORDER) {
      for (const task of this.tasks.values()) {
        if (task.priority !== priority || !task.active) continue;

        // CRITICAL and HIGH always run. Others check budget.
        if (priority !== 'CRITICAL' && priority !== 'HIGH') {
          const elapsed = performance.now() - frameStart;
          if (elapsed >= this.frameBudgetMs * 0.8) {
            // Budget nearly exhausted — skip lower priority tasks this frame
            break;
          }
        }

        // IDLE tasks only run if we have >50% budget remaining
        if (priority === 'IDLE') {
          const elapsed = performance.now() - frameStart;
          if (elapsed >= this.frameBudgetMs * 0.5) break;
        }

        try {
          task.callback(deltaMs, timestamp);
        } catch (err) {
          console.error(`[Scheduler] Task '${task.id}' error:`, err);
        }

        if (!task.recurring) {
          toRemove.push(task.id);
        }
      }
    }

    // Cleanup one-shot tasks
    for (const id of toRemove) this.tasks.delete(id);

    this.lastFrameDuration = performance.now() - frameStart;
  }

  // =========================================================================
  // STATS
  // =========================================================================

  getStats(): SchedulerStats {
    return {
      fps: this.fps,
      frameTimeMs: this.lastFrameDuration,
      taskCount: this.tasks.size,
      droppedFrames: this.droppedFrames,
      budgetUtilization: this.lastFrameDuration / this.frameBudgetMs,
    };
  }

  getFPS(): number { return this.fps; }
  isRunning(): boolean { return this.running; }

  destroy(): void {
    this.stop();
    this.tasks.clear();
  }
}

// ============================================================================
// SINGLETON — one scheduler for the entire app
// ============================================================================
export const scheduler = new FrameScheduler();
