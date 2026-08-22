package com.foxtrader.app.domain.usecase.deriv

import com.foxtrader.app.domain.model.deriv.DerivProposalRequest
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DerivRequestBuilderTest {
    @Test
    fun proposalUsesNewApiUnderlyingSymbolField() {
        val json = DerivRequestBuilder.proposal(
            DerivProposalRequest(
                underlyingSymbol = "1HZ100V",
                amount = 10.0,
                contractType = "CALL",
                duration = 5,
                durationUnit = "m",
            ),
            reqId = 42,
        )
        assertEquals("1HZ100V", json["underlying_symbol"]?.jsonPrimitive?.content)
        assertFalse(json.containsKey("symbol"))
        assertFalse(json.containsKey("loginid"))
        assertEquals(10.0, json["amount"]?.jsonPrimitive?.double)
        assertEquals(42, json["req_id"]?.jsonPrimitive?.int)
    }

    @Test
    fun positionManagementUsesNewApiShape() {
        val sell = DerivRequestBuilder.sell(123L, 0.0, 9)
        assertEquals(123L, sell["sell"]?.jsonPrimitive?.content?.toLong())
        assertEquals(0.0, sell["price"]?.jsonPrimitive?.double)
        assertFalse(sell.containsKey("loginid"))

        val update = DerivRequestBuilder.contractUpdate(123L, 10.0, 20.0, 10)
        assertFalse(update.containsKey("loginid"))
        assertEquals(123L, update["contract_id"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun systemAndSubscriptionRequestsAreBounded() {
        val history = DerivRequestBuilder.ticksHistory("1HZ100V", 60, 99_999, 7)
        assertEquals(5_000, history["count"]?.jsonPrimitive?.int)
        assertEquals("candles", history["style"]?.jsonPrimitive?.content)
        val update = DerivRequestBuilder.contractUpdateHistory(123L, 5_000, 8)
        assertEquals(999, update["limit"]?.jsonPrimitive?.int)
    }
    @Test
    fun historyAndDiscoveryRequestsUseNewApiShapeAndBounds() {
        val contracts = DerivRequestBuilder.contractsList(11)
        assertEquals(1, contracts["contracts_list"]?.jsonPrimitive?.int)
        assertFalse(contracts.containsKey("loginid"))

        val profit = DerivRequestBuilder.profitTable(limit = 5_000, offset = -10, reqId = 12)
        assertEquals(500, profit["limit"]?.jsonPrimitive?.int)
        assertEquals(0, profit["offset"]?.jsonPrimitive?.int)
        assertEquals("DESC", profit["sort"]?.jsonPrimitive?.content)
        assertFalse(profit.containsKey("loginid"))

        val statement = DerivRequestBuilder.statement(limit = 5_000, offset = -5, actionType = "buy", reqId = 13)
        assertEquals(999, statement["limit"]?.jsonPrimitive?.int)
        assertEquals(0, statement["offset"]?.jsonPrimitive?.int)
        assertEquals("buy", statement["action_type"]?.jsonPrimitive?.content)
        assertFalse(statement.containsKey("loginid"))
    }

}
