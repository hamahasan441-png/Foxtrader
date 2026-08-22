package com.foxtrader.app.domain.usecase.deriv

import com.foxtrader.app.domain.model.deriv.DerivProposalRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DerivRequestBuilder {
    fun activeSymbols(reqId: Int): JsonObject = buildJsonObject {
        put("active_symbols", "brief")
        put("req_id", reqId)
    }

    fun contractsFor(symbol: String, reqId: Int): JsonObject = buildJsonObject {
        put("contracts_for", symbol)
        put("req_id", reqId)
    }

    fun contractsList(reqId: Int): JsonObject = buildJsonObject {
        put("contracts_list", 1)
        put("req_id", reqId)
    }

    fun ticks(symbol: String, reqId: Int, subscribe: Boolean = true): JsonObject = buildJsonObject {
        put("ticks", symbol)
        put("subscribe", if (subscribe) 1 else 0)
        put("req_id", reqId)
    }

    fun ticksHistory(
        symbol: String,
        granularitySeconds: Int,
        count: Int,
        reqId: Int,
        endEpochSeconds: Long? = null,
    ): JsonObject = buildJsonObject {
        require(symbol.isNotBlank()) { "Deriv symbol is required" }
        require(granularitySeconds > 0) { "Deriv candle granularity must be positive" }
        put("ticks_history", symbol.trim())
        put("end", endEpochSeconds?.coerceAtLeast(0L)?.toString() ?: "latest")
        put("style", "candles")
        put("granularity", granularitySeconds)
        put("count", count.coerceIn(1, 5000))
        // Explicit one-shot request. The current Deriv API accepts 0/1 here;
        // sending 0 avoids accidentally creating a subscription while paging.
        put("subscribe", 0)
        put("req_id", reqId)
    }

    fun balance(reqId: Int, subscribe: Boolean = false): JsonObject = buildJsonObject {
        put("balance", 1)
        put("subscribe", if (subscribe) 1 else 0)
        put("req_id", reqId)
    }

    fun portfolio(reqId: Int): JsonObject = buildJsonObject {
        put("portfolio", 1)
        put("req_id", reqId)
    }

    fun profitTable(limit: Int, offset: Int, reqId: Int): JsonObject = buildJsonObject {
        put("profit_table", 1)
        put("limit", limit.coerceIn(1, 500))
        put("offset", offset.coerceAtLeast(0))
        put("sort", "DESC")
        put("req_id", reqId)
    }

    fun statement(limit: Int, offset: Int, actionType: String?, reqId: Int): JsonObject = buildJsonObject {
        put("statement", 1)
        put("limit", limit.coerceIn(1, 999))
        put("offset", offset.coerceAtLeast(0))
        actionType?.trim()?.takeIf { it.isNotEmpty() }?.let { put("action_type", it) }
        put("req_id", reqId)
    }

    fun proposal(request: DerivProposalRequest, reqId: Int): JsonObject = buildJsonObject {
        put("proposal", 1)
        put("amount", request.amount)
        put("basis", request.basis)
        put("contract_type", request.contractType)
        put("currency", request.currency)
        put("underlying_symbol", request.underlyingSymbol)
        request.duration?.let { put("duration", it) }
        request.durationUnit?.let { put("duration_unit", it) }
        request.multiplier?.let { put("multiplier", it) }
        request.barrier?.let { put("barrier", it) }
        request.barrier2?.let { put("barrier2", it) }
        put("req_id", reqId)
    }

    fun buy(proposalId: String, maxPrice: Double, reqId: Int): JsonObject = buildJsonObject {
        put("buy", proposalId)
        put("price", maxPrice)
        put("req_id", reqId)
    }

    fun proposalOpenContract(contractId: Long, reqId: Int, subscribe: Boolean = false): JsonObject = buildJsonObject {
        put("proposal_open_contract", 1)
        put("contract_id", contractId)
        put("subscribe", if (subscribe) 1 else 0)
        put("req_id", reqId)
    }

    fun contractUpdate(contractId: Long, stopLoss: Double?, takeProfit: Double?, reqId: Int): JsonObject = buildJsonObject {
        put("contract_update", 1)
        put("contract_id", contractId)
        put("limit_order", buildJsonObject {
            stopLoss?.let { put("stop_loss", it) }
            takeProfit?.let { put("take_profit", it) }
        })
        put("req_id", reqId)
    }

    fun contractUpdateHistory(contractId: Long, limit: Int, reqId: Int): JsonObject = buildJsonObject {
        put("contract_update_history", 1)
        put("contract_id", contractId)
        put("limit", limit.coerceIn(1, 999))
        put("req_id", reqId)
    }

    fun cancel(contractId: Long, reqId: Int): JsonObject = buildJsonObject {
        put("cancel", contractId)
        put("req_id", reqId)
    }

    fun transaction(reqId: Int): JsonObject = buildJsonObject {
        put("transaction", 1)
        put("subscribe", 1)
        put("req_id", reqId)
    }

    fun ping(reqId: Int): JsonObject = buildJsonObject {
        put("ping", 1)
        put("req_id", reqId)
    }

    fun serverTime(reqId: Int): JsonObject = buildJsonObject {
        put("time", 1)
        put("req_id", reqId)
    }

    fun tradingTimes(date: String = "today", reqId: Int): JsonObject = buildJsonObject {
        put("trading_times", date)
        put("req_id", reqId)
    }

    fun forgetAll(types: List<String>, reqId: Int): JsonObject = buildJsonObject {
        put("forget_all", JsonArray(types.map(::JsonPrimitive)))
        put("req_id", reqId)
    }

    fun sell(contractId: Long, minimumPrice: Double, reqId: Int): JsonObject = buildJsonObject {
        put("sell", contractId)
        put("price", minimumPrice)
        put("req_id", reqId)
    }

    fun forget(subscriptionId: String, reqId: Int): JsonObject = buildJsonObject {
        put("forget", subscriptionId)
        put("req_id", reqId)
    }
}
