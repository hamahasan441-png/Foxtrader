// ============================================================================
// WORKER PIPELINE — Off-main-thread analysis computation
// ============================================================================

export { WorkerPool, workerPool } from './worker-pool';
export type { WorkerPoolConfig } from './worker-pool';
export { candlesToBuffer } from './analysis-worker';
export type { WorkerRequest, WorkerResponse, WorkerRequestType, WorkerResponseType } from './analysis-worker';
