package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foxtrader.app.data.local.entity.ExecutionAuditLogEntity

/**
 * DAO for durable execution state.
 *
 * Records are keyed by order idempotency key; REPLACE updates the latest receipt
 * for that key (an order can transition UNKNOWN -> ACCEPTED after reconciliation)
 * while never allowing two live submissions for the same intent.
 */
@Dao
interface ExecutionAuditLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entity: ExecutionAuditLogEntity)

    @Query("SELECT * FROM execution_audit_log WHERE idempotencyKey = :key ORDER BY timestamp DESC LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): ExecutionAuditLogEntity?

    @Query("SELECT * FROM execution_audit_log ORDER BY timestamp ASC")
    suspend fun all(): List<ExecutionAuditLogEntity>

    @Query("SELECT * FROM execution_audit_log WHERE status = :status ORDER BY timestamp ASC")
    suspend fun byStatus(status: String): List<ExecutionAuditLogEntity>

    @Query("SELECT * FROM execution_audit_log WHERE status = :status AND executionScope = :executionScope ORDER BY timestamp ASC")
    suspend fun byStatusAndScope(status: String, executionScope: String): List<ExecutionAuditLogEntity>

    @Query("SELECT * FROM execution_audit_log WHERE status = :status AND executionScope = :executionScope AND timestamp >= :dayStart ORDER BY timestamp ASC")
    suspend fun byStatusAndScopeSince(status: String, executionScope: String, dayStart: Long): List<ExecutionAuditLogEntity>

    @Query("SELECT COUNT(*) FROM execution_audit_log WHERE status = :status AND executionScope = '' AND timestamp >= :dayStart AND realizedProfit IS NOT NULL")
    suspend fun countLegacyRealizedSince(status: String, dayStart: Long): Int

    @Query("SELECT COUNT(*) FROM execution_audit_log WHERE status = :status AND executionScope = :executionScope AND timestamp >= :dayStart AND operationTag LIKE 'CLOSE:%' AND realizedProfit IS NULL")
    suspend fun countAcceptedClosesWithUnknownProfitSince(status: String, executionScope: String, dayStart: Long): Int

    @Query("SELECT * FROM execution_audit_log WHERE status = :status AND executionScope = :executionScope AND operationTag LIKE 'CLOSE:%' AND realizedProfit IS NULL ORDER BY timestamp ASC")
    suspend fun acceptedClosesWithUnknownProfit(status: String, executionScope: String): List<ExecutionAuditLogEntity>

    @Query("DELETE FROM execution_audit_log WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
