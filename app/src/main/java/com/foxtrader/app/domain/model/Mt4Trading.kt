package com.foxtrader.app.domain.model

/**
 * Broker-authoritative pending order exposed by MetaApi.
 * Kept separate from [Mt4Position] so a pending order can never be mistaken for
 * an already-open market position in close/SL/TP workflows.
 */
data class Mt4PendingOrder(
    val ticket: Long,
    val symbol: String,
    val type: Mt4OrderType,
    val lots: Double,
    val remainingLots: Double,
    val openPrice: Double,
    val currentPrice: Double?,
    val openTime: Long,
    val sl: Double,
    val tp: Double,
    val state: String,
    val expirationType: Mt4PendingExpirationType = Mt4PendingExpirationType.GTC,
    val expirationTime: Long? = null,
)

enum class Mt4PendingExpirationType {
    GTC,
    DAY,
    SPECIFIED,
    SPECIFIED_DAY,
}

/** User-reviewed request for a new pending MT4/MT5 order. */
data class Mt4PendingOrderRequest(
    val symbol: String,
    val type: Mt4OrderType,
    val lots: Double,
    val openPrice: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val expirationType: Mt4PendingExpirationType = Mt4PendingExpirationType.GTC,
    val expirationTime: Long? = null,
) {
    init {
        require(type in PENDING_TYPES) { "Pending order request requires a pending order type" }
    }

    companion object {
        val PENDING_TYPES = setOf(
            Mt4OrderType.BUY_LIMIT,
            Mt4OrderType.SELL_LIMIT,
            Mt4OrderType.BUY_STOP,
            Mt4OrderType.SELL_STOP,
        )
    }
}

/** Broker-side protective-position update. Null SL/TP preserves that field. */
data class Mt4PositionProtection(
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    /** MetaApi trailing-stop distance in broker points; null disables changes to TSL. */
    val trailingDistancePoints: Double? = null,
)

/** Broker state captured when the user opens a pending-order management review. */
data class Mt4PendingOrderSnapshot(
    val openPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val lots: Double,
) {
    init {
        require(openPrice.isFinite() && openPrice > 0.0) { "Reviewed pending price must be positive and finite" }
        require(stopLoss.isFinite() && stopLoss >= 0.0) { "Reviewed stop loss must be finite and non-negative" }
        require(takeProfit.isFinite() && takeProfit >= 0.0) { "Reviewed take profit must be finite and non-negative" }
        require(lots.isFinite() && lots > 0.0) { "Reviewed pending volume must be positive and finite" }
    }
}

/** Broker state captured when the user opens a position-management review. */
data class Mt4PositionSnapshot(
    val lots: Double,
    val stopLoss: Double,
    val takeProfit: Double,
) {
    init {
        require(lots.isFinite() && lots > 0.0) { "Reviewed position volume must be positive and finite" }
        require(stopLoss.isFinite() && stopLoss >= 0.0) { "Reviewed stop loss must be finite and non-negative" }
        require(takeProfit.isFinite() && takeProfit >= 0.0) { "Reviewed take profit must be finite and non-negative" }
    }
}

/**
 * One-shot chart-to-broker hand-off. This is a draft only: it never executes a
 * trade. The broker screen must still perform its normal review/confirm gates.
 */
data class BrokerTradeDraft(
    val symbol: String,
    val direction: Direction,
    val referenceEntryPrice: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val source: String,
    val confidence: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Authoritative close details reconstructed from MetaApi history deals. */
data class Mt4ClosedPositionDetails(
    val positionId: Long,
    val exitPrice: Double,
    val exitTime: Long,
    val realizedProfit: Double,
)
