package com.foxtrader.app.domain.usecase.release

import com.foxtrader.app.domain.model.ReleaseReadinessInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReadinessEvaluatorTest {
    private val evaluator = ReleaseReadinessEvaluator()

    @Test
    fun `production release blocks without signing`() {
        val report = evaluator.evaluate(safeInput().copy(productionBuild = true, releaseSigningConfigured = false))
        assertFalse(report.releasable)
        assertTrue(report.checks.any { it.id == "signing" && it.severity.name == "BLOCKER" })
    }

    @Test
    fun `release blocks if unattended live execution becomes possible`() {
        val report = evaluator.evaluate(safeInput().copy(unattendedLiveExecutionPossible = true))
        assertFalse(report.releasable)
    }

    @Test
    fun `safe production input is releasable`() {
        val report = evaluator.evaluate(safeInput())
        assertTrue(report.releasable)
    }

    private fun safeInput() = ReleaseReadinessInput(
        productionBuild = true,
        releaseSigningConfigured = true,
        cleartextTrafficAllowed = false,
        releaseHttpLoggingEnabled = false,
        encryptedCredentialStorage = true,
        unattendedLiveExecutionPossible = false,
        backendBaseUrl = "https://api.example.com/",
        certificatePinsConfigured = true,
        crashReportingConfigured = true,
        unitTestsPassed = true,
        lintPassed = true,
    )
}
