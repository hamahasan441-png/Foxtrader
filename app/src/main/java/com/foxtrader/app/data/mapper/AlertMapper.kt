package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.AlertEntity
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert

/**
 * Alert entity <-> domain mapping.
 *
 * `dispatchedTo` is intentionally not persisted: it records which channels a
 * single delivery attempt used, which is transport detail with no meaning once
 * the alert is in the inbox. Reconstructing it would imply a delivery guarantee
 * the app cannot make.
 */
fun AlertEntity.toDomain(): FoxAlert = FoxAlert(
    id = id,
    title = title,
    body = body,
    priority = runCatching { AlertPriority.valueOf(priority) }
        .getOrDefault(AlertPriority.MEDIUM),
    symbol = symbol,
    timestamp = timestamp,
    acknowledged = acknowledged,
)

fun FoxAlert.toEntity(): AlertEntity = AlertEntity(
    id = id,
    title = title,
    body = body,
    priority = priority.name,
    symbol = symbol,
    timestamp = timestamp,
    acknowledged = acknowledged,
)
