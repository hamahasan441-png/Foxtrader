package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.ExecutionAuditLogDao
import com.foxtrader.app.data.local.entity.ExecutionAuditLogEntity
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.execution.ExecutionAuditLog
import com.foxtrader.app.domain.usecase.execution.ExecutionReceipt
import com.foxtrader.app.domain.usecase.execution.TradeIntent
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production, Room-backed [ExecutionAuditLog].
 *
 * Latest-state-per-order idempotency key. The intent's identifying fields are
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

    /** Returns unresolved (UNKNOWN) receipts for one broker-account scope. */
    suspend fun unknown(executionScope: String): List<ExecutionReceipt> {
        require(executionScope.isNotBlank()) { "Execution scope is required for reconciliation" }
        return dao.byStatusAndScope(ExecutionAuditLogEntity.STATUS_UNKNOWN, executionScope).map { it.toReceipt() }
    }

    /** Accepted close receipts whose broker history P/L has not synchronized yet. */
    suspend fun acceptedClosesWithUnknownProfit(executionScope: String): List<ExecutionReceipt.Accepted> {
        require(executionScope.isNotBlank()) { "Execution scope is required for reconciliation" }
        return dao.acceptedClosesWithUnknownProfit(
            ExecutionAuditLogEntity.STATUS_ACCEPTED, executionScope
        ).mapNotNull { it.toReceipt() as? ExecutionReceipt.Accepted }
    }

    // ========================================================================
    // MAPPING
    // ========================================================================

    /**
     * Returns today's realized gross loss for one broker account.
     *
     * A nullable result is deliberate: if the database contains a realized P&L
     * row from the pre-v10 unscoped schema for today, the value cannot be
     * attributed safely to this account/currency. Returning null makes an
     * enabled live daily-loss gate fail closed instead of mixing accounts.
     */
    suspend fun getTodayRealizedLoss(executionScope: String): Double? {
        require(executionScope.isNotBlank()) { "Execution scope is required for daily-loss calculation" }
        val dayStart = localDayStartMillis()
        if (dao.countLegacyRealizedSince(ExecutionAuditLogEntity.STATUS_ACCEPTED, dayStart) > 0) {
            return null
        }
        if (dao.countAcceptedClosesWithUnknownProfitSince(
                ExecutionAuditLogEntity.STATUS_ACCEPTED, executionScope, dayStart
            ) > 0
        ) {
            return null
        }
        val entities = dao.byStatusAndScopeSince(
            ExecutionAuditLogEntity.STATUS_ACCEPTED,
            executionScope,
            dayStart,
        )
        var grossLoss = 0.0
        for (e in entities) {
            val p = e.realizedProfit ?: continue
            if (!p.isFinite()) return null
            if (p < 0.0) grossLoss += -p
        }
        return grossLoss.takeIf { it.isFinite() }
    }

    /** Net-loss variant for diagnostics/product experimentation; currently unused. */
    @Suppress("unused")
    suspend fun getTodayNetLoss(executionScope: String): Double? {
        require(executionScope.isNotBlank()) { "Execution scope is required for daily-loss calculation" }
        val dayStart = localDayStartMillis()
        if (dao.countLegacyRealizedSince(ExecutionAuditLogEntity.STATUS_ACCEPTED, dayStart) > 0) {
            return null
        }
        if (dao.countAcceptedClosesWithUnknownProfitSince(
                ExecutionAuditLogEntity.STATUS_ACCEPTED, executionScope, dayStart
            ) > 0
        ) {
            return null
        }
        val entities = dao.byStatusAndScopeSince(
            ExecutionAuditLogEntity.STATUS_ACCEPTED,
            executionScope,
            dayStart,
        )
        var net = 0.0
        for (e in entities) {
            val p = e.realizedProfit ?: continue
            if (!p.isFinite()) return null
            net += p
        }
        if (!net.isFinite()) return null
        return if (net < 0.0) -net else 0.0
    }

    /**
     * Start of the user's local calendar day. The risk dashboard groups trades
     * by the device-local date as well; using UTC here made the safety gate and
     * the UI disagree for users outside UTC (especially around midnight/DST).
     */
    private fun localDayStartMillis(): Long =
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // ========================================================================
    // MAPPING
    // ========================================================================

    private fun ExecutionReceipt.toEntity(): ExecutionAuditLogEntity {
        val intent = intent
        return ExecutionAuditLogEntity(
            idempotencyKey = intent.idempotencyKey,
            executionScope = intent.executionScope,
            operationTag = intent.operationTag,
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
            realizedProfit = (this as? ExecutionReceipt.Accepted)?.realizedProfit,
        )
    }

    private fun ExecutionAuditLogEntity.toReceipt(): ExecutionReceipt {
        val intent = TradeIntent(
            symbol = symbol,
            direction = runCatching { Direction.valueOf(direction) }.getOrElse {
                throw IllegalStateException("Corrupt execution audit direction: $direction", it)
            },
            volume = volume,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            confirmationTimestamp = timestamp,
            executionScope = executionScope,
            operationTag = operationTag,
            idempotencyKey = idempotencyKey,
        )
        return when (status) {
            ExecutionAuditLogEntity.STATUS_ACCEPTED -> ExecutionReceipt.Accepted(
                intent = intent,
                orderId = orderId.orEmpty(),
                fillPrice = null,
                realizedProfit = realizedProfit,
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
