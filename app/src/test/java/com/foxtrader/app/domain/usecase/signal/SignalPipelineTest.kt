package com.foxtrader.app.domain.usecase.signal

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SignalPipeline] and [SignalProcessor].
 *
 * Validates:
 * - Passthrough pipeline leaves the decision unchanged
 * - Single and multi-processor pipelines apply transformations in order
 * - Exceptions in a processor are swallowed (pipeline stays resilient)
 * - Disapproved decisions pass through all processors
 */
class SignalPipelineTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun approvedDecision(confidence: Double = 75.0) = DecisionResult(
        approved = true,
        direction = Direction.BULLISH,
        confidence = confidence,
        grade = SignalGrade.STRONG,
        confluencePresent = listOf(RequiredConfluence.BOS_OR_CHOCH, RequiredConfluence.FVG, RequiredConfluence.ORDER_BLOCK,
            RequiredConfluence.HTF_BIAS, RequiredConfluence.TREND),
        confluenceMissing = emptyList(),
        blockReasons = emptyList(),
        explanation = "Approved",
        timestamp = System.currentTimeMillis(),
    )

    private fun rejectedDecision() = DecisionResult(
        approved = false,
        direction = null,
        confidence = 30.0,
        grade = SignalGrade.NO_SIGNAL,
        confluencePresent = emptyList(),
        confluenceMissing = RequiredConfluence.all(),
        blockReasons = listOf("No consensus"),
        explanation = "Rejected",
        timestamp = System.currentTimeMillis(),
    )

    private fun context() = AgentContext(
        symbol = "EURUSD",
        timeframe = Timeframe.M15,
        candles = listOf(
            Candle(System.currentTimeMillis(), 1.1000, 1.1010, 1.0990, 1.1005, 100.0)
        ),
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `passthrough pipeline returns the same decision instance`() {
        val decision = approvedDecision()
        val ctx = context()
        val result = SignalPipeline.PASSTHROUGH.process(decision, ctx)
        assertSame("Passthrough should return the exact same object", decision, result)
    }

    @Test
    fun `empty pipeline is passthrough`() {
        val pipeline = SignalPipeline()
        assertTrue(pipeline.isEmpty)
        val decision = approvedDecision()
        assertSame(decision, pipeline.process(decision, context()))
    }

    @Test
    fun `single processor can modify decision`() {
        val processor = SignalProcessor { d, _ -> d.copy(confidence = 99.0) }
        val pipeline = SignalPipeline(listOf(processor))
        val result = pipeline.process(approvedDecision(50.0), context())
        assertEquals(99.0, result.confidence, 1e-6)
    }

    @Test
    fun `processors are applied in order`() {
        val calls = mutableListOf<Int>()
        val p1 = SignalProcessor { d, _ -> calls += 1; d.copy(confidence = 60.0) }
        val p2 = SignalProcessor { d, _ -> calls += 2; d.copy(confidence = d.confidence + 10.0) }
        val pipeline = SignalPipeline(listOf(p1, p2))
        val result = pipeline.process(approvedDecision(40.0), context())
        assertEquals(listOf(1, 2), calls)
        assertEquals(70.0, result.confidence, 1e-6)
    }

    @Test
    fun `faulty processor exception is swallowed and previous result is used`() {
        val goodProcessor = SignalProcessor { d, _ -> d.copy(confidence = 80.0) }
        val faultyProcessor = SignalProcessor { _, _ -> error("Processor crashed!") }
        val pipeline = SignalPipeline(listOf(goodProcessor, faultyProcessor))
        // Should not throw; result should be what goodProcessor returned
        val result = pipeline.process(approvedDecision(50.0), context())
        assertEquals(80.0, result.confidence, 1e-6)
    }

    @Test
    fun `processor can veto an approved decision`() {
        val veto = SignalProcessor { d, _ ->
            d.copy(approved = false, blockReasons = listOf("Veto from processor"))
        }
        val pipeline = SignalPipeline(listOf(veto))
        val result = pipeline.process(approvedDecision(), context())
        assertFalse(result.approved)
        assertTrue(result.blockReasons.any { it.contains("Veto") })
    }

    @Test
    fun `all processors run even when decision is disapproved mid-pipeline`() {
        val calls = mutableListOf<Int>()
        val p1 = SignalProcessor { d, _ -> calls += 1; d.copy(approved = false) }
        val p2 = SignalProcessor { d, _ -> calls += 2; d }
        val pipeline = SignalPipeline(listOf(p1, p2))
        pipeline.process(approvedDecision(), context())
        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun `rejected decision passes through unchanged by a no-op processor`() {
        val noOp = SignalProcessor { d, _ -> d }
        val pipeline = SignalPipeline(listOf(noOp))
        val rejected = rejectedDecision()
        val result = pipeline.process(rejected, context())
        assertFalse(result.approved)
        assertEquals("No consensus", result.blockReasons.first())
    }
}
