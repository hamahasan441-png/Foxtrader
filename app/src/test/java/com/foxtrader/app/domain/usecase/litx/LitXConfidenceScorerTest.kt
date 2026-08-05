package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.LitXGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LitXConfidenceScorerTest {

    private val scorer = LitXConfidenceScorer()

    private fun inputs(v: Int) = LitXConfidenceScorer.Inputs(v, v, v, v, v, v, v, v, v, v, v)

    @Test
    fun `all factors maxed yields 100 and A+`() {
        val c = scorer.score(inputs(100))
        assertEquals(100, c.score)
        assertEquals(LitXGrade.A_PLUS, c.grade)
        assertEquals(11, c.factors.size)
    }

    @Test
    fun `all factors zero yields 0 and Reject`() {
        val c = scorer.score(inputs(0))
        assertEquals(0, c.score)
        assertEquals(LitXGrade.REJECT, c.grade)
    }

    @Test
    fun `grade thresholds map correctly`() {
        assertEquals(LitXGrade.A_PLUS, scorer.score(inputs(90)).grade)
        assertEquals(LitXGrade.A, scorer.score(inputs(78)).grade)
        assertEquals(LitXGrade.B, scorer.score(inputs(62)).grade)
        assertEquals(LitXGrade.REJECT, scorer.score(inputs(40)).grade)
    }

    @Test
    fun `meets enforces minimum grade ordering`() {
        assertTrue(LitXConfidenceScorer.meets(LitXGrade.A_PLUS, LitXGrade.A))
        assertTrue(LitXConfidenceScorer.meets(LitXGrade.A, LitXGrade.A))
        assertFalse(LitXConfidenceScorer.meets(LitXGrade.B, LitXGrade.A))
        assertFalse(LitXConfidenceScorer.meets(LitXGrade.REJECT, LitXGrade.B))
    }
}
