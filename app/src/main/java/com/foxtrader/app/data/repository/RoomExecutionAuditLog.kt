package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.ExecutionAuditLogDao
import com.foxtrader.app.data.local.entity.ExecutionAuditLogEntity
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.execution.ExecutionAuditLog
import com.foxtrader.app.domain.usecase.execution.ExecutionReceipt
import com.foxtrader.app.domain.usecase.execution.TradeIntent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production, Room-backed [ExecutionAuditLog].
 *
 * Append-only per order idempotency key. The intent's identifying fields are
 * persisted so [findByIdempotencyKey] can block duplicate submissions and
 * reconciliation can correlate an order with the broker's position history.
 * No credentials or raw broker payloads are stored.
 */
@Singleton
class RoomExecutionAuditLog @Inject constructor(
    private val dao: ExecutionAuditLogDao,
) : ExecutionAuditLog {

    override suspend fun record(receipt: ExecutionReceipt) {
        dao.record(receipt.toEntity())
    }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt? =
        dao.findByIdempotencyKey(idempotencyKey)?.toReceipt()

    override suspend fun all(): List<ExecutionReceipt> = dao.all().map { it.toReceipt() }

    /** Returns all unresolved (UNKNOWN) receipts for reconciliation. */
    suspend fun unknown(): List<ExecutionReceipt> =
        dao.byStatus(ExecutionAuditLogEntity.STATUS_UNKNOWN).map { it.toReceipt() }

    // ========================================================================
    // MAPPING
    // ========================================================================

    private fun ExecutionReceipt.toEntity(): ExecutionAuditLogEntity {
        val intent = intent
        return ExecutionAuditLogEntity(
            idempotencyKey = intent.idempotencyKey,
            status = when (this) {
                is ExecutionReceipt.Accepted -> ExecutionAuditLogEntity.STATUS_ACCEPTED
                is ExecutionReceipt.Rejected -> ExecutionAuditLogEntity.STATUS_REJECTED
                is ExecutionReceipt.Unknown -> ExecutionAuditLogEntity.STATUS_UNKNOWN
            },
            symbol = intent.symbol,
            direction = intent.direction.name,
            volume = intent.volume,
            entryPrice = intent.entryPrice,
            stopLoss = intent.stopLoss,
            takeProfit = intent.takeProfit,
            orderId = (this as? ExecutionReceipt.Accepted)?.orderId,
            reasons = (this as? ExecutionReceipt.Rejected)?.reasons?.joinToString("; ").orEmpty(),
            timestamp = timestamp,
        )
    }

    private fun ExecutionAuditLogEntity.toReceipt(): ExecutionReceipt {
        val intent = TradeIntent(
            symbol = symbol,
            direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.BULLISH),
            volume = volume,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            confirmationTimestamp = timestamp,
            idempotencyKey = idempotencyKey,
        )
        return when (status) {
            ExecutionAuditLogEntity.STATUS_ACCEPTED -> ExecutionReceipt.Accepted(
                intent = intent,
                orderId = orderId.orEmpty(),
                fillPrice = null,
                timestamp = timestamp,
            )
            ExecutionAuditLogEntity.STATUS_UNKNOWN -> ExecutionReceipt.Unknown(
                intent = intent,
                timestamp = timestamp,
            )
            else -> ExecutionReceipt.Rejected(
                intent = intent,
                reasons = reasons.split("; ").filter { it.isNotBlank() },
                timestamp = timestamp,
            )
        }
    }
}
